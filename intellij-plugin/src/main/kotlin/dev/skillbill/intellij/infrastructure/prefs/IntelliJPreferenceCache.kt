package dev.skillbill.intellij.infrastructure.prefs

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.skillbill.intellij.application.PreferenceCachePort
import dev.skillbill.intellij.domain.CachedDisplaySnapshot
import dev.skillbill.intellij.domain.DEFAULT_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.LastKnownDisplayCache
import dev.skillbill.intellij.domain.StatusExecutionMetadata
import dev.skillbill.intellij.domain.MAX_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.MIN_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.infrastructure.AbsolutePathGuard
import java.time.Instant


@State(
    name = "SkillBillApplicationSettings",
    storages = [Storage("skillBillSettings.xml")],
)
class SkillBillApplicationSettings : PersistentStateComponent<SkillBillApplicationSettings.State> {
    data class State(
        var cliExecutableOverride: String? = null,
        var refreshIntervalSeconds: Long = DEFAULT_REFRESH_INTERVAL_SECONDS,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state.copy(
            cliExecutableOverride = PreferenceSanitizer.sanitizeExecutablePath(state.cliExecutableOverride),
            refreshIntervalSeconds = PreferenceSanitizer.sanitizeRefreshInterval(state.refreshIntervalSeconds),
        )
    }

    fun readCliOverride(): String? = PreferenceSanitizer.sanitizeExecutablePath(state.cliExecutableOverride)

    fun writeCliOverride(path: String?) {
        state.cliExecutableOverride = PreferenceSanitizer.sanitizeExecutablePath(path)
    }

    fun readRefreshIntervalSeconds(): Long =
        PreferenceSanitizer.sanitizeRefreshInterval(state.refreshIntervalSeconds)

    fun writeRefreshIntervalSeconds(seconds: Long) {
        state.refreshIntervalSeconds = PreferenceSanitizer.sanitizeRefreshInterval(seconds)
    }
}


@State(
    name = "SkillBillProjectDisplayCache",
    storages = [Storage("skillBillDisplayCache.xml")],
)
class SkillBillProjectDisplayCache : PersistentStateComponent<SkillBillProjectDisplayCache.State> {
    data class State(
        var summary: String? = null,
        var repositoryIdentity: String? = null,
        var issueKey: String? = null,
        var currentStepId: String? = null,
        var currentStepLabel: String? = null,
        var progressCompleted: Int? = null,
        var progressTotal: Int? = null,
        var startedAt: String? = null,
        var currentSubtaskId: String? = null,
        var subtaskStartedAt: String? = null,
        var updatedAt: String? = null,
        var observedAt: String? = null,
        var activeDurationMs: Long? = null,
        var activeDurationAsOf: String? = null,
        var subtaskActiveDurationMs: Long? = null,
        var subtaskActiveDurationAsOf: String? = null,
        var executionScope: String? = null,
        var executionId: String? = null,
        var statusStoreId: String? = null,
        var branchCorrelation: String? = null,
        var runSequence: String? = null,
        var statusRevision: String? = null,
        var invocationId: String? = null,
        var phaseId: String? = null,
        var currentActivity: String? = null,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = PreferenceSanitizer.sanitizeCacheState(state)
    }

    fun read(): LastKnownDisplayCache? {
        val observedRaw = state.observedAt ?: return null
        val summary = state.summary?.takeIf { it.isNotBlank() } ?: return null
        val observedAt = runCatching { Instant.parse(observedRaw) }.getOrNull() ?: return null
        return LastKnownDisplayCache(
            display = CachedDisplaySnapshot(
                summary = summary.take(CachedDisplaySnapshot.MAX_SUMMARY_CHARS),
                repositoryIdentity = state.repositoryIdentity,
                issueKey = state.issueKey,
                currentStepId = state.currentStepId,
                currentStepLabel = state.currentStepLabel,
                progressCompleted = state.progressCompleted,
                progressTotal = state.progressTotal,
                startedAt = state.startedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                currentSubtaskId = state.currentSubtaskId,
                subtaskStartedAt = state.subtaskStartedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                updatedAt = state.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                activeDurationMs = state.activeDurationMs?.takeIf { it >= 0L },
                activeDurationAsOf =
                    state.activeDurationAsOf?.let { runCatching { Instant.parse(it) }.getOrNull() },
                subtaskActiveDurationMs = state.subtaskActiveDurationMs?.takeIf { it >= 0L },
                subtaskActiveDurationAsOf =
                    state.subtaskActiveDurationAsOf?.let { runCatching { Instant.parse(it) }.getOrNull() },
                execution = StatusExecutionMetadata(
                    executionScope = state.executionScope,
                    executionId = state.executionId,
                    statusStoreId = state.statusStoreId,
                    branchCorrelation = state.branchCorrelation,
                    runSequence = state.runSequence,
                    statusRevision = state.statusRevision,
                    invocationId = state.invocationId,
                    phaseId = state.phaseId,
                    currentActivity = state.currentActivity,
                ).takeIf { it.executionScope != null },
            ),
            observedAt = observedAt,
        )
    }

