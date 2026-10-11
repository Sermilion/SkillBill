package dev.skillbill.intellij.application

import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusExecutionMetadata
import dev.skillbill.intellij.domain.UNCORROBORATED_IDLE_TOLERANCE
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.domain.isLiveOutcome
import dev.skillbill.intellij.domain.isPollTransportFailure
import dev.skillbill.intellij.domain.isUncorroboratedIdle
import dev.skillbill.intellij.domain.toCacheSnapshotOrNull
import dev.skillbill.intellij.domain.toStaleOutcome
import dev.skillbill.intellij.domain.withPollFailure
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException


class StatusRefreshCoordinator(
    private val statusRepository: StatusRepository,
    private val preferences: PreferenceCachePort,
    private val scope: CoroutineScope,
    private val projectRoot: Path,
    private val onCancelProcesses: () -> Unit = {},
) {
    private val activeConsumers = AtomicInteger(0)
    private val disposed = AtomicBoolean(false)
    private val refreshMutex = Mutex()
    private val _outcomes = MutableStateFlow<SkillBillStatusOutcome?>(null)
    val outcomes: StateFlow<SkillBillStatusOutcome?> = _outcomes.asStateFlow()

    private val _events = MutableSharedFlow<CoordinatorEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CoordinatorEvent> = _events.asSharedFlow()

    
    private var unconfirmedIdleSamples = 0

    @Volatile
    private var refreshGeneration = 0L

    private var acceptedOutcome: SkillBillStatusOutcome? = null

    @Volatile
    private var pollJob: Job? = null

    fun addConsumer() {
        if (disposed.get()) return
        if (activeConsumers.incrementAndGet() == 1) {
            startPolling()
        }
    }

    fun removeConsumer() {
        if (activeConsumers.decrementAndGet() <= 0) {
            activeConsumers.set(0)
            stopPolling()
        }
    }

    fun requestRefresh() {
        if (disposed.get()) return
        scope.launch {
            refreshOnce()
        }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        refreshGeneration += 1
        activeConsumers.set(0)
        stopPolling()
        onCancelProcesses()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && !disposed.get() && activeConsumers.get() > 0) {
                refreshOnce()
                val intervalMs = preferences.getRefreshIntervalSeconds().coerceAtLeast(1L) * 1_000L
                delay(intervalMs)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() {
        if (disposed.get()) return
        refreshMutex.withLock {
            if (disposed.get()) return
            val requestGeneration = refreshGeneration
            val outcome = try {
                statusRepository.fetchStatus(projectRoot)
            } catch (_: CancellationException) {
                return
            } catch (_: Exception) {
                if (disposed.get() || requestGeneration != refreshGeneration) return
                emit(transportFailureFallback(UnavailableReason.PROCESS_FAILURE) ?: return)
                return
            }

            if (disposed.get() || requestGeneration != refreshGeneration) return
            if (outcome.executionMetadata()?.runSequence != null && outcome.executionMetadata()?.branchCorrelation == null) {
                currentCorrelationMatchesCache()?.let { emit(it.toStaleOutcome()) }
                return
            }
            if (outcome.isSuccessfulSnapshot() && correlationChanged(outcome)) {
                refreshGeneration += 1
                _outcomes.value = null
                acceptedOutcome = null
                preferences.setLastKnownDisplayCache(null)
                unconfirmedIdleSamples = 0
            }
            if (isStoreReplacement(orderingOutcome(), outcome)) {
                refreshGeneration += 1
                _outcomes.value = null
                acceptedOutcome = null
                preferences.setLastKnownDisplayCache(null)
                unconfirmedIdleSamples = 0
            }


            if (outcome.isUncorroboratedIdle()) {
                val held = _outcomes.value
                if (held != null && held.isLiveOutcome()) {
                    unconfirmedIdleSamples += 1
                    if (unconfirmedIdleSamples <= UNCORROBORATED_IDLE_TOLERANCE) {
                        emit(held)
                        return
                    }
                }
            } else {
                unconfirmedIdleSamples = 0
            }

            if (outcome is SkillBillStatusOutcome.Unavailable && outcome.reasonCode.isPollTransportFailure()) {
                val fallback = transportFailureFallback(outcome.reasonCode)
                if (fallback != null) {
                    emit(fallback)
                    return
                }
            }

            if (!acceptsNewerStatus(orderingOutcome(), outcome)) {
                if (_outcomes.value == null) preferences.getLastKnownDisplayCache()?.let { emit(it.toStaleOutcome()) }
                return
            }
            if (outcome.isSuccessfulSnapshot()) acceptedOutcome = outcome
            val toEmit = when {
                outcome is SkillBillStatusOutcome.Unavailable && outcome.reasonCode.isPollTransportFailure() ->
                    transportFailureFallback(outcome.reasonCode) ?: outcome

                outcome is SkillBillStatusOutcome.Unavailable ||
                    outcome is SkillBillStatusOutcome.Incompatible ->
                    currentCorrelationMatchesCache()?.toStaleOutcome() ?: outcome

                else -> {
                    outcome.toCacheSnapshotOrNull()?.let { preferences.setLastKnownDisplayCache(it) }
                    outcome
                }
            }
            emit(toEmit)
        }
    }

    private fun orderingOutcome(): SkillBillStatusOutcome? =
        acceptedOutcome ?: preferences.getLastKnownDisplayCache()?.toStaleOutcome() ?: _outcomes.value

    private fun transportFailureFallback(reason: UnavailableReason): SkillBillStatusOutcome? =
        _outcomes.value?.takeIf { it.isLiveOutcome() }?.withPollFailure(reason)
            ?: currentCorrelationMatchesCache()?.toStaleOutcome()

    private fun currentCorrelationMatchesCache() =
        preferences.getLastKnownDisplayCache()?.takeIf { cache ->
            val currentOutcome = acceptedOutcome ?: _outcomes.value
            val current = currentOutcome?.correlation()
            val cached = cache.correlation()
            val currentRepository = currentOutcome?.repositoryIdentity()
            val cachedRepository = cache.display.repositoryIdentity
            currentRepository == null || cachedRepository == null || currentRepository == cachedRepository &&
                (current == null || cached == null || current == cached)
        }

    private fun correlationChanged(incoming: SkillBillStatusOutcome): Boolean {
        val incomingCorrelation = incoming.correlation() ?: return false
        val current = (acceptedOutcome ?: _outcomes.value)?.correlation()
        val cache = preferences.getLastKnownDisplayCache()
        val cached = cache?.correlation()
        if (cache != null && cached == null) return true
        return listOfNotNull(current, cached).any { it != incomingCorrelation }
    }

    private fun emit(outcome: SkillBillStatusOutcome) {
        _outcomes.value = outcome
        _events.tryEmit(CoordinatorEvent.Refreshed(outcome))
    }
}

sealed class CoordinatorEvent {
    data class Refreshed(val outcome: SkillBillStatusOutcome) : CoordinatorEvent()
}

private data class StatusCorrelation(
    val repositoryIdentity: String,
    val branchCorrelation: String,
)

private fun acceptsNewerStatus(
    current: SkillBillStatusOutcome?,
    incoming: SkillBillStatusOutcome,
): Boolean {
    val currentExecution = current?.executionMetadata() ?: return true
    val incomingExecution = incoming.executionMetadata() ?: return true
    if (current.repositoryIdentity() != incoming.repositoryIdentity()) return false
    if (currentExecution.branchCorrelation != incomingExecution.branchCorrelation) return false
    val currentSequence = currentExecution.runSequence ?: return isLiveOverDone(current, incoming)
    val incomingSequence = incomingExecution.runSequence ?: return isLiveOverDone(current, incoming)
    val sequenceOrder = compareDecimalStrings(incomingSequence, currentSequence)
    if (sequenceOrder < 0) return isLiveOverDone(current, incoming)
    if (sequenceOrder > 0) return true
    if (currentExecution.executionId != incomingExecution.executionId) return false
    val currentRevision = currentExecution.statusRevision ?: return false
    val incomingRevision = incomingExecution.statusRevision ?: return false
    if (compareDecimalStrings(incomingRevision, currentRevision) < 0) return false
    if (isTerminal(current) && current::class != incoming::class) return false
    return true
}

private fun isTerminal(outcome: SkillBillStatusOutcome): Boolean = when (outcome) {
    is SkillBillStatusOutcome.Done,
    is SkillBillStatusOutcome.Blocked,
    is SkillBillStatusOutcome.Failed,
    -> true
    else -> false
}

private fun isLiveOverDone(
    current: SkillBillStatusOutcome,
    incoming: SkillBillStatusOutcome,
): Boolean {
    val incomingLive = when (incoming) {
        is SkillBillStatusOutcome.Active,
        is SkillBillStatusOutcome.Paused,
        is SkillBillStatusOutcome.Blocked,
        -> true
        else -> false
    }
    if (!incomingLive) return false
    if (current.executionMetadata()?.executionId == incoming.executionMetadata()?.executionId) return false
    return isTerminal(current) || !bothLiveStandalone(current, incoming)
}

private fun bothLiveStandalone(
    current: SkillBillStatusOutcome,
    incoming: SkillBillStatusOutcome,
): Boolean = isLiveStandalone(current) && isLiveStandalone(incoming)

private fun isLiveStandalone(outcome: SkillBillStatusOutcome): Boolean =
    when (outcome) {
        is SkillBillStatusOutcome.Active, is SkillBillStatusOutcome.Paused ->
            outcome.executionMetadata()?.executionScope == "standalone_phase"
        else -> false
    }

private fun isStoreReplacement(
    current: SkillBillStatusOutcome?,
    incoming: SkillBillStatusOutcome,
): Boolean {
    val currentStore = current?.executionMetadata()?.statusStoreId
    val incomingStore = incoming.executionMetadata()?.statusStoreId
    return currentStore != null && incomingStore != null && currentStore != incomingStore
}

private fun SkillBillStatusOutcome.repositoryIdentity(): String? = when (this) {
    is SkillBillStatusOutcome.Idle -> repositoryIdentity
    is SkillBillStatusOutcome.Done -> repositoryIdentity
    is SkillBillStatusOutcome.Active -> repositoryIdentity
    is SkillBillStatusOutcome.Paused -> repositoryIdentity
    is SkillBillStatusOutcome.Stale -> repositoryIdentity
    is SkillBillStatusOutcome.Blocked -> repositoryIdentity
    is SkillBillStatusOutcome.Failed -> repositoryIdentity
    is SkillBillStatusOutcome.Unavailable,
    is SkillBillStatusOutcome.Incompatible,
    -> null
}

private fun SkillBillStatusOutcome.executionMetadata(): StatusExecutionMetadata? = when (this) {
    is SkillBillStatusOutcome.Done -> execution
    is SkillBillStatusOutcome.Active -> execution
    is SkillBillStatusOutcome.Paused -> execution
    is SkillBillStatusOutcome.Stale -> execution
    is SkillBillStatusOutcome.Blocked -> execution
    is SkillBillStatusOutcome.Failed -> execution
    is SkillBillStatusOutcome.Idle,
    is SkillBillStatusOutcome.Unavailable,
    is SkillBillStatusOutcome.Incompatible,
    -> null
}

private fun SkillBillStatusOutcome.correlation(): StatusCorrelation? {
    val repositoryIdentity = repositoryIdentity() ?: return null
    val branchCorrelation = executionMetadata()?.branchCorrelation ?: return null
    return StatusCorrelation(repositoryIdentity, branchCorrelation)
}

private fun LastKnownDisplayCache.correlation(): StatusCorrelation? {
    val repositoryIdentity = display.repositoryIdentity ?: return null
    val branchCorrelation = display.execution?.branchCorrelation ?: return null
    return StatusCorrelation(repositoryIdentity, branchCorrelation)
}

private fun SkillBillStatusOutcome.isSuccessfulSnapshot(): Boolean = when (this) {
    is SkillBillStatusOutcome.Unavailable,
    is SkillBillStatusOutcome.Incompatible,
    -> false

    else -> true
}

private fun compareDecimalStrings(left: String, right: String): Int {
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return when {
        normalizedLeft.length != normalizedRight.length -> normalizedLeft.length.compareTo(normalizedRight.length)
        normalizedLeft == normalizedRight -> 0
        normalizedLeft > normalizedRight -> 1
        else -> -1
    }
}
