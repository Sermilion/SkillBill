package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.PartialUsageField
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageCoverage
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import kotlin.time.Duration
import kotlin.time.Instant

internal class UsageObservationMerge(initial: UsageObservation? = null) {
  private data class FieldKey(val source: UsageSource, val bucket: String)
  private data class Stamp(val revision: Long, val sourceTime: Instant?, val fieldTime: Instant)
  private data class CoverageStamp(val source: UsageSource, val stamp: Stamp)

  private var revision = 0L
  private val fields = mutableMapOf<FieldKey, Stamp>()
  private val replacements = mutableMapOf<UsageSource, Stamp>()
  private val sourceStamps = mutableMapOf<UsageSource, Stamp>()
  private var permissionStamp: Stamp? = null
  private var warningStamp: Stamp? = null
  private var coverageStamp: CoverageStamp? = null
  var observation: UsageObservation? = initial
    private set

  init {
    initial?.meters?.forEach { meter ->
      val source = meter.sourceOwner ?: initial.source
      fields[FieldKey(source, meter.bucketIdentity)] = Stamp(0, initial.sourceObservationTime, meter.observedAt)
    }
    initial?.let {
      sourceStamps[it.source] = Stamp(0, it.sourceObservationTime, it.collectionTime)
      coverageStamp = CoverageStamp(it.source, Stamp(0, it.sourceObservationTime, it.collectionTime))
      if (it.coverage == UsageCoverage.Complete || it.coverage == UsageCoverage.Empty) {
        replacements[it.source] = Stamp(0, it.sourceObservationTime, it.collectionTime)
      }
      it.statusObservedAt?.let { at ->
        if (it.requestPermission != RequestPermission.Unknown) permissionStamp = Stamp(0, it.sourceObservationTime, at)
        if (it.sessionWarning != null) warningStamp = Stamp(0, it.sourceObservationTime, at)
      }
    }
  }

