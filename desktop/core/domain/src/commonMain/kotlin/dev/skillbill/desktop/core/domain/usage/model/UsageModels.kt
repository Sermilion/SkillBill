package dev.skillbill.desktop.core.domain.usage.model

import kotlin.time.Instant
import kotlin.time.Duration

enum class UsageProvider { Codex, Claude, Cursor, Junie }

data class UsageProfileLookup(
  val provider: UsageProvider,
  val configurationReference: String?,
  val generation: Long,
)

data class VerifiedUsageSubject(
  val profile: UsageProfileLookup,
  val identity: VerifiedUsageIdentity,
  val authMode: UsageAuthMode,
)

data class VerifiedUsageIdentity(
  val value: String,
  val workspace: VerifiedUsageWorkspaceScope? = null,
)

sealed interface VerifiedUsageWorkspaceScope {
  data class Workspace(val id: String) : VerifiedUsageWorkspaceScope
  data object VerifiedNonWorkspace : VerifiedUsageWorkspaceScope
}

enum class UsageAuthMode { Account, ApiKey, Organization }

data class SessionUsageScope(val logicalSessionId: String, val processGeneration: Long)

data class UsageRequest(
  val profile: UsageProfileLookup,
  val logicalSessionId: String? = null,
  val modelSelection: UsageModelSelection? = null,
)

data class UsageModelSelection(val name: String, val provenance: ModelSelectionProvenance)
enum class ModelSelectionProvenance { ActualExecution, Unknown }
sealed interface UsageModelApplicability {
  data object Unknown : UsageModelApplicability
  data class Verified(val model: String) : UsageModelApplicability
}

data class UsageDemand(
  val ownerId: String,
  val request: UsageRequest,
  val visible: Boolean,
  val active: Boolean,
)

data class UsageObservation(
  val subject: UsageObservationSubject,
  val collectionTime: Instant,
  val sourceObservationTime: Instant?,
  val source: UsageSource,
  val freshness: UsageFreshness,
  val refreshState: UsageRefreshState,
  val meters: List<UsageMeter>,
  val requestPermission: RequestPermission,
  val sessionWarning: Boolean? = null,
  val cardCandidates: List<UsageMeter> = emptyList(),
  val popupMeters: List<UsageMeter> = meters,
  val coverage: UsageCoverage = UsageCoverage.Unknown,
  val refreshFailure: UsageUnavailableReason? = null,
  val emptySuccess: Boolean = false,
  val statusObservedAt: Instant? = null,
  val requestGeneration: Long? = null,
  val sourceFreshnessBasis: Duration? = null,
)

enum class UsageCoverage { Complete, Partial, Empty, Unknown }

sealed interface UsageObservationSubject {
  data class Account(val value: VerifiedUsageSubject) : UsageObservationSubject
  data class Session(val value: SessionUsageScope) : UsageObservationSubject
}

data class UsageSource(val provider: UsageProvider, val capability: UsageCapability)

enum class UsageCapability { NumericSnapshot, SessionStatusOnly, Unsupported, AwaitingReader, Unavailable }
sealed interface UsageRefreshCapability {
  data object ProbeOnly : UsageRefreshCapability
  data object EventOnly : UsageRefreshCapability
  data object Unsupported : UsageRefreshCapability
  data class Pull(val family: UsageRefreshFamily, val minimumInterval: Duration = Duration.ZERO) : UsageRefreshCapability
}
enum class UsageRefreshFamily { CodexSnapshot, TeamSpend, AggregatedActivity }
sealed interface UsageRefreshAvailability {
  data object Ready : UsageRefreshAvailability
  data object Checking : UsageRefreshAvailability
  data class Deferred(val reason: UsageRefreshDeferredReason, val eligibleAt: Instant) : UsageRefreshAvailability
  data class Disabled(val reason: UsageUnavailableReason) : UsageRefreshAvailability
}
enum class UsageRefreshDeferredReason { Cooldown, SourceFloor, Retry }
enum class UsageFreshness { Current, Stale, Unknown }
enum class UsageRefreshState { Idle, Loading, Complete, Failed, Throttled }
enum class RequestPermission { Allowed, Rejected, Unknown }

