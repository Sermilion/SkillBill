package skillbill.ports.taskruntime.model

import skillbill.workflow.taskruntime.artifact.FeatureTaskRuntimeRunEvidenceAddress

const val IMPLEMENTATION_CHECKLIST_STORE_ROOT: String = ".skill-bill/feature-task-tracking"

fun implementationChecklistDirectory(workflowId: String): String =
  "$IMPLEMENTATION_CHECKLIST_STORE_ROOT/${FeatureTaskRuntimeRunEvidenceAddress.pathSegment(workflowId)}"

fun implementationChecklistRelativePath(workflowId: String): String =
  "${implementationChecklistDirectory(workflowId)}/checklist.md"

data class ImplementationChecklistTask(
  val key: String,
  val title: String,
) {
  init {
    require(key.isNotBlank())
    require(title.isNotBlank())
  }
}

data class ImplementationChecklistSeed(
  val workflowId: String,
  val planDigest: String,
  val tasks: List<ImplementationChecklistTask>,
) {
  init {
    require(workflowId.isNotBlank())
    require(planDigest.isNotBlank())
  }
}

data class ImplementationChecklistAddress(
  val relativePath: String,
) {
  init {
    require(relativePath.isNotBlank())
  }
}

sealed interface ImplementationChecklistPrepareResult {
  data class Ready(
    val address: ImplementationChecklistAddress,
  ) : ImplementationChecklistPrepareResult

  data class Degraded(
    val address: ImplementationChecklistAddress,
    val reason: String,
  ) : ImplementationChecklistPrepareResult
}
