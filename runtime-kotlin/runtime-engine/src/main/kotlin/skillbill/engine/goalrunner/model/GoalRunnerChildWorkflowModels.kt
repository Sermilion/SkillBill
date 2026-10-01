package skillbill.engine.goalrunner.model

import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates

data class GoalRunnerChildExecutionPlanAdmission(
  val workflowId: String,
  val expected: ValidatedFeatureTaskRuntimeExecutionPlan,
)

data class GoalRunnerChildWorkflowSetup(
  val subtaskId: Int,
  val workflowId: String,
  val goalBranch: String,
  val normalizedIssueKey: String,
  val repositoryIdentity: String,
  val governedSpecPath: String,
  val reviewBaseline: GoalSubtaskReviewBaseline,
  val reviewPolicy: GoalRunnerReviewPolicy,
  val planningHydration: GoalChildPlanningHydrationRequest? = null,
  val executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
  val operatorResumePhaseId: String? = null,
  val operatorResumeReason: String? = null,
) {
  init {
    require(subtaskId > 0) { "subtaskId must be positive." }
    require(workflowId.isNotBlank()) { "workflowId must not be blank." }
    require(goalBranch.isNotBlank()) { "goalBranch must not be blank." }
    require(normalizedIssueKey.isNotBlank()) { "normalizedIssueKey must not be blank." }
    require(repositoryIdentity.isNotBlank()) { "repositoryIdentity must not be blank." }
    require(governedSpecPath.isNotBlank()) { "governedSpecPath must not be blank." }
    require((operatorResumePhaseId == null) == (operatorResumeReason == null)) {
      "Operator resume phase and reason must be supplied together."
    }
  }
}

data class GoalChildPlanningHydrationRequest(
  val identity: GoalPlanningIdentity,
  val provenance: GoalPlanningContractProvenance,
  val descriptor: GovernedGoalSubtaskDescriptor,
)

data class GoalChildPlanningHydrationResult(
  val currentStepId: String,
  val stepUpdates: WorkflowStepUpdates,
  val artifacts: WorkflowArtifactPatch,
)
