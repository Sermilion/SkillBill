package skillbill.workflow.taskruntime.phase.task

import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionTemplate
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration

object FeatureTaskRuntimePhaseWorkflowDefinition {
  const val PHASE_PREPLAN: String = FeatureTaskRuntimePhaseIds.PREPLAN
  const val PHASE_PLAN: String = FeatureTaskRuntimePhaseIds.PLAN
  const val PHASE_IMPLEMENT: String = FeatureTaskRuntimePhaseIds.IMPLEMENT
  const val PHASE_SIMPLIFY: String = FeatureTaskRuntimePhaseIds.SIMPLIFY
  const val PHASE_IMPLEMENT_FIX: String = FeatureTaskRuntimePhaseIds.IMPLEMENT_FIX
  const val PHASE_REVIEW: String = FeatureTaskRuntimePhaseIds.REVIEW
  const val PHASE_BUILD: String = FeatureTaskRuntimePhaseIds.BUILD
  const val PHASE_VERIFY_FINDINGS: String = FeatureTaskRuntimePhaseIds.VERIFY_FINDINGS
  const val PHASE_AUDIT_PLAN_FIX: String = FeatureTaskRuntimePhaseIds.AUDIT_PLAN_FIX
  const val PHASE_AUDIT_IMPLEMENT_FIX: String = FeatureTaskRuntimePhaseIds.AUDIT_IMPLEMENT_FIX
  const val PHASE_AUDIT: String = FeatureTaskRuntimePhaseIds.AUDIT
  const val PHASE_VALIDATE: String = FeatureTaskRuntimePhaseIds.VALIDATE
  const val PHASE_WRITE_HISTORY: String = FeatureTaskRuntimePhaseIds.WRITE_HISTORY
  const val PHASE_COMMIT_PUSH: String = FeatureTaskRuntimePhaseIds.COMMIT_PUSH
  const val PHASE_PR: String = FeatureTaskRuntimePhaseIds.PR

  const val DERIVED_CONTEXT_DIFF: String = "diff"
  const val DERIVED_CONTEXT_SCOPED_REPOSITORY_STATE: String = "scoped_repository_state"

  const val DERIVED_CONTEXT_PR_BRANCH_DIFF: String = "pr_branch_diff"

  const val REVIEW_FIX_LOOP_ID: String = "review_fix"

  const val AUDIT_REPAIR_LOOP_ID: String = "audit_repair"

  const val AUDIT_GAP_LOOP_ID: String = "audit_gap"

  const val SEMANTIC_LOOP_WARNING_THRESHOLD: Int = 3

  const val PREPLAN_REGENERATION_LOOP_ID: String = "regenerate_preplan"

  const val MAX_RECORD_REGENERATION_ATTEMPTS: Int = 2

  val REGENERATION_LOOP_ID_BY_PRODUCER: Map<String, String> =
    mapOf(
      PHASE_BUILD to "regenerate_build_receipt",
      PHASE_VALIDATE to "regenerate_validation_receipt",
    )

  val REGENERATION_PRODUCER_BY_CONSUMER: Map<String, Set<String>> =
    mapOf(PHASE_WRITE_HISTORY to REGENERATION_LOOP_ID_BY_PRODUCER.keys.toSet())

  val REGENERATION_LOOP_IDS: Set<String> = REGENERATION_LOOP_ID_BY_PRODUCER.values.toSet()

  fun isRegenerationLoopId(loopId: String): Boolean = loopId in REGENERATION_LOOP_IDS

  val definition: WorkflowDefinition = FeatureTaskRuntimePhaseWorkflowGraph.definition

  val agentSettledPhaseIds: Set<String> = definition.stepIds.toSet() - PHASE_COMMIT_PUSH

  const val UPSTREAM_PHASE_RECEIPT_CONTRACT_ID: String = "feature_task_runtime.upstream_phase_receipt"

  const val UPSTREAM_PHASE_RECEIPT_CONTRACT_VERSION: String = "0.1"

  object PhaseProjectionContract {
    const val VERSION: String = "0.1"
    const val PHASE_PROSE: String = "feature_task_runtime.phase_prose"
    const val REVIEW_CLEARANCE: String = "feature_task_runtime.review_clearance"
    const val REVIEW_REPAIR_REQUEST: String = "feature_task_runtime.review_repair_request"
    const val FINDINGS_VERIFICATION_INPUT: String = "feature_task_runtime.findings_verification_input"
    const val FINDINGS_VERIFICATION_DISPOSITIONS: String = "feature_task_runtime.findings_verification_dispositions"
    const val REPAIR_LEDGER: String = "feature_task_runtime.repair_ledger"
    const val REPAIR_PLAN: String = "feature_task_runtime.repair_plan"
    const val CHANGE_RECEIPT: String = "feature_task_runtime.change_receipt"
    const val VALIDATION_REQUEST: String = "feature_task_runtime.validation_request"
    const val VALIDATION_RECEIPT: String = "feature_task_runtime.validation_receipt"
    const val BUILD_RECEIPT: String = "feature_task_runtime.build_receipt"
    const val BOUNDARY_CANDIDATES: String = "feature_task_runtime.boundary_candidates"
    const val HISTORY_RECEIPT: String = "feature_task_runtime.history_receipt"
    const val HISTORY_RECEIPT_VERSION: String = "0.2"
    const val COMMIT_REQUEST: String = "feature_task_runtime.commit_request"
    const val COMMIT_RECEIPT: String = "feature_task_runtime.commit_receipt"
    const val PR_REQUEST: String = "feature_task_runtime.pr_request"
  }

  fun phaseProjection(template: PhaseHandoffProjectionTemplate): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseProjection(template)

  fun phaseProseDeclaration(
    consumerPhaseId: String,
    producingPhaseId: String = PHASE_PREPLAN,
    checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
      FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  ): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseProseDeclaration(
      consumerPhaseId,
      producingPhaseId,
      checkpointPolicy,
    )

  fun sharedReviewEvidenceDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.sharedReviewEvidenceDeclaration(consumerPhaseId)

  fun repairLedgerDeclaration(consumerPhaseId: String): PhaseHandoffProjectionDeclaration =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.repairLedgerDeclaration(consumerPhaseId)

  const val REPAIR_LEDGER_PROJECTION_NAME: String = "repair_ledger"

  const val SHARED_REVIEW_EVIDENCE_PROJECTION_NAME: String = "shared_review_evidence"

  fun runtimeProjectorProducerPhaseIds(consumerPhaseId: String): Set<String> =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.runtimeProjectorProducerPhaseIds(consumerPhaseId)

  val phaseDeclarations: Map<String, FeatureTaskRuntimePhaseDeclaration> =
    FeatureTaskRuntimePhaseWorkflowProjectionDeclarations.phaseDeclarations(definition)

  val transitions: FeatureTaskRuntimeTransitionDeclaration =
    FeatureTaskRuntimePhaseWorkflowTransitions.transitions(definition)
}
