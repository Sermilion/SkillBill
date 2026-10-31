package skillbill.engine.goalrunner.model

import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQaSelection

public data class GoalRunnerChildExecutionPlanAdmission(
  public val workflowId: String,
  public val expected: ValidatedFeatureTaskRuntimeExecutionPlan,
)

public data class GoalRunnerChildWorkflowSetup(
  public val subtaskId: Int,
  public val workflowId: String,
  public val goalBranch: String,
  public val normalizedIssueKey: String,
  public val repositoryIdentity: String,
  public val governedSpecPath: String,
  public val reviewBaseline: GoalSubtaskReviewBaseline,
  public val reviewPolicy: GoalRunnerReviewPolicy,
  internal val planningHydration: GoalChildPlanningHydrationRequest? = null,
  public val executionPlan: ValidatedFeatureTaskRuntimeExecutionPlan? = null,
  public val qaSelection: FeatureTaskRuntimeQaSelection? = null,
  internal val operatorResumePhaseId: String? = null,
  internal val operatorResumeReason: String? = null,
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

public data class GoalChildPlanningHydrationRequest(
  public val identity: GoalPlanningIdentity,
  public val provenance: GoalPlanningContractProvenance,
  public val descriptor: GovernedGoalSubtaskDescriptor,
  public val planWorkflowId: String? = null,
)

public data class GoalChildPlanningHydrationResult(
  public val currentStepId: String,
  public val stepUpdates: WorkflowStepUpdates,
  public val artifacts: WorkflowArtifactPatch,
)
