package skillbill.workflow.taskruntime.model.core

import skillbill.contracts.review.ReviewVerificationSignalKeys

enum class FeatureTaskRuntimeRepositoryCheckpointPolicy(val wireValue: String) {
  NOT_REQUIRED("not_required"),
  MUST_MATCH("must_match"),
  REFRESH_FROM_REPOSITORY("refresh_from_repository"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeRepositoryCheckpointPolicy =
      entries.firstOrNull { it.wireValue == value }
        ?: unrecognizedHandoffWireValue("repository checkpoint policy", value)
  }
}

data class FeatureTaskRuntimeRepositoryCheckpoint(
  val fingerprint: String,
  val baseRef: String? = null,
  val headRef: String? = null,
  val workingTreeOwnedPaths: List<String> = emptyList(),
) {
  init {
    val reason = violation(fingerprint, workingTreeOwnedPaths)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    internal fun violation(fingerprint: String, workingTreeOwnedPaths: List<String>): String? =
      when {
        fingerprint.isBlank() ->
          "FeatureTaskRuntimeRepositoryCheckpoint.fingerprint must be non-blank; an unidentified checkpoint " +
            "cannot satisfy must_match or refresh_from_repository."
        fingerprint.length > MAX_REPOSITORY_FINGERPRINT_LENGTH ->
          "FeatureTaskRuntimeRepositoryCheckpoint.fingerprint allows at most " +
            "$MAX_REPOSITORY_FINGERPRINT_LENGTH characters, had ${fingerprint.length}."
        workingTreeOwnedPaths.any(String::isBlank) ->
          "FeatureTaskRuntimeRepositoryCheckpoint.workingTreeOwnedPaths must not contain blank entries."
        else -> null
      }
  }

  internal fun toEnvelopeMap(): Map<String, Any?> =
    linkedMapOf<String, Any?>(ReviewVerificationSignalKeys.REPOSITORY_CHECKPOINT_FINGERPRINT to fingerprint).apply {
      baseRef?.let { put("base_ref", it) }
      headRef?.let { put("head_ref", it) }
      if (workingTreeOwnedPaths.isNotEmpty()) put("working_tree_owned_paths", workingTreeOwnedPaths)
    }
}
