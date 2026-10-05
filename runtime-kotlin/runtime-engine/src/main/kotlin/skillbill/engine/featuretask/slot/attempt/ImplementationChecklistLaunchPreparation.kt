package skillbill.engine.featuretask.slot.attempt

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.ports.taskruntime.model.ImplementationChecklistSeed
import skillbill.ports.taskruntime.model.ImplementationChecklistTask
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

internal object ImplementationChecklistLaunchPreparation {
  fun prepare(
    context: PhaseAttemptLaunchPreparationContext,
    run: PhaseRun,
    continuation: FeatureTaskRuntimeImplementationContinuation?,
  ) {
    val workflowId = run.request.workflowId
    if (workflowId.isBlank()) return
    if (
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT &&
      run.phaseId != FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY
    ) {
      return
    }
    val store = context.implementationChecklistStore ?: return
    val artifact =
      context.recorder.loadPhaseRecords(workflowId)
        ?.get(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN)
        ?.outputArtifact
    val planDigest =
      if (artifact.isNullOrBlank()) {
        context.diagnostics.warning(
          "implementation checklist degraded: seam=implementation_checklist " +
            "value_expected=plan-record value_used=missing",
        )
        MISSING_PLAN_DIGEST
      } else {
        sha256HexUtf8(artifact)
      }
    store.prepare(
      run.request.repoRoot,
      ImplementationChecklistSeed(
        workflowId = workflowId,
        planDigest = planDigest,
        tasks = tasks(artifact, continuation),
      ),
    )
  }

  private fun tasks(
    artifact: String?,
    continuation: FeatureTaskRuntimeImplementationContinuation?,
  ): List<ImplementationChecklistTask> {
    val planTasks =
      planProse(artifact).lineSequence().mapNotNull(::planTaskTitle).mapIndexed { index, title ->
        ImplementationChecklistTask("t${index + 1}", title)
      }
    val continuationTasks =
      continuation?.priorValueSegments.orEmpty().mapIndexedNotNull { index, segment ->
        val title = continuationTitle(segment) ?: return@mapIndexedNotNull null
        ImplementationChecklistTask("c${index + 1}", title)
      }
    return planTasks.toList() + continuationTasks
  }

  private fun planProse(artifact: String?): String {
    if (artifact.isNullOrBlank()) return ""
    val parsed = JsonCodec.parseObjectOrNull(artifact) ?: return artifact
    return parsed[SharedPayloadKeys.VALUE]?.let { element ->
      (element as? JsonPrimitive)?.contentOrNull
    } ?: artifact
  }

  private fun planTaskTitle(line: String): String? {
    val match = TASK_BULLET.matchEntire(line.trim()) ?: return null
    return match.groupValues[1].trim().takeIf(String::isNotBlank)
  }

  private fun continuationTitle(segment: String): String? {
    val title = segment.lineSequence().firstOrNull(String::isNotBlank)?.trim() ?: return null
    return title.take(CONTINUATION_TITLE_LIMIT).takeIf(String::isNotBlank)
  }
}

private val TASK_BULLET: Regex = Regex("""^(?:[-*]|\d+\.)\s+(?:\[(?: |x|X)\]\s+)?(.+)$""")
private const val MISSING_PLAN_DIGEST: String = "no-plan-record"
private const val CONTINUATION_TITLE_LIMIT: Int = 120
