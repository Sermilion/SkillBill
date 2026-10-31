package skillbill.engine.featuretask.model.audit

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus

public data class AcceptanceCriterionRepairBinding(
  public val catalogId: String,
  public val executionId: String,
  public val specDigest: String,
  public val authoredCriteria: List<String>,
  internal val auditStepIds: Set<String> = emptySet(),
  public val interactionScenarios: List<InteractionScenario> = emptyList(),
)

public data class AcceptanceCriterionRepairStepRoles(
  public val audit: String,
  public val planning: String,
  public val repair: String,
  internal val repairLoopId: String,
)

public data class ExpectedAcceptanceCriterionRepairState(
  public val revision: Long?,
  public val currentCriterionRef: String?,
  public val reservationId: String?,
)

public data class AcceptanceCriterionRepairCommit(
  public val expected: ExpectedAcceptanceCriterionRepairState,
  public val mutation: AcceptanceCriterionRepairMutation,
  public val phase: FeatureTaskRuntimePhaseStateRequest,
  public val evidence: AcceptanceCriterionRepairAuditEvidence?,
  internal val attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
  internal val nextSteps: Map<AcceptanceCriterionRepairStage, String> = emptyMap(),
  internal val additionalPhaseId: String? = null,
  internal val additionalAttemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
  internal val repairChangedPaths: List<String> = emptyList(),
)

public sealed interface AcceptanceCriterionRepairMutation {
  public data class Discover(
    public val admission: AcceptanceCriterionRepairAdmission,
  ) : AcceptanceCriterionRepairMutation

  public data class ProseAudit(val audit: AcceptanceCriterionProseAudit) : AcceptanceCriterionRepairMutation

  public data object KeepProgress : AcceptanceCriterionRepairMutation

  public data class QaReaudit(val criterionRefs: List<String>) : AcceptanceCriterionRepairMutation

  public data class Clarification(val question: String) : AcceptanceCriterionRepairMutation

  public data class Reserve(val reservationId: String) : AcceptanceCriterionRepairMutation

  public data class RepairCompleted(
    val reservationId: String,
    val evidenceRef: String,
    val treeDigest: String,
    val checkpointId: String,
  ) : AcceptanceCriterionRepairMutation

  public data class Outcome(val outcome: AcceptanceCriterionRepairOutcome) : AcceptanceCriterionRepairMutation

  public data class InteractionTrace(
    val settlement: AcceptanceCriterionInteractionTraceSettlement,
  ) : AcceptanceCriterionRepairMutation

  public data object PrepareInteractionTrace : AcceptanceCriterionRepairMutation
}

public enum class AcceptanceCriterionRepairRefusal(public val wireValue: String) {
  OWNER_CHANGED("owner_changed"),
  EXPECTED_STATE_CHANGED("expected_state_changed"),
  IDENTITY_CHANGED("identity_changed"),
  SPEC_SOURCE_CHANGED("spec_source_changed"),
  INVALID_TRANSITION("invalid_transition"),
  AUDIT_BINDING_CHANGED("audit_binding_changed"),
  UNSAFE_LEGACY_ADOPTION("unsafe_legacy_adoption"),
  CAPACITY_EXHAUSTED("capacity_exhausted"),
  ACTIVE_SESSION("active_session"),
  HISTORICAL_EVIDENCE_UNPROVABLE("historical_evidence_unprovable"),
  OWNING_SESSION_UNAVAILABLE("owning_session_unavailable"),
  SUPERSEDED_VERSION_CHANGED("superseded_version_changed"),
}

public fun OwningTransactionResult.Refusal.toRepairRefusal(): AcceptanceCriterionRepairRefusal =
  when (this) {
    OwningTransactionResult.Refusal.ACTIVE_SESSION -> AcceptanceCriterionRepairRefusal.ACTIVE_SESSION
    OwningTransactionResult.Refusal.UNSUPPORTED -> AcceptanceCriterionRepairRefusal.OWNING_SESSION_UNAVAILABLE
  }

