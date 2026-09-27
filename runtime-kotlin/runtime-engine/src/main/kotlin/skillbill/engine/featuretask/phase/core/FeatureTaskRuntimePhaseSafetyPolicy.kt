package skillbill.engine.featuretask.phase.core

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.checkpoint.isRuntimePrivatePath
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition

internal data class FeatureTaskRuntimePhaseFileManifest(
  val before: List<String>,
  val after: List<String>,
) {
  val introduced: List<String> = (after.toSet() - before.toSet()).sorted()
}

object FeatureTaskRuntimePhaseSafetyPolicy {
  fun lineSeparatedPaths(raw: String): List<String> =
    raw
      .lineSequence()
      .map(String::trim)
      .filter(String::isNotEmpty)
      .distinct()
      .sorted()
      .toList()

  fun changedPaths(status: String): List<String> =
    porcelainEntries(status)
      .mapNotNull(PorcelainEntry::retainedPath)
      .filterNot(::isRuntimePrivatePath)
      .distinct()
      .sorted()

  fun deletedPaths(status: String): List<String> =
    porcelainEntries(status)
      .mapNotNull(PorcelainEntry::removedPath)
      .filterNot(::isRuntimePrivatePath)
      .distinct()
      .sorted()

  internal fun dispositionForTerminalOutput(
    output: FeatureTaskRuntimeWorkflowArtifactMap,
    blockedDisposition: FeatureTaskRuntimeFailureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
  ): FeatureTaskRuntimeFailureDisposition {
    val explicit =
      (output[SharedPayloadKeys.FAILURE_DISPOSITION] as? String)
        ?.let(FeatureTaskRuntimeFailureDisposition::fromWireValue)
    if (explicit != null) return explicit
    return if ((output[SharedPayloadKeys.STATUS] as? String).workflowStepStatus() == WorkflowStepStatus.FAILED) {
      FeatureTaskRuntimeFailureDisposition.RETRYABLE
    } else {
      blockedDisposition
    }
  }

  private fun porcelainEntries(status: String): List<PorcelainEntry> =
    status
      .lineSequence()
      .map(String::trimEnd)
      .mapNotNull(::porcelainEntry)
      .toList()

  private fun porcelainEntry(line: String): PorcelainEntry? {
    if (line.length < PORCELAIN_PATH_OFFSET) return null
    val code = line.take(PORCELAIN_STATUS_WIDTH)
    val payload = line.substring(PORCELAIN_PATH_OFFSET).trim().trim('"')
    if (payload.isBlank()) return null
    return when {
      code == UNTRACKED || code == IGNORED -> PorcelainEntry(retainedPath = payload, removedPath = null)
      RENAME_SEPARATOR in payload -> {
        val source = payload.substringBeforeLast(RENAME_SEPARATOR).trim().trim('"')
        val destination = payload.substringAfterLast(RENAME_SEPARATOR).trim().trim('"')
        PorcelainEntry(
          retainedPath = destination.takeIf(String::isNotBlank),
          removedPath = source.takeIf(String::isNotBlank),
        )
      }
      code[0] == DELETE_STATUS || code[1] == DELETE_STATUS ->
        PorcelainEntry(retainedPath = null, removedPath = payload)
      else -> PorcelainEntry(retainedPath = payload, removedPath = null)
    }
  }

  private data class PorcelainEntry(
    val retainedPath: String?,
    val removedPath: String?,
  )

  private const val PORCELAIN_PATH_OFFSET: Int = 3
  private const val PORCELAIN_STATUS_WIDTH: Int = 2
  private const val DELETE_STATUS: Char = 'D'
  private const val UNTRACKED: String = "??"
  private const val IGNORED: String = "!!"
  private const val RENAME_SEPARATOR: String = " -> "
}
