package skillbill.engine.featuretask.model.audit

import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairDependency
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap

data class AcceptanceCriterionRepairBinding(
  val catalogId: String,
  val executionId: String,
  val specDigest: String,
  val authoredCriteria: List<String>,
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
)

sealed interface AcceptanceCriterionRepairMutation {
  data class Discover(val admission: AcceptanceCriterionRepairAdmission) : AcceptanceCriterionRepairMutation

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
  AUDIT_BINDING_CHANGED("audit_binding_changed"),
  UNSAFE_LEGACY_ADOPTION("unsafe_legacy_adoption"),
  PRIOR_OWNER_STOP_NOT_PROVEN("prior_owner_stop_not_proven"),
  UNPROVABLE_HISTORICAL_TUPLE("unprovable_historical_tuple"),
  CAPACITY_EXHAUSTED("capacity_exhausted"),
  ACTIVE_SESSION("active_session"),
  HISTORICAL_EVIDENCE_UNPROVABLE("historical_evidence_unprovable"),
  HISTORICAL_SOURCE_UNPROVABLE("historical_source_unprovable"),
  HISTORICAL_SOURCE_CHANGED("historical_source_changed"),
  HISTORICAL_TREE_UNPROVABLE("historical_tree_unprovable"),
  HISTORICAL_TREE_CHANGED("historical_tree_changed"),
}

sealed interface AcceptanceCriterionRepairHydration {
  data class Present(val state: AcceptanceCriterionRepairState) : AcceptanceCriterionRepairHydration

  data object FreshDiscoveryRequired : AcceptanceCriterionRepairHydration

  data object LegacyAdoptionRequired : AcceptanceCriterionRepairHydration
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

sealed interface AcceptanceCriterionRepairAdoptionResult {
  data class Adopted(val receipt: FeatureTaskRuntimeWorkflowArtifactMap) : AcceptanceCriterionRepairAdoptionResult

  data class Refused(val reason: AcceptanceCriterionRepairRefusal) : AcceptanceCriterionRepairAdoptionResult
}

data class AcceptanceCriterionRepairLegacyProof(
  val treeDigest: String,
  val checkpointId: String,
  val stoppedGeneration: Long = 0,
)

data class AcceptanceCriterionRepairStandaloneSourceProof(
  val repositoryIdentity: String,
  val normalizedIssueKey: String,
  val specPath: String,
  val checkpointCommit: String,
  val sourceText: String,
)
