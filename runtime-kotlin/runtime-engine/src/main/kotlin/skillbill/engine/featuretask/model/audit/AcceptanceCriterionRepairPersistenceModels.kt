package skillbill.engine.featuretask.model.audit

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.ports.db.model.OwningTransactionResult
import skillbill.review.spec.model.InteractionScenario
import skillbill.workflow.model.repair.FocusedRepairStrategy
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.interaction.AcceptanceCriterionInteractionTraceSettlement
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationAttemptStatus

data class AcceptanceCriterionRepairBinding(
  val catalogId: String,
  val executionId: String,
  val specDigest: String,
  val authoredCriteria: List<String>,
  val auditStepIds: Set<String> = emptySet(),
  val interactionScenarios: List<InteractionScenario> = emptyList(),
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
  val repairChangedPaths: List<String> = emptyList(),
)

sealed interface AcceptanceCriterionRepairMutation {
  data class Discover(
    val admission: AcceptanceCriterionRepairAdmission,
  ) : AcceptanceCriterionRepairMutation

  data class ProseAudit(val audit: AcceptanceCriterionProseAudit) : AcceptanceCriterionRepairMutation

  data object KeepProgress : AcceptanceCriterionRepairMutation

  data class QaReaudit(val criterionRefs: List<String>) : AcceptanceCriterionRepairMutation

  data class Clarification(val question: String) : AcceptanceCriterionRepairMutation

  data class Reserve(val reservationId: String) : AcceptanceCriterionRepairMutation

  data class RepairCompleted(
    val reservationId: String,
    val evidenceRef: String,
    val treeDigest: String,
    val checkpointId: String,
  ) : AcceptanceCriterionRepairMutation

  data class Outcome(val outcome: AcceptanceCriterionRepairOutcome) : AcceptanceCriterionRepairMutation

  data class InteractionTrace(
    val settlement: AcceptanceCriterionInteractionTraceSettlement,
  ) : AcceptanceCriterionRepairMutation

  data object PrepareInteractionTrace : AcceptanceCriterionRepairMutation
}

enum class AcceptanceCriterionRepairRefusal(val wireValue: String) {
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

internal fun OwningTransactionResult.Refusal.toRepairRefusal(): AcceptanceCriterionRepairRefusal =
  when (this) {
    OwningTransactionResult.Refusal.ACTIVE_SESSION -> AcceptanceCriterionRepairRefusal.ACTIVE_SESSION
    OwningTransactionResult.Refusal.UNSUPPORTED -> AcceptanceCriterionRepairRefusal.OWNING_SESSION_UNAVAILABLE
  }

sealed interface AcceptanceCriterionRepairVersionPlan {
  data object NotSuperseded : AcceptanceCriterionRepairVersionPlan

  data class Unrecoverable(val family: String, val version: String) : AcceptanceCriterionRepairVersionPlan

  class Recovery(
    val families: List<String>,
    val regenerated: Map<String, Any?>,
    val rejectedRecord: ByteArray,
    val detail: String,
  ) : AcceptanceCriterionRepairVersionPlan
}

sealed interface AcceptanceCriterionRepairVersionRecoveryResult {
  data object Unchanged : AcceptanceCriterionRepairVersionRecoveryResult

  data class Recovered(val families: List<String>) : AcceptanceCriterionRepairVersionRecoveryResult

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairVersionRecoveryResult
}

sealed interface AcceptanceCriterionRepairHydration {
  data class Present(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairHydration

  data object FreshDiscoveryRequired : AcceptanceCriterionRepairHydration

  data object LegacyAdoptionRequired : AcceptanceCriterionRepairHydration

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

  fun historicalAttributionUnavailable(): Boolean =
    accounting == AcceptanceCriterionRepairHydration.HistoricalAttributionUnavailable ||
      accounting == AcceptanceCriterionRepairHydration.LegacyAdoptionRequired

  fun freshDiscoveryRequired(): Boolean = accounting == AcceptanceCriterionRepairHydration.FreshDiscoveryRequired

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
      val recent = failures.takeLast(FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES)
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

  val currentCriterionContext: String?
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
      val remaining = (FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES - failures).coerceAtLeast(0)
      val authoredQueue =
        state.criteria
          .filter { it.canonicalRef in state.pendingAuditRefs || it.canonicalRef in state.unresolvedQueue }
          .sortedBy { it.ordinal }
      val queuePosition = authoredQueue.indexOf(criterion).takeIf { it >= 0 }?.plus(1)
      val gap = criterion.evidenceRef.takeUnless { it == state.specDigest } ?: "Not assessed."
      return "mode=${state.mode.wireValue}; stage=${state.stage.wireValue}; criterion $identity; " +
        "retained failures: $failures of ${FocusedRepairStrategy.MAX_UNSUCCESSFUL_OUTCOMES}; " +
        "remaining failures: $remaining; queue position: ${queuePosition ?: "unavailable"} of ${authoredQueue.size}; " +
        "live cycle: ${reserved ?: "none"}; " +
        "authored criterion: ${criterion.originalText}; latest gap: $gap" +
        state.pendingClarificationQuestion?.let { "; read-only clarification: $it" }.orEmpty()
    }
}

sealed interface AcceptanceCriterionRepairStatusRead {
  data class Current(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairStatusRead

  /** Unversioned state from before the serial repair contract; status shows it, resume refuses it. */
  data object PreContract : AcceptanceCriterionRepairStatusRead

  data class SupersededVersion(val version: String) : AcceptanceCriterionRepairStatusRead
}

sealed interface AcceptanceCriterionRepairWriteResult {
  data class Committed(
    val state: AcceptanceCriterionRepairState,
    val replayed: Boolean,
    val interactionTraceSkipped: Boolean = false,
    val newAnyChangeRechecks: List<String> = emptyList(),
    val droppedRechecks: List<String> = emptyList(),
  ) : AcceptanceCriterionRepairWriteResult

  data class CommittedWithProjectionFailure(
    val state: AcceptanceCriterionRepairState,
  ) : AcceptanceCriterionRepairWriteResult

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairWriteResult
}

data class AcceptanceCriterionRepairAuditEvidence(val payload: FeatureTaskRuntimeWorkflowArtifactMap)

sealed interface AcceptanceCriterionRepairAdoptionResult {
  data class Adopted(val receipt: FeatureTaskRuntimeWorkflowArtifactMap) : AcceptanceCriterionRepairAdoptionResult

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairAdoptionResult
}

data class AcceptanceCriterionRepairLegacyProof(
  val treeDigest: String,
  val checkpointId: String,
  val stoppedGeneration: Long = 0,
)