  fun admit(incoming: UsageObservation, omission: OmissionSemantics, baseRevision: Long): UsageObservation? {
    val subject = incoming.subject as? UsageObservationSubject.Account ?: return null
    if (incoming.source.provider != subject.value.profile.provider || !validMeters(incoming.meters, incoming.source)) return null
    val previous = observation
    if (previous != null && previous.subject != incoming.subject) return null
    val nextRevision = ++revision
    val previousSource = previous?.source ?: incoming.source
    val sourceTime = incoming.sourceObservationTime
    val meters = previous?.meters.orEmpty().associateBy { FieldKey(it.sourceOwner ?: previousSource, it.bucketIdentity) }.toMutableMap()
    var numericAccepted = false
    var deletionAccepted = false
    val sourceKeys = meters.keys.filter { it.source == incoming.source }.toSet()
    val replacementAllowed = omission == OmissionSemantics.OmittedFieldsAreRemoved &&
      coverageIsNewer(incoming.source, incoming.sourceObservationTime, incoming.collectionTime, baseRevision) &&
      (replacements[incoming.source]?.let { stamp -> stamp.revision <= baseRevision &&
        (if (sourceTime != null && stamp.sourceTime != null) sourceTime > stamp.sourceTime
        else incoming.collectionTime > stamp.fieldTime) } != false)
    var replacementComplete = replacementAllowed
    if (replacementAllowed) {
      sourceKeys.filter { it.bucket !in incoming.meters.map(UsageMeter::bucketIdentity) }.forEach { key ->
        if (accept(key, incoming.sourceObservationTime, incoming.collectionTime, baseRevision)) {
          meters.remove(key)
          fields[key] = Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime)
          deletionAccepted = true
        } else replacementComplete = false
      }
    }
    incoming.meters.forEach { meter ->
      val key = FieldKey(incoming.source, meter.bucketIdentity)
      if (accept(key, incoming.sourceObservationTime, meter.observedAt, baseRevision)) {
        val current = meters[key]
        if (current != meter || fields[key]?.sourceTime != incoming.sourceObservationTime) {
          meters[key] = meter.copy(sourceOwner = incoming.source)
          fields[key] = Stamp(nextRevision, incoming.sourceObservationTime, meter.observedAt)
          numericAccepted = true
        }
      } else if (replacementAllowed) replacementComplete = false
    }
    val permissionAccepted = incoming.requestPermission != RequestPermission.Unknown &&
      acceptStatus(permissionStamp, incoming.sourceObservationTime, incoming.collectionTime, baseRevision)
    val warningAccepted = incoming.sessionWarning != null &&
      acceptStatus(warningStamp, incoming.sourceObservationTime, incoming.collectionTime, baseRevision)
    if (permissionAccepted) permissionStamp = Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime)
    if (warningAccepted) warningStamp = Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime)
    val coverageAccepted = coverageIsNewer(incoming.source, incoming.sourceObservationTime, incoming.collectionTime, baseRevision)
    val authoritativeCoverageAccepted = omission == OmissionSemantics.OmittedFieldsAreRemoved &&
      replacementAllowed && replacementComplete && coverageAccepted
    if (authoritativeCoverageAccepted) replacements[incoming.source] = Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime)
    val explicitEmptyAccepted = incoming.emptySuccess &&
      newer(sourceStamps[incoming.source], incoming.sourceObservationTime, incoming.collectionTime, baseRevision) &&
      (authoritativeCoverageAccepted || omission != OmissionSemantics.OmittedFieldsAreRemoved)
    if (numericAccepted || deletionAccepted || explicitEmptyAccepted) {
      sourceStamps[incoming.source] = Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime)
    }
    val basis = previous ?: incoming
    val nextCoverage = when {
      omission == OmissionSemantics.Unknown && coverageAccepted -> UsageCoverage.Unknown
      authoritativeCoverageAccepted && incoming.emptySuccess -> UsageCoverage.Empty
      authoritativeCoverageAccepted -> UsageCoverage.Complete
      (numericAccepted || deletionAccepted) && coverageAccepted -> UsageCoverage.Partial
      else -> basis.coverage
    }
    if (coverageAccepted && (nextCoverage != basis.coverage || omission == OmissionSemantics.Unknown || authoritativeCoverageAccepted)) {
      coverageStamp = CoverageStamp(incoming.source, Stamp(nextRevision, incoming.sourceObservationTime, incoming.collectionTime))
    }
    val metersList = meters.entries.sortedWith(compareBy({ it.key.source.provider.name }, { it.key.source.capability.name }, { it.key.bucket })).map { it.value }
    val result = basis.copy(
      collectionTime = if (numericAccepted || deletionAccepted || explicitEmptyAccepted) maxOf(basis.collectionTime, incoming.collectionTime) else basis.collectionTime,
      sourceObservationTime = if (numericAccepted || deletionAccepted || explicitEmptyAccepted) incoming.sourceObservationTime else basis.sourceObservationTime,
      source = incoming.source,
      meters = metersList,
      requestPermission = if (permissionAccepted) incoming.requestPermission else basis.requestPermission,
      sessionWarning = if (warningAccepted) incoming.sessionWarning else basis.sessionWarning,
      statusObservedAt = if (permissionAccepted || warningAccepted) incoming.collectionTime else basis.statusObservedAt,
      emptySuccess = explicitEmptyAccepted || (previous?.emptySuccess == true && !numericAccepted),
      refreshState = if (numericAccepted || explicitEmptyAccepted || coverageAccepted) UsageRefreshState.Complete else basis.refreshState,
      refreshFailure = if (numericAccepted || explicitEmptyAccepted || coverageAccepted) null else basis.refreshFailure,
      requestGeneration = maxOf(basis.requestGeneration ?: 0, incoming.requestGeneration ?: 0),
      sourceFreshnessBasis = if (numericAccepted || deletionAccepted || explicitEmptyAccepted) incoming.sourceFreshnessBasis else basis.sourceFreshnessBasis,
      coverage = nextCoverage,
    )
    observation = ranked(result)
    return observation
  }

  fun partial(
    result: UsageResult.Partial,
    requestGeneration: Long,
    baseRevision: Long = revision,
    sourceFreshnessBasis: Duration? = null,
  ): UsageObservation? {
    val previous = observation ?: emptyFor(result, requestGeneration)
    if (previous.subject != UsageObservationSubject.Account(result.subject) || previous.source.provider != result.source.provider) return null
    if (result.fields.keys.any(String::isBlank)) return null
    if (result.fields.any { (bucket, field) -> field is PartialUsageField.Present &&
        (field.value.bucketIdentity != bucket || field.value.sourceOwner?.let { it != result.source } == true) }) return null
    val nextRevision = ++revision
    val meters = previous.meters.associateBy { FieldKey(it.sourceOwner ?: previous.source, it.bucketIdentity) }.toMutableMap()
    var acceptedNumeric = false
    result.fields.forEach { (bucket, field) ->
      val key = FieldKey(result.source, bucket)
      when (field) {
        PartialUsageField.Absent -> Unit
        is PartialUsageField.Cleared -> if (accept(key, result.sourceObservationTime, field.observedAt, baseRevision)) {
          meters.remove(key)
          fields[key] = Stamp(nextRevision, result.sourceObservationTime, field.observedAt)
          acceptedNumeric = true
        }
        is PartialUsageField.Present -> if (accept(key, result.sourceObservationTime, field.value.observedAt, baseRevision)) {
          meters[key] = field.value.copy(sourceOwner = result.source)
          fields[key] = Stamp(nextRevision, result.sourceObservationTime, field.value.observedAt)
          acceptedNumeric = true
        }
      }
    }
    val permissionAccepted = result.requestPermission != null &&
      acceptStatus(permissionStamp, result.sourceObservationTime, result.collectionTime, baseRevision)
    val warningAccepted = result.sessionWarning != null &&
      acceptStatus(warningStamp, result.sourceObservationTime, result.collectionTime, baseRevision)
    if (permissionAccepted) permissionStamp = Stamp(nextRevision, result.sourceObservationTime, result.collectionTime)
    if (warningAccepted) warningStamp = Stamp(nextRevision, result.sourceObservationTime, result.collectionTime)
    if (acceptedNumeric) sourceStamps[result.source] = Stamp(nextRevision, result.sourceObservationTime, result.collectionTime)
    val coverageAccepted = coverageIsNewer(result.source, result.sourceObservationTime, result.collectionTime, baseRevision)
    if (acceptedNumeric && coverageAccepted) {
      coverageStamp = CoverageStamp(result.source, Stamp(nextRevision, result.sourceObservationTime, result.collectionTime))
    }
    val list = meters.entries.sortedWith(compareBy({ it.key.source.provider.name }, { it.key.source.capability.name }, { it.key.bucket })).map { it.value }
    val next = previous.copy(
      collectionTime = if (acceptedNumeric) maxOf(previous.collectionTime, result.collectionTime) else previous.collectionTime,
      sourceObservationTime = if (acceptedNumeric) result.sourceObservationTime else previous.sourceObservationTime,
      source = result.source,
      meters = list,
      requestPermission = if (permissionAccepted) result.requestPermission ?: previous.requestPermission else previous.requestPermission,
      sessionWarning = if (warningAccepted) result.sessionWarning else previous.sessionWarning,
      statusObservedAt = if (permissionAccepted || warningAccepted) result.collectionTime else previous.statusObservedAt,
      emptySuccess = if (acceptedNumeric) false else previous.emptySuccess,
      refreshState = if (acceptedNumeric) UsageRefreshState.Complete else previous.refreshState,
      refreshFailure = if (acceptedNumeric) null else previous.refreshFailure,
      requestGeneration = maxOf(previous.requestGeneration ?: 0, requestGeneration),
      sourceFreshnessBasis = if (acceptedNumeric) sourceFreshnessBasis else previous.sourceFreshnessBasis,
      coverage = if (acceptedNumeric && coverageAccepted) UsageCoverage.Partial else previous.coverage,
    )
    observation = ranked(next)
    return observation
  }

  fun failed(reason: dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason): UsageObservation? = observation?.let {
    it.copy(refreshState = UsageRefreshState.Failed, refreshFailure = reason).also { updated -> observation = updated }
  }

  fun currentRevision(): Long = revision

  private fun accept(key: FieldKey, sourceTime: Instant?, fieldTime: Instant, baseRevision: Long): Boolean {
    val existing = fields[key] ?: replacements[key.source]
    return existing?.let { stamp -> newer(stamp, sourceTime, fieldTime, baseRevision) } ?: true
  }

  private fun acceptStatus(stamp: Stamp?, sourceTime: Instant?, fieldTime: Instant, baseRevision: Long): Boolean =
    stamp?.let { newer(it, sourceTime, fieldTime, baseRevision) } ?: true

  private fun coverageIsNewer(source: UsageSource, sourceTime: Instant?, fieldTime: Instant, baseRevision: Long): Boolean =
    coverageStamp?.let { existing ->
      if (existing.source == source) newer(existing.stamp, sourceTime, fieldTime, baseRevision)
      else existing.stamp.revision <= baseRevision
    } ?: true

  private fun newer(stamp: Stamp?, sourceTime: Instant?, fieldTime: Instant, baseRevision: Long): Boolean {
    if (stamp == null) return true
    if (stamp.revision > baseRevision || fieldTime < stamp.fieldTime) return false
    if (sourceTime != null && stamp.sourceTime != null && sourceTime < stamp.sourceTime) return false
    return fieldTime > stamp.fieldTime || sourceTime != null && sourceTime > (stamp.sourceTime ?: sourceTime)
  }

  private fun emptyFor(result: UsageResult.Partial, generation: Long) = UsageObservation(
    subject = UsageObservationSubject.Account(result.subject),
    collectionTime = result.collectionTime,
    sourceObservationTime = null,
    source = result.source,
    freshness = dev.skillbill.desktop.core.domain.usage.model.UsageFreshness.Unknown,
    refreshState = UsageRefreshState.Idle,
    meters = emptyList(),
    requestPermission = RequestPermission.Unknown,
    coverage = UsageCoverage.Unknown,
    requestGeneration = generation,
  )

  private fun validMeters(meters: List<UsageMeter>, source: UsageSource): Boolean =
    meters.none { it.bucketIdentity.isBlank() || it.sourceOwner != null && it.sourceOwner != source } &&
      meters.map(UsageMeter::bucketIdentity).distinct().size == meters.size

  private fun ranked(observation: UsageObservation): UsageObservation {
    val rank = UsageMeterRanking.rank(observation.meters, null)
    return observation.copy(cardCandidates = rank.cardCandidates, popupMeters = rank.popupMeters)
  }
}