internal sealed interface AcceptanceCriterionRepairVersionPlan {
  data object NotSuperseded : AcceptanceCriterionRepairVersionPlan

  data class Unrecoverable(val family: String, val version: String) : AcceptanceCriterionRepairVersionPlan

  class Recovery(
    val families: List<String>,
    val regenerated: Map<String, Any?>,
    val rejectedRecord: ByteArray,
    val detail: String,
  ) : AcceptanceCriterionRepairVersionPlan
}

public sealed interface AcceptanceCriterionRepairVersionRecoveryResult {
  public data object Unchanged : AcceptanceCriterionRepairVersionRecoveryResult

  public data class Recovered(
    val families: List<String>,
  ) : AcceptanceCriterionRepairVersionRecoveryResult

  public data class Refused(
    val reason: AcceptanceCriterionRepairRefusal,
  ) : AcceptanceCriterionRepairVersionRecoveryResult
}

public sealed interface AcceptanceCriterionRepairHydration {
  public data class Present(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairHydration

  public data object FreshDiscoveryRequired : AcceptanceCriterionRepairHydration

  public data object LegacyAdoptionRequired : AcceptanceCriterionRepairHydration

  public data object HistoricalAttributionUnavailable : AcceptanceCriterionRepairHydration
}

internal data class AcceptanceCriterionRepairInvocation(
  val phaseId: String,
  val attempt: Int,
  val accounting: AcceptanceCriterionRepairState,
)

public class AcceptanceCriterionRepairRunState internal constructor(
  hydration: AcceptanceCriterionRepairHydration,
  internal val binding: AcceptanceCriterionRepairBinding? = null,
) {
  internal var steps: AcceptanceCriterionRepairStepRoles? = null
    private set

  internal fun bindSteps(roles: AcceptanceCriterionRepairStepRoles) {
    steps = roles
  }

  @Volatile private var accounting = hydration

  @Volatile internal var traceReaskScope: String? = null
    private set

  internal fun recordTraceReask(key: String) {
    traceReaskScope = key
  }

  @Volatile internal var invocation: AcceptanceCriterionRepairInvocation? = null
    private set

  internal val snapshot: AcceptanceCriterionRepairState?
    get() = (accounting as? AcceptanceCriterionRepairHydration.Present)?.state

  internal val auditsNoChangeClaim: Boolean
    get() = snapshot?.mode == null || snapshot?.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY

  internal fun historicalAttributionUnavailable(): Boolean =
    accounting == AcceptanceCriterionRepairHydration.HistoricalAttributionUnavailable ||
      accounting == AcceptanceCriterionRepairHydration.LegacyAdoptionRequired

  internal fun freshDiscoveryRequired(): Boolean =
    accounting == AcceptanceCriterionRepairHydration.FreshDiscoveryRequired

  internal fun acknowledgeCommitted(state: AcceptanceCriterionRepairState) {
    accounting = AcceptanceCriterionRepairHydration.Present(state)
  }

  internal fun acknowledgeStarted(phase: FeatureTaskRuntimePhaseStateRequest) {
    invocation = snapshot?.let { AcceptanceCriterionRepairInvocation(phase.phaseId, phase.attemptCount, it) }
  }

  internal val priorRepairFailures: String?
    get() {
      val state = snapshot ?: return null
      val current = state.currentCriterionRef ?: return null
      val failures = state.outcomes.filter { it.criterionRef == current && !it.successful }
      if (failures.isEmpty()) return null
      val recent = failures.takeLast(AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES)
      return buildString {
        appendLine("Earlier unsuccessful repair evidence for $current")
        appendLine(
          "Showing the latest ${recent.size} of ${failures.size} unsuccessful outcomes for this criterion, " +
            "including outcomes retained across operator resume. The complete history remains in durable accounting.",
        )
        appendLine(
          "Use these reports as counterexamples to check against current source. They do not establish that " +
            "a gap is still open, and they do not reopen another criterion. Preserve fixes that already hold.",
        )
        recent.forEach { outcome ->
          appendLine()
          appendLine("Outcome ${outcome.outcomeId}, ${outcome.kind.wireValue}:")
          appendLine(outcome.evidenceRef)
        }
      }.trimEnd()
    }

  internal val currentCriterionContext: String?
    get() {
      val state = snapshot ?: return null
      val current = state.currentCriterionRef
      if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
        return "Initial full audit of the authored acceptance criteria against current source." +
          state.pendingClarificationQuestion?.let { " Read-only clarification: $it" }.orEmpty()
      }
      if (current == null) {
        val outcome =
          when (state.stage) {
            AcceptanceCriterionRepairStage.ACCEPTED ->
              "Every acceptance criterion is accepted. Tests, build, and project-quality work remain in their later " +
                "phases."
            AcceptanceCriterionRepairStage.INTERACTION_TRACE_REQUIRED ->
              "Every acceptance criterion is accepted. Trace the pending interaction scenarios across them against " +
                "current source."
            else -> "Audit the full authored acceptance set against current source."
          }
        return outcome + state.pendingClarificationQuestion?.let { " Read-only clarification: $it" }.orEmpty()
      }
      val criterion = state.criteria.firstOrNull { it.canonicalRef == current } ?: return null
      val identity = criterion.originalAlias?.let { "$it ($current)" } ?: current
      val failures = state.unsuccessfulTotals.getOrDefault(current, 0)
      val reserved = state.reservation?.takeIf { it.criterionRef == current }?.reservationId
      val remaining = (AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES - failures).coerceAtLeast(0)
      val authoredQueue =
        state.criteria
          .filter { it.canonicalRef in state.pendingAuditRefs || it.canonicalRef in state.unresolvedQueue }
          .sortedBy { it.ordinal }
      val queuePosition = authoredQueue.indexOf(criterion).takeIf { it >= 0 }?.plus(1)
      val gap = criterion.evidenceRef.takeUnless { it == state.specDigest } ?: "Not assessed."
      return "mode=${state.mode.wireValue}; stage=${state.stage.wireValue}; criterion $identity; " +
        "retained failures: $failures of ${AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES}; " +
        "remaining failures: $remaining; queue position: ${queuePosition ?: "unavailable"} of ${authoredQueue.size}; " +
        "live cycle: ${reserved ?: "none"}; " +
        "authored criterion: ${criterion.originalText}; latest gap: $gap" +
        state.pendingClarificationQuestion?.let { "; read-only clarification: $it" }.orEmpty()
    }
}

