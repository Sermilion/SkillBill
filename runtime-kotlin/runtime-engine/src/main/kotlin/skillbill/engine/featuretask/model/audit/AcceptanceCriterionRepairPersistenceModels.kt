package skillbill.engine.featuretask.model.audit

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus

data class AcceptanceCriterionRepairBinding(
  val catalogId: String,
  val executionId: String,
  val specDigest: String,
  val authoredCriteria: List<String>,
  val auditStepIds: Set<String> = emptySet(),
)

data class AcceptanceCriterionRepairStepRoles(
  val audit: String,
  val planning: String,
  val repair: String,
  val repairLoopId: String,
)

data class ExpectedAcceptanceCriterionRepairState(
  val revision: Long?,
  val currentCriterionRef: String?,
  val reservationId: String?,
)

data class AcceptanceCriterionRepairCommit(
  val expected: ExpectedAcceptanceCriterionRepairState,
  val mutation: AcceptanceCriterionRepairMutation,
  val phase: FeatureTaskRuntimePhaseStateRequest,
  val evidence: AcceptanceCriterionRepairAuditEvidence?,
  val attemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
  val nextSteps: Map<AcceptanceCriterionRepairStage, String> = emptyMap(),
  val additionalPhaseId: String? = null,
  val additionalAttemptStatus: FeatureTaskRuntimeImplementationAttemptStatus? = null,
)

sealed interface AcceptanceCriterionRepairMutation {
  data class Discover(
    val admission: AcceptanceCriterionRepairAdmission,
  ) : AcceptanceCriterionRepairMutation

  data class ProseAudit(val audit: AcceptanceCriterionProseAudit) : AcceptanceCriterionRepairMutation

  data object KeepProgress : AcceptanceCriterionRepairMutation

  data class Clarification(val question: String) : AcceptanceCriterionRepairMutation

  data object RequireFinalAudit : AcceptanceCriterionRepairMutation

  data class Reserve(val reservationId: String) : AcceptanceCriterionRepairMutation

  data class RepairCompleted(
    val reservationId: String,
    val evidenceRef: String,
    val treeDigest: String,
    val checkpointId: String,
  ) : AcceptanceCriterionRepairMutation

  data class Outcome(val outcome: AcceptanceCriterionRepairOutcome) : AcceptanceCriterionRepairMutation

  data class FinalAudit(
    val auditedRefs: Set<String>,
    val regressedRefs: Set<String>,
    val digest: String,
    val treeDigest: String,
    val checkpointId: String,
    val dependencies: List<AcceptanceCriterionRepairDependency>? = null,
  ) : AcceptanceCriterionRepairMutation
}

enum class AcceptanceCriterionRepairRefusal(val wireValue: String) {
  OWNER_CHANGED("owner_changed"),
  EXPECTED_STATE_CHANGED("expected_state_changed"),
  IDENTITY_CHANGED("identity_changed"),
  INVALID_TRANSITION("invalid_transition"),
  CAPACITY_EXHAUSTED("capacity_exhausted"),
  ACTIVE_SESSION("active_session"),
  HISTORICAL_EVIDENCE_UNPROVABLE("historical_evidence_unprovable"),
}

sealed interface AcceptanceCriterionRepairHydration {
  data class Present(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairHydration

  data object FreshDiscoveryRequired : AcceptanceCriterionRepairHydration

  data object HistoricalAttributionUnavailable : AcceptanceCriterionRepairHydration
}

internal data class AcceptanceCriterionRepairInvocation(
  val phaseId: String,
  val attempt: Int,
  val accounting: AcceptanceCriterionRepairState,
)

class AcceptanceCriterionRepairRunState internal constructor(
  hydration: AcceptanceCriterionRepairHydration,
  internal val binding: AcceptanceCriterionRepairBinding? = null,
) {
  internal var steps: AcceptanceCriterionRepairStepRoles? = null
    private set

  internal fun bindSteps(roles: AcceptanceCriterionRepairStepRoles) {
    steps = roles
  }

  @Volatile private var accounting = hydration

  @Volatile internal var invocation: AcceptanceCriterionRepairInvocation? = null
    private set

  internal val snapshot: AcceptanceCriterionRepairState?
    get() = (accounting as? AcceptanceCriterionRepairHydration.Present)?.state

  fun historicalAttributionUnavailable(): Boolean =
    accounting == AcceptanceCriterionRepairHydration.HistoricalAttributionUnavailable

  fun freshDiscoveryRequired(): Boolean = accounting == AcceptanceCriterionRepairHydration.FreshDiscoveryRequired

  internal fun acknowledgeCommitted(state: AcceptanceCriterionRepairState) {
    accounting = AcceptanceCriterionRepairHydration.Present(state)
  }

  internal fun acknowledgeStarted(phase: FeatureTaskRuntimePhaseStateRequest) {
    invocation = snapshot?.let { AcceptanceCriterionRepairInvocation(phase.phaseId, phase.attemptCount, it) }
  }

  val currentCriterionContext: String?
    get() {
      val state = (accounting as? AcceptanceCriterionRepairHydration.Present)?.state ?: return null
      val current = state.currentCriterionRef
      if (state.mode == AcceptanceCriterionRepairMode.INITIAL_DISCOVERY) {
        return "Initial full audit of the authored acceptance criteria against current source." +
          state.pendingClarificationQuestion?.let { " Read-only clarification: $it" }.orEmpty()
      }
      if (current == null) {
        return "Audit the full authored AC set against current source. Mode=${state.mode.wireValue}." +
          state.pendingClarificationQuestion?.let { " Read-only clarification: $it" }.orEmpty()
      }
      val criterion = state.criteria.firstOrNull { it.canonicalRef == current } ?: return null
      val failures = state.unsuccessfulTotals.getOrDefault(current, 0)
      val reserved = state.reservation?.takeIf { it.criterionRef == current }?.reservationId
      val remaining = (AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES - failures).coerceAtLeast(0)
      val gap = criterion.evidenceRef.takeUnless { it == state.specDigest } ?: "Not assessed."
      return "mode=${state.mode.wireValue}; stage=${state.stage.wireValue}; $current: ${criterion.originalText}; " +
        "retained failures: $failures of ${AcceptanceCriterionRepairState.MAX_UNSUCCESSFUL_REPAIR_OUTCOMES}; " +
        "remaining repairs: $remaining; live cycle: ${reserved ?: "none"}; " +
        "latest gap: $gap" + state.pendingClarificationQuestion?.let { "; read-only clarification: $it" }.orEmpty()
    }
}

sealed interface AcceptanceCriterionRepairWriteResult {
  data class Committed(
    val state: AcceptanceCriterionRepairState,
    val replayed: Boolean,
  ) : AcceptanceCriterionRepairWriteResult

  data class CommittedWithProjectionFailure(
    val state: AcceptanceCriterionRepairState,
  ) : AcceptanceCriterionRepairWriteResult

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairWriteResult
}

data class AcceptanceCriterionRepairAuditEvidence(val payload: FeatureTaskRuntimeWorkflowArtifactMap)
