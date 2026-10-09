package skillbill.workflow.engine.model

import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowContinueStatus
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import java.time.Instant

data class WorkflowStepState(
  val stepId: String,
  val status: WorkflowStepStatus,
  val attemptCount: Int,
  val planWorkflowId: String? = null,
)

data class WorkflowUpdateInput(
  val workflowStatus: WorkflowStatus,
  val currentStepId: String,
  val stepUpdates: WorkflowStepUpdates?,
  val artifactsPatch: WorkflowArtifactPatch?,
  val sessionId: String,
  val replaceArtifacts: Boolean = false,
  val terminalInstant: Instant? = null,
)

data class WorkflowStateSnapshot(
  val workflowId: String,
  val sessionId: String,
  val workflowName: String,
  val contractVersion: String,
  val workflowStatus: WorkflowStatus,
  val currentStepId: String,
  val steps: List<WorkflowStepState>,
  val artifacts: DurableWorkflowArtifacts,
  val startedAt: Instant?,
  val updatedAt: Instant?,
  val finishedAt: Instant?,
  val mode: FeatureTaskWorkflowMode? = null,
)

data class WorkflowContinueDecision(
  val view: WorkflowContinueView,
  val shouldReopen: Boolean,
  val resumeStepId: String,
  val nextAttemptCount: Int,
)

data class WorkflowContinueDecisionOverrides(
  val continueStatus: WorkflowContinueStatus? = null,
  val workflowStatusBeforeContinue: WorkflowStatus? = null,
  val repositoryCheckpointIdentity: String = "",
)

data class ResolvedRequiredArtifact(
  val present: Boolean,
  val value: Any?,
)

fun interface RequiredArtifactPresenceResolver {
  fun missingRequiredArtifacts(
    snapshot: WorkflowSnapshotView,
    resumeStepId: String,
    requiredArtifacts: List<String>,
  ): List<String>

  fun resolveRequiredArtifact(
    snapshot: WorkflowSnapshotView,
    artifactKey: String,
  ): ResolvedRequiredArtifact =
    ResolvedRequiredArtifact(
      present = snapshot.artifacts.containsKey(artifactKey),
      value = snapshot.artifacts[artifactKey],
    )

  companion object {
    val DEFAULT: RequiredArtifactPresenceResolver =
      object : RequiredArtifactPresenceResolver {
        override fun missingRequiredArtifacts(
          snapshot: WorkflowSnapshotView,
          resumeStepId: String,
          requiredArtifacts: List<String>,
        ): List<String> = requiredArtifacts.filterNot(snapshot.artifacts::containsKey)
      }
  }
}

data class WorkflowDefinition(
  val skillName: String,
  val workflowName: String,
  val workflowIdPrefix: String,
  val defaultSessionPrefix: String,
  val contractVersion: String,
  val workflowStatuses: Set<String>,
  val stepStatuses: Set<String>,
  val terminalStatuses: Set<String>,
  val workflowStatusEnums: Set<WorkflowStatus>,
  val stepStatusEnums: Set<WorkflowStepStatus>,
  val terminalStatusEnums: Set<WorkflowStatus>,
  val defaultInitialStepId: String,
  val stepIds: List<String>,
  val stepLabels: Map<String, String>,
  val requiredArtifactsByStep: Map<String, List<String>>,
  val resumeActions: Map<String, String>,
  val continuationReferenceSections: Map<String, List<String>>,
  val continuationDirectives: Map<String, String>,
  val continuationArtifactOrder: List<String>,
  val openPriorStepsCompleted: Boolean,
  val completedTerminalSummaryArtifact: String,
  val workflowMode: String? = null,
  val usesFeatureTaskRuntimeContinuation: Boolean = false,
  val inputProjectionsByStep: Map<String, WorkflowInputProjectionDeclaration> = emptyMap(),
  val requiredArtifactPresenceResolver: RequiredArtifactPresenceResolver =
    RequiredArtifactPresenceResolver.DEFAULT,
)

fun WorkflowDefinition.isTerminalStatus(status: WorkflowStatus): Boolean = status in terminalStatusEnums

fun WorkflowDefinition.isTerminalStatus(status: String): Boolean {
  val decoded = WorkflowStatus.fromWire(status)
  return if (decoded == null) {
    status in terminalStatuses
  } else {
    decoded in terminalStatusEnums
  }
}

data class WorkflowInputProjectionDeclaration(
  val requiredArtifactKeys: List<String>,
  val projectedFieldsByArtifactKey: Map<String, Set<String>> = emptyMap(),
  val forbiddenArtifactKeys: Set<String>,
  val maxUtf8Bytes: Int,
  val maxCollectionItems: Int,
  val repositoryCheckpointArtifactKey: String,
)

data class WorkflowInputProjection(
  val stepId: String,
  val producerIteration: Int,
  val repositoryCheckpoint: Any?,
  val artifacts: WorkflowLaunchProjectionArtifacts,
  val utf8Bytes: Int,
)