public sealed interface AcceptanceCriterionRepairStatusRead {
  public data class Current(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairStatusRead

  /** Unversioned state from before the serial repair contract; status shows it, resume refuses it. */
  public data object PreContract : AcceptanceCriterionRepairStatusRead

  public data class SupersededVersion(val version: String) : AcceptanceCriterionRepairStatusRead
}

public sealed interface AcceptanceCriterionRepairWriteResult {
  public data class Committed(
    public val state: AcceptanceCriterionRepairState,
    public val replayed: Boolean,
    internal val interactionTraceSkipped: Boolean = false,
    internal val newAnyChangeRechecks: List<String> = emptyList(),
    internal val droppedRechecks: List<String> = emptyList(),
  ) : AcceptanceCriterionRepairWriteResult

  public data class CommittedWithProjectionFailure(
    public val state: AcceptanceCriterionRepairState,
  ) : AcceptanceCriterionRepairWriteResult

  public data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairWriteResult
}

public data class AcceptanceCriterionRepairAuditEvidence(val payload: FeatureTaskRuntimeWorkflowArtifactMap)

public sealed interface AcceptanceCriterionRepairAdoptionResult {
  public data class Adopted(
    val receipt: FeatureTaskRuntimeWorkflowArtifactMap,
  ) : AcceptanceCriterionRepairAdoptionResult

  public data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairAdoptionResult
}

public data class AcceptanceCriterionRepairLegacyProof(
  public val treeDigest: String,
  public val checkpointId: String,
  internal val stoppedGeneration: Long = 0,
)
