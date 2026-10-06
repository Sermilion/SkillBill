package skillbill.engine.featuretask.runloop.finalization

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.loadManifestOrNull
import skillbill.application.decomposition.resolvedParentSpecPath
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.workflow.decomposition.intentFor
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.decomposition.withParentStatus
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.io.IOException
import java.nio.file.Path

interface CommitPushManifestCompletion {
  fun markCompleteBeforeFinalCommit(
    request: FeatureTaskRuntimeRunFacts,
    lastResumableStep: String,
  ): String?
}

@Inject
class DecompositionManifestCommitCompletion(
  private val writer: DecompositionManifestWriter,
  private val validator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestStore,
) : CommitPushManifestCompletion {
  override fun markCompleteBeforeFinalCommit(
    request: FeatureTaskRuntimeRunFacts,
    lastResumableStep: String,
  ): String? {
    val continuation = request.goalContinuation?.takeIf { it.suppressPr } ?: return null
    return try {
      projectComplete(request, continuation, lastResumableStep)
    } catch (error: SkillBillRuntimeException) {
      error.message?.takeIf(String::isNotBlank)
        ?: "Goal commit could not mark the decomposition manifest complete before the final commit."
    } catch (error: IOException) {
      error.message?.takeIf(String::isNotBlank)
        ?: "Goal commit could not mark the decomposition manifest complete before the final commit."
    }
  }

  private fun projectComplete(
    request: FeatureTaskRuntimeRunFacts,
    continuation: FeatureTaskRuntimeGoalContinuationContext,
    lastResumableStep: String,
  ): String? {
    val manifestPath =
      resolvedParentSpecPath(request.repoRoot, Path.of(request.runInvariants.specReference))
        .parent
        ?.resolve(DECOMPOSITION_MANIFEST_FILENAME)
        ?: return "Goal commit could not resolve a decomposition manifest beside " +
          "'${request.runInvariants.specReference}'."
    val existing =
      loadManifestOrNull(manifestPath, validator, fileStore)
        ?: return "Goal commit could not load decomposition manifest '$manifestPath' before the final commit."
    if (existing.subtasks.none { it.id == continuation.subtaskId }) {
      return "Goal subtask '${continuation.subtaskId}' is missing from the decomposition manifest."
    }
    val completed = existing.completedForCommit(continuation.subtaskId, request.workflowId, lastResumableStep)
    val artifacts = LinkedHashMap<String, Any?>()
    DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.putInto(
      artifacts,
      LinkedHashMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          validator.encodeManifestWireMap(
            completed,
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label(),
          ),
        ),
      ),
    )
    return when (
      val outcome =
        writer.writeProjectionFromWorkflowState(
          request.repoRoot,
          DurableWorkflowArtifacts.fromMap(artifacts),
          validator,
          fileStore,
        )
    ) {
      is DecompositionManifestProjectionOutcome.Written -> null
      DecompositionManifestProjectionOutcome.Absent ->
        "Goal commit could not mark the decomposition manifest complete before the final commit."
      is DecompositionManifestProjectionOutcome.Failed ->
        "Goal commit could not mark the decomposition manifest complete before the final commit " +
          "(${outcome.operation} at ${outcome.targetPath})."
    }
  }
}

private fun DecompositionManifest.completedForCommit(
  subtaskId: Int,
  workflowId: String,
  lastResumableStep: String,
): DecompositionManifest {
  val status = DecompositionStatus.COMPLETE.wireValue
  return copy(
    subtasks =
      subtasks.map { subtask ->
        if (subtask.id != subtaskId) {
          subtask
        } else {
          subtask.copy(
            status = status,
            workflowId = workflowId,
            blockedReason = null,
            lastResumableStep = lastResumableStep,
          )
        }
      },
    currentSubtaskIntent = intentFor(subtaskId, status),
  ).withParentStatus()
}
