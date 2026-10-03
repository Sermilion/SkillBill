package skillbill.workflow.taskruntime.phase.task

import skillbill.contracts.workflow.WORKFLOW_STATE_CONTRACT_VERSION
import skillbill.workflow.engine.model.FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds

internal object FeatureTaskRuntimePhaseWorkflowGraph {
  val definition: WorkflowDefinition =
    WorkflowDefinition(
      skillName = "bill-feature-task",
      workflowName = "bill-feature-task",
      workflowIdPrefix = "wftr",
      defaultSessionPrefix = "ftr",
      contractVersion = WORKFLOW_STATE_CONTRACT_VERSION,
      workflowStatuses =
        setOf(
          WorkflowStatus.PENDING.wireValue,
          WorkflowStatus.RUNNING.wireValue,
          WorkflowStatus.COMPLETED.wireValue,
          WorkflowStatus.FAILED.wireValue,
          WorkflowStatus.ABANDONED.wireValue,
          WorkflowStatus.BLOCKED.wireValue,
          WorkflowStatus.PAUSED.wireValue,
        ),
      stepStatuses = WorkflowStepStatus.entries.map(WorkflowStepStatus::wireValue).toSet(),
      terminalStatuses =
        setOf(
          WorkflowStatus.COMPLETED.wireValue,
          WorkflowStatus.FAILED.wireValue,
          WorkflowStatus.ABANDONED.wireValue,
        ),
      workflowStatusEnums =
        setOf(
          WorkflowStatus.PENDING,
          WorkflowStatus.RUNNING,
          WorkflowStatus.COMPLETED,
          WorkflowStatus.FAILED,
          WorkflowStatus.ABANDONED,
          WorkflowStatus.BLOCKED,
          WorkflowStatus.PAUSED,
        ),
      stepStatusEnums = WorkflowStepStatus.entries.toSet(),
      terminalStatusEnums =
        setOf(
          WorkflowStatus.COMPLETED,
          WorkflowStatus.FAILED,
          WorkflowStatus.ABANDONED,
        ),
      defaultInitialStepId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      stepIds = FeatureTaskRuntimePhaseIds.all,
      stepLabels =
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to "Phase 1: Pre-plan",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to "Phase 2: Plan",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to "Phase 3: Implement",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to "Phase 3b: Simplify",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to "Phase 4a: Completeness Audit",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to "Phase 4b: Plan Fix",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX to "Phase 4c: Implement Fix",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to "Phase 5: Code Review",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to "Phase 5a: Verify Findings",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to "Phase 5b: Implement Fix",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to "Phase 5c: Build",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to "Phase 6: Quality Validation",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to "Phase 7: Boundary History",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to "Phase 8: Commit and Push",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to "Phase 9: Pull Request",
        ),
      requiredArtifactsByStep =
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to emptyList(),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
            listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
            ),
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
            ),
        ),
      resumeActions =
        mapOf(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN to
            "Re-run the preplan phase from the run-invariants, then persist the validated planning prose output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN to
            "Resume planning from the latest preplan prose, then persist the validated planning prose output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
            "Resume implementation from the planned work and current repository, then persist the validated output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY to
            "Resume simplification from the current subtask scoped diff and owned paths, reconciling the " +
            "working tree without replaying completed edits, then persist the validated output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX to
            "Resume the implement-fix phase from the latest verified findings, reconciling the " +
            "current tree, then persist the validated output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_PLAN_FIX to
            "Plan each latest audit gap against the current tree without editing, then persist the repair plan.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX to
            "Execute the saved audit repair plan against the current tree, then return to audit.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to
            "Inspect every planned criterion against current code and tests without editing files. " +
            "Report remaining criteria for the implementation repair step.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW to
            "Resume code review over its repository scope after audit completes.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VERIFY_FINDINGS to
            "Resume finding verification from the latest review output and in-flight dispositions " +
            "without re-running review.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD to
            "Resume compile/build proof from the plan after audit completes.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE to
            "Resume quality validation from the plan after audit completes.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY to
            "Resume boundary history writing from the latest implement and settled build or " +
            "validate output.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH to
            "Resume commit/push after verifying implement, the settled quality gate, " +
            "and write_history outputs are current.",
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR to
            "Resume PR creation from the latest implement output, commit output, and derived " +
            "diff context.",
        ),
      continuationReferenceSections = emptyMap(),
      continuationDirectives = emptyMap(),
      continuationArtifactOrder = emptyList(),
      openPriorStepsCompleted = false,
      completedTerminalSummaryArtifact = FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY,
      usesFeatureTaskRuntimeContinuation = true,
      workflowMode = "runtime",
      requiredArtifactPresenceResolver = FeatureTaskRuntimeRequiredArtifactPresenceResolver,
    )
}
