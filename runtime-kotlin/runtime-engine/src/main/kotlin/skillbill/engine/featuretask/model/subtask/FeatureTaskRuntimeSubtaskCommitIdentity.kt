package skillbill.engine.featuretask.model.subtask

import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeCheckpointRefName

internal const val SUBTASK_TRAILER_KEY = "Skill-Bill-Subtask"

data class FeatureTaskRuntimeSubtaskCommitIdentity(val issueKey: String, val subtaskId: String) {
  init {
    require(issueKey.isNotBlank()) { "FeatureTaskRuntimeSubtaskCommitIdentity.issueKey must be non-blank." }
    require(subtaskId.isNotBlank()) { "FeatureTaskRuntimeSubtaskCommitIdentity.subtaskId must be non-blank." }
  }

  val trailer: String get() = "$SUBTASK_TRAILER_KEY: $issueKey/$subtaskId"

  fun checkpointRefName(sequenceNumber: Int): String =
    featureTaskRuntimeCheckpointRefName(issueKey, subtaskId, sequenceNumber)

  fun matches(commitMessage: String): Boolean = parse(commitMessage) == this

  companion object {
    fun parse(commitMessage: String): FeatureTaskRuntimeSubtaskCommitIdentity? =
      commitMessage.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("$SUBTASK_TRAILER_KEY:") }
        .mapNotNull { line -> identityFrom(line.removePrefix("$SUBTASK_TRAILER_KEY:").trim()) }
        .lastOrNull()

    private fun identityFrom(value: String): FeatureTaskRuntimeSubtaskCommitIdentity? {
      val segments = value.split('/')
      if (segments.size != 2) return null
      val (issueKey, subtaskId) = segments
      if (issueKey.isBlank() || subtaskId.isBlank()) return null
      return FeatureTaskRuntimeSubtaskCommitIdentity(issueKey, subtaskId)
    }
  }
}
