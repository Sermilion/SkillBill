package skillbill.application.decomposition.model

import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionStackBranch
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import java.nio.file.Path

data class DecompositionManifestWriteRequest(
  val repoRoot: Path,
  val parentSpecPath: Path,
  val planningResult: DecompositionPlanningResult,
  val baseBranch: String,
  val featureBranch: String?,
  val executionModel: DecompositionExecutionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
  val stackBranches: List<DecompositionStackBranch> = emptyList(),
  val currentSubtaskId: Int? = null,
  val specSource: SpecSource = SpecSource.LOCAL,
)

data class DecompositionManifestRuntimeUpdate(
  val workflowId: String = "",
  val workflowStatus: String = "",
  val currentStepId: String = "",
  val planningResult: DecompositionPlanningResult? = null,
  val stepUpdates: WorkflowStepUpdates? = null,
  val artifactsPatch: WorkflowArtifactPatch? = null,
  val existingArtifacts: DurableWorkflowArtifacts = DurableWorkflowArtifacts.EMPTY,
)

data class DecompositionPlanManifestInput(
  val repoRoot: Path,
  val plan: DecompositionPlanningResult,
  val artifactsPatch: WorkflowArtifactPatch?,
  val existingArtifacts: DurableWorkflowArtifacts,
  val validator: DecompositionManifestValidator,
  val fileStore: DecompositionManifestStore,
)

data class DecompositionManifestWorkflowProjectionInput(
  val repoRoot: Path,
  val existingArtifacts: DurableWorkflowArtifacts,
  val validator: DecompositionManifestValidator,
  val planningResult: DecompositionPlanningResult? = null,
  val artifactsPatch: WorkflowArtifactPatch? = null,
  val runtimeUpdate: DecompositionManifestRuntimeUpdate = DecompositionManifestRuntimeUpdate(),
  val fileStore: DecompositionManifestStore,
)