data class UsageMeter private constructor(
  val bucketIdentity: String,
  val label: String,
  val scope: UsageMeterScope,
  val observedAt: Instant,
  val resetAt: Instant?,
  val period: UsagePeriod,
  val value: UsageValue,
  val modelApplicability: UsageModelApplicability = UsageModelApplicability.Unknown,
  val expired: Boolean = false,
  val comparableAllowanceGroup: String? = null,
  val sourceOwner: UsageSource? = null,
  val freshnessBasis: Duration? = null,
  val stale: Boolean = false,
) {
  companion object {
    fun create(
      bucketIdentity: String,
      label: String,
      scope: UsageMeterScope,
      observedAt: Instant,
      resetAt: Instant?,
      period: UsagePeriod,
      value: UsageValue,
      modelApplicability: UsageModelApplicability = UsageModelApplicability.Unknown,
      expired: Boolean = false,
      comparableAllowanceGroup: String? = null,
      sourceOwner: UsageSource? = null,
      freshnessBasis: Duration? = null,
      stale: Boolean = false,
    ): UsageMeter? =
      if (bucketIdentity.isBlank() || label.isBlank()) null
      else UsageMeter(bucketIdentity, label, scope, observedAt, resetAt, period, value, modelApplicability, expired, comparableAllowanceGroup, sourceOwner, freshnessBasis, stale)
  }
}

sealed interface UsageMeterScope {
  data object General : UsageMeterScope
  data class Model(val name: String) : UsageMeterScope
  data class Pool(val name: String) : UsageMeterScope
}

sealed interface UsagePeriod {
  data class Rolling private constructor(val durationSeconds: Long) : UsagePeriod {
    companion object {
      fun create(durationSeconds: Long): Rolling? = durationSeconds.takeIf { it > 0 }?.let(::Rolling)
    }
  }
  data class BillingBoundaries(val startsAt: Instant?, val endsAt: Instant?) : UsagePeriod
  data object Unknown : UsagePeriod
}

sealed interface UsageValue {
  class PercentageUsed private constructor(val percent: Double) : UsageValue {
    override fun equals(other: Any?) = other is PercentageUsed && percent == other.percent

    override fun hashCode() = percent.hashCode()

    companion object {
      fun create(percent: Double): PercentageUsed? =
        percent.takeIf { it.isFinite() && it in 0.0..100.0 }?.let(::PercentageUsed)
    }
  }

  class AmountUsed private constructor(val amount: DecimalQuantity, val limit: DecimalQuantity) : UsageValue {
    override fun equals(other: Any?) = other is AmountUsed && amount == other.amount && limit == other.limit

    override fun hashCode() = 31 * amount.hashCode() + limit.hashCode()

    val barFraction: Double?
      get() {
        val denominator = limit.asDouble()
        val fraction = amount.asDouble() / denominator
        return fraction.takeIf { amount.isNonNegative && denominator.isFinite() && denominator > 0.0 && it.isFinite() }
          ?.coerceIn(0.0, 1.0)
      }

    companion object {
      fun create(amount: DecimalQuantity, limit: DecimalQuantity): AmountUsed? =
        if (amount.unit == limit.unit && amount.isNonNegative && limit.isPositive) AmountUsed(amount, limit) else null
    }
  }

  data class Money private constructor(val amount: DecimalQuantity, val currency: String) : UsageValue {
    companion object {
      fun create(amount: DecimalQuantity, currency: String): Money? =
        if (currency.length == 3 && currency.all { it in 'A'..'Z' } && amount.unit == currency) Money(amount, currency) else null
    }
  }
  data class CreditBalance(val amount: DecimalQuantity) : UsageValue
  data object Unlimited : UsageValue
  data class Status(val label: String) : UsageValue
}

class DecimalQuantity private constructor(val unscaledDigits: String, val scale: Int, val unit: String) {
  val isPositive: Boolean get() = unscaledDigits.any { it in '1'..'9' } && !unscaledDigits.startsWith("-")
  val isNonNegative: Boolean get() = !unscaledDigits.startsWith("-")