    fun write(cache: LastKnownDisplayCache?) {
        if (cache == null) {
            state = State()
            return
        }
        val sanitized = PreferenceSanitizer.sanitizeCache(cache) ?: run {
            state = State()
            return
        }
        state = State(
            summary = sanitized.display.summary,
            repositoryIdentity = sanitized.display.repositoryIdentity,
            issueKey = sanitized.display.issueKey,
            currentStepId = sanitized.display.currentStepId,
            currentStepLabel = sanitized.display.currentStepLabel,
            progressCompleted = sanitized.display.progressCompleted,
            progressTotal = sanitized.display.progressTotal,
            startedAt = sanitized.display.startedAt?.toString(),
            currentSubtaskId = sanitized.display.currentSubtaskId,
            subtaskStartedAt = sanitized.display.subtaskStartedAt?.toString(),
            updatedAt = sanitized.display.updatedAt?.toString(),
            observedAt = sanitized.observedAt.toString(),
            activeDurationMs = sanitized.display.activeDurationMs,
            activeDurationAsOf = sanitized.display.activeDurationAsOf?.toString(),
            subtaskActiveDurationMs = sanitized.display.subtaskActiveDurationMs,
            subtaskActiveDurationAsOf = sanitized.display.subtaskActiveDurationAsOf?.toString(),
            executionScope = sanitized.display.execution?.executionScope,
            executionId = sanitized.display.execution?.executionId,
            statusStoreId = sanitized.display.execution?.statusStoreId,
            branchCorrelation = sanitized.display.execution?.branchCorrelation,
            runSequence = sanitized.display.execution?.runSequence,
            statusRevision = sanitized.display.execution?.statusRevision,
            invocationId = sanitized.display.execution?.invocationId,
            phaseId = sanitized.display.execution?.phaseId,
            currentActivity = sanitized.display.execution?.currentActivity,
        )
    }
}

class IntelliJPreferenceCache(
    private val applicationSettings: SkillBillApplicationSettings,
    private val projectDisplayCache: SkillBillProjectDisplayCache,
) : PreferenceCachePort {
    override fun getCliExecutableOverride(): String? = applicationSettings.readCliOverride()

    override fun setCliExecutableOverride(path: String?) {
        applicationSettings.writeCliOverride(path)
    }

    override fun getRefreshIntervalSeconds(): Long = applicationSettings.readRefreshIntervalSeconds()

    override fun setRefreshIntervalSeconds(seconds: Long) {
        applicationSettings.writeRefreshIntervalSeconds(seconds)
    }

    override fun getLastKnownDisplayCache(): LastKnownDisplayCache? = projectDisplayCache.read()

    override fun setLastKnownDisplayCache(cache: LastKnownDisplayCache?) {
        projectDisplayCache.write(cache)
    }

    companion object {
        fun forProject(project: Project): IntelliJPreferenceCache =
            IntelliJPreferenceCache(
                applicationSettings = service<SkillBillApplicationSettings>(),
                projectDisplayCache = project.service<SkillBillProjectDisplayCache>(),
            )
    }
}


object PreferenceSanitizer {
    private val FORBIDDEN_KEYS = setOf(
        "token", "prompt", "phase", "stderr", "password", "secret", "api_key", "apikey",
    )

    fun sanitizeExecutablePath(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.length > 1_024) return null
        if (looksLikeSecret(value)) return null
        return value
    }

    fun sanitizeRefreshInterval(seconds: Long): Long =
        when {
            seconds < MIN_REFRESH_INTERVAL_SECONDS -> MIN_REFRESH_INTERVAL_SECONDS
            seconds > MAX_REFRESH_INTERVAL_SECONDS -> MAX_REFRESH_INTERVAL_SECONDS
            else -> seconds
        }

    fun sanitizeCache(cache: LastKnownDisplayCache): LastKnownDisplayCache? {
        val summary = cache.display.summary.trim()
        if (summary.isEmpty() || looksLikeSecret(summary) || looksLikeUnboundedBlob(summary)) {
            return null
        }
        return cache.copy(
            display = cache.display.copy(
                summary = summary.take(CachedDisplaySnapshot.MAX_SUMMARY_CHARS),
                repositoryIdentity = sanitizeRepositoryIdentity(cache.display.repositoryIdentity),
                issueKey = cache.display.issueKey?.take(64),
                currentStepId = cache.display.currentStepId?.take(64),
                currentStepLabel = cache.display.currentStepLabel?.take(128),
                currentSubtaskId = cache.display.currentSubtaskId?.take(64),
            ),
        )
    }

    fun sanitizeCacheState(state: SkillBillProjectDisplayCache.State): SkillBillProjectDisplayCache.State {
        if (state.observedAt.isNullOrBlank() || state.summary.isNullOrBlank()) {
            return SkillBillProjectDisplayCache.State()
        }
        if (looksLikeSecret(state.summary!!) || looksLikeUnboundedBlob(state.summary!!)) {
            return SkillBillProjectDisplayCache.State()
        }
        return state.copy(
            summary = state.summary?.take(CachedDisplaySnapshot.MAX_SUMMARY_CHARS),
            repositoryIdentity = sanitizeRepositoryIdentity(state.repositoryIdentity),
            branchCorrelation = state.branchCorrelation?.trim()?.take(256),
        )
    }

    
    fun sanitizeRepositoryIdentity(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (AbsolutePathGuard.containsAbsolutePath(value)) return null
        if (looksLikeSecret(value)) return null
        return value.take(256)
    }

    fun looksLikeSecret(value: String): Boolean {
        val lower = value.lowercase()
        if (FORBIDDEN_KEYS.any { lower.contains(it) && (lower.contains('=') || lower.contains(':')) }) {
            return true
        }
        if (lower.contains("stderr")) return true
        if (lower.startsWith("sk-") || lower.contains("bearer ")) return true
        return false
    }

    fun looksLikeUnboundedBlob(value: String): Boolean =
        value.length > CachedDisplaySnapshot.MAX_SUMMARY_CHARS * 2 || value.count { it == '\n' } > 20
}
