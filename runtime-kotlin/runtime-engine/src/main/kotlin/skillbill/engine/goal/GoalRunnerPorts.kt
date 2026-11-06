package skillbill.engine.goal

import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.StandalonePlanReport
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import java.nio.file.Path

public fun interface GoalNewWorkAdmissionPort {
  public fun admit(
    intake: String,
    repoRoot: Path,
  ): GoalNewWorkAdmission
}

public sealed interface GoalNewWorkAdmission {
  public data class Admitted(public val issueKey: String) : GoalNewWorkAdmission

  public data object NoIssueKey : GoalNewWorkAdmission

  public data class Refused(public val issueKey: String?, public val reason: String) : GoalNewWorkAdmission
}

public interface GoalRunnerStatusPort {
  public fun status(request: GoalRunnerStatusRequest): GoalRunnerStatusProjection?

  public fun statusForWorkflow(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
  ): GoalRunnerStatusProjection?

  public fun reconcileSubtaskPage(
    parentWorkflowId: String,
    request: GoalRunnerStatusRequest,
    subtasks: List<DecompositionSubtask>,
  ): List<DecompositionSubtask>

  public fun pause(
    issueKey: String,
    repoRoot: Path? = null,
    expectedParentWorkflowId: String? = null,
  ): GoalRunnerPauseResult

  public fun stop(
    issueKey: String,
    repoRoot: Path? = null,
    expectedParentWorkflowId: String? = null,
    databaseFileObservation: DatabaseFileObservation? = null,
    beforeAdmission: (() -> Boolean)? = null,
  ): GoalRunnerStopVerbResult

  public fun resume(
    issueKey: String,
    repoRoot: Path? = null,
  ): GoalRunnerResumeResult
}

public interface GoalPreflightPort {
  public fun preflight(request: GoalPreflightRequest): GoalPreflightResult
}

public fun interface GoalIssueStartedReader {
  public fun hasStarted(
    issueKey: String,
    repoRoot: Path,
  ): Boolean
}

public fun interface GoalManifestProjectionReader {
  public fun read(
    issueKey: String,
    repoRoot: Path,
  ): GoalRunnerManifestState?
}

public interface StandalonePlanReportPort {
  public fun report(
    manifest: DecompositionManifest,
    repoRoot: Path,
  ): StandalonePlanReport

  public fun seededFeatureName(
    issueKey: String,
    repoRoot: Path,
  ): String?

  public fun detailReport(
    issueKey: String,
    repoRoot: Path,
    savedSubtaskIds: Set<Int>,
  ): StandalonePlanReport?
}

public interface GoalBlockRecoveryPort {
  public fun artifacts(
    manifest: DecompositionManifest,
    existing: DurableWorkflowArtifacts,
  ): WorkflowArtifactPatch

  public fun reopenBlockedPhaseForOperatorResume(
    unitOfWork: UnitOfWork,
    request: OperatorBlockedPhaseReopenRequest,
    expectedIdentity: FeatureTaskExecutionIdentity,
    expectedExecutionPlan: ValidatedFeatureTaskRuntimeExecutionPlan,
  ): Boolean
}
