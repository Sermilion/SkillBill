package skillbill.workflow.taskruntime.model.blockincident

import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.RejectedOutputDiagnosticFailureCode
import skillbill.error.core.TelemetryHttpFailureCode
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.goalrunner.model.GoalRunnerStopReason
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal data class BlockIncidentClassificationRule(
  val category: BlockIncidentResponsibleCategory,
  val certainty: BlockIncidentCertainty,
  val prerequisite: BlockIncidentPrerequisiteKind?,
  val matches: (BlockIncidentOccurrence, List<BlockIncidentDiagnosisEvidence>) -> Boolean,
)

internal val BLOCK_INCIDENT_CLASSIFICATION_RULES: List<BlockIncidentClassificationRule> =
  listOf(
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.USER_ENVIRONMENT,
      BlockIncidentCertainty.DIAGNOSED,
      BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED,
    ) { occurrence, _ ->
      occurrence.failureCode in CONFIGURATION_FAILURE_CODES
    },
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.USER_ENVIRONMENT,
      BlockIncidentCertainty.DIAGNOSED,
      BlockIncidentPrerequisiteKind.CONFIGURATION_FIXED,
    ) { occurrence, _ ->
      occurrence.hasDisposition(FeatureTaskRuntimeFailureDisposition.NON_RETRYABLE_POLICY_CONFLICT) ||
        (occurrence.origin == BlockIncidentOrigin.ACCEPTED_GOAL_BLOCK && occurrence.failureCode in POLICY_FAILURE_CODES)
    },
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.EXTERNAL_SERVICE,
      BlockIncidentCertainty.DIAGNOSED,
      BlockIncidentPrerequisiteKind.EXTERNAL_SERVICE_AVAILABLE,
    ) { occurrence, _ -> occurrence.failureCode in EXTERNAL_SERVICE_FAILURE_CODES },
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.PROJECT_SOURCE,
      BlockIncidentCertainty.DIAGNOSED,
      BlockIncidentPrerequisiteKind.PROJECT_EDIT_COMPLETED,
    ) { occurrence, _ -> occurrence.failureCode in PROJECT_FAILURE_CODES },
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.SKILL_BILL_DEFECT,
      BlockIncidentCertainty.SUSPECTED,
      null,
    ) { occurrence, evidence ->
      val outputFailure =
        occurrence.hasDisposition(FeatureTaskRuntimeFailureDisposition.INVALID_OUTPUT) ||
          occurrence.hasDisposition(FeatureTaskRuntimeFailureDisposition.PROCESS_FAILURE)
      outputFailure &&
        (
          occurrence.failureCode in RUNTIME_DEFECT_FAILURE_CODES ||
            evidence.any { it.available && it.kind in OUTPUT_EVIDENCE_KINDS }
        )
    },
    BlockIncidentClassificationRule(
      BlockIncidentResponsibleCategory.USER_ENVIRONMENT,
      BlockIncidentCertainty.SUSPECTED,
      null,
    ) { occurrence, _ ->
      occurrence.failureCode != null &&
        occurrence.failureCode !in ATTEMPT_LIMIT_FAILURE_CODES &&
        occurrence.hasDisposition(FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION)
    },
  )

private fun BlockIncidentOccurrence.hasDisposition(disposition: FeatureTaskRuntimeFailureDisposition): Boolean =
  failureDisposition == disposition.wireValue

private fun Enum<*>.label(): String = "${declaringJavaClass.simpleName}.$name"

private val CONFIGURATION_FAILURE_CODES =
  setOf(
    InstallFailureCode.MALFORMED_REPO_LOCAL_CONFIG.label(),
    InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE.label(),
    TelemetryHttpFailureCode.RELAY_URL_UNCONFIGURED.label(),
    RejectedOutputDiagnosticFailureCode.INVALID_CONFIGURATION.label(),
    FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION.label(),
  )

private val POLICY_FAILURE_CODES =
  setOf(GoalRunnerStopReason.POLICY_BLOCKED.name, GoalRunnerStopReason.DEPENDENCIES_BLOCKED.name)