  override fun equals(other: Any?) =
    other is DecimalQuantity && unscaledDigits == other.unscaledDigits && scale == other.scale && unit == other.unit

  override fun hashCode() = 31 * (31 * unscaledDigits.hashCode() + scale) + unit.hashCode()

  internal fun asDouble(): Double = unscaledDigits.toDouble() / tenTo(scale)

  companion object {
    fun create(unscaledDigits: String, scale: Int, unit: String): DecimalQuantity? {
      if (unscaledDigits.isEmpty() || scale !in 0..1000 || unit.isBlank()) return null
      val digits = unscaledDigits.removePrefix("-")
      if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
      return DecimalQuantity(unscaledDigits, scale, unit)
    }

    private fun tenTo(scale: Int): Double = (0 until scale).fold(1.0) { value, _ -> value * 10.0 }
  }
}

sealed interface UsageResult {
  data class Snapshot(
    val value: UsageObservation,
    val omission: OmissionSemantics,
    val reservationId: String? = null,
  ) : UsageResult
  data class Partial(
    val subject: VerifiedUsageSubject,
    val source: UsageSource,
    val collectionTime: Instant,
    val reservationId: String,
    val sourceObservationTime: Instant? = null,
    val fields: Map<String, PartialUsageField>,
    val requestPermission: RequestPermission? = null,
    val sessionWarning: Boolean? = null,
  ) : UsageResult
  data class EmptySuccess(
    val subject: VerifiedUsageSubject,
    val source: UsageSource,
    val collectionTime: Instant,
    val omission: OmissionSemantics,
    val reservationId: String,
    val sourceObservationTime: Instant? = null,
  ) : UsageResult
  data class Unavailable(val reason: UsageUnavailableReason) : UsageResult
  data class Unsupported(val reason: UsageUnavailableReason) : UsageResult
  data class SignInRequired(val reason: UsageUnavailableReason) : UsageResult
  data class TransientFailure(val reason: UsageUnavailableReason) : UsageResult
  data class Throttled(val reason: UsageUnavailableReason, val retryAt: VerifiedRetryInstant?) : UsageResult
}

enum class OmissionSemantics { OmittedFieldsAreUnchanged, OmittedFieldsAreRemoved, Unknown }

sealed interface PartialUsageField {
  data object Absent : PartialUsageField
  data class Cleared(val observedAt: Instant) : PartialUsageField
  data class Present(val value: UsageMeter) : PartialUsageField
}

data class VerifiedRetryInstant(val value: Instant, val verifiedBy: String)
data class UsageUnavailableReason(val code: UsageUnavailableCode, val detail: String? = null)
enum class UsageUnavailableCode { AwaitingCapabilityReader, UnsupportedPersonalSnapshot, SessionObservationUnavailable, ProfileUnverified, ProviderUnavailable, Throttled, TransientFailure }

sealed interface UsageRefreshResult {
  data class Started(val request: UsageRequest) : UsageRefreshResult
  data class Coalesced(val request: UsageRequest) : UsageRefreshResult
  data class NotDue(val eligibleAt: Instant) : UsageRefreshResult
  data class Deferred(val eligibleAt: Instant) : UsageRefreshResult
  data class Disabled(val reason: UsageUnavailableReason) : UsageRefreshResult
  data class Failed(val reason: UsageUnavailableReason) : UsageRefreshResult
}

sealed interface UsageDemandResult {
  data class Acquired(val demand: UsageDemand) : UsageDemandResult
  data object Released : UsageDemandResult
  data class Rejected(val reason: UsageUnavailableReason) : UsageDemandResult
}

sealed interface UsageSessionStatus {
  data class Observed(
    val scope: SessionUsageScope,
    val profile: UsageProfileLookup,
    val permission: RequestPermission,
    val warning: Boolean,
    val observedAt: Instant,
    val meters: List<UsageMeter> = emptyList(),
  ) : UsageSessionStatus

  data object Unavailable : UsageSessionStatus
}