private val EXTERNAL_SERVICE_FAILURE_CODES =
  setOf(
    TelemetryHttpFailureCode.PROXY_REQUEST_FAILED.label(),
    TelemetryHttpFailureCode.PROXY_INVALID_RESPONSE.label(),
  )

private val PROJECT_FAILURE_CODES =
  setOf(
    AcceptanceCriterionRepairStage.BUDGET_BLOCKED.wireValue,
    FeatureTaskRuntimeFailureCode.PROJECT_QUALITY_GATE_FAILED.label(),
  )

private val RUNTIME_DEFECT_FAILURE_CODES =
  setOf(
    BlockIncidentFailureCode.INVALID_SCHEMA.label(),
    BlockIncidentFailureCode.INVALID_VERSION.label(),
    BlockIncidentFailureCode.IMMUTABLE_CONFLICT.label(),
    BlockIncidentFailureCode.PERSISTENCE.label(),
    BlockIncidentFailureCode.QUARANTINE.label(),
    BlockIncidentFailureCode.FALLBACK_SPOOL.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.MALFORMED.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.ROOT_NOT_OBJECT.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.DUPLICATE_KEY.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.NO_REPAIR_CANDIDATE.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.AMBIGUOUS_REPAIR.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.REPAIR_LIMIT_EXCEEDED.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.UNSUPPORTED_REPAIR.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.SCHEMA_INVALID.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.PHASE_ID_MISMATCH.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.SEMANTIC_INVALID.label(),
    FeatureTaskRuntimePhaseOutputFailureCode.MULTIPLE_OUTPUT_CANDIDATES.label(),
    FeatureTaskRuntimeFailureCode.INVALID_REPAIR_RECEIPT.label(),
    FeatureTaskRuntimeFailureCode.INVALID_FINDING_VERIFICATION_RECORD.label(),
    FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_CHECKPOINT_IDENTITY_VERSION.label(),
    FeatureTaskRuntimeFailureCode.INVALID_QUARANTINE_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_ATTEMPT_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_IMPLEMENTATION_PROGRESS_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_PHASE_HANDOFF_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_PERSISTENCE_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_PROJECTION_MEASUREMENT_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_SHARED_EVIDENCE_PROJECTION_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_BUILD_RECEIPT_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_EVIDENCE_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_READINESS_EVIDENCE_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_STATE_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_AUDIT_RESULT_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_HANDOFF_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_ACCEPTANCE_CRITERION_REPAIR_ADOPTION_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_EXECUTION_IDENTITY_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.INVALID_WORKER_OWNERSHIP_SCHEMA.label(),
    FeatureTaskRuntimeFailureCode.PHASE_SETTLEMENT_CONFLICT.label(),
    FeatureTaskRuntimeFailureCode.FEATURE_TASK_RUNTIME_CONTRACT_REJECTED.label(),
    FeatureTaskRuntimeFailureCode.MALFORMED_STEP_LAUNCH_ASSIGNMENT.label(),
    FeatureTaskRuntimeFailureCode.INCOHERENT_STEP_LAUNCH_ASSIGNMENTS.label(),
    FeatureTaskRuntimeFailureCode.UNSUPPORTED_EXECUTION_PLAN_VERSION.label(),
  )

// Attempt-budget and loop-cap blocks name the exhausted limit, not a fault in the user's environment.
private val ATTEMPT_LIMIT_FAILURE_CODES =
  setOf(
    FeatureTaskRuntimeFailureCode.PHASE_ATTEMPT_BUDGET_EXHAUSTED.label(),
    FeatureTaskRuntimeFailureCode.LOOP_EDGE_CAP_EXHAUSTED.label(),
  )

private val OUTPUT_EVIDENCE_KINDS =
  setOf(BlockIncidentDiagnosisEvidenceKind.REJECTED_OUTPUT, BlockIncidentDiagnosisEvidenceKind.PRODUCER_OUTPUT)
