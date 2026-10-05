package skillbill.workflow.taskruntime.model.review

data class FeatureTaskRuntimeSharedEvidenceArtifact(
  val fingerprint: String,
  val baseRef: String?,
  val headRef: String?,
  val files: List<FeatureTaskRuntimeSharedEvidenceFileEntry>,
  val hunks: List<FeatureTaskRuntimeSharedEvidenceHunkEntry>,
  val diffPayload: FeatureTaskRuntimeSharedEvidenceDiffPayloadRef,
) {
  init {
    val reason = violation(fingerprint)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(fingerprint: String): String? =
      if (fingerprint.isNotBlank()) {
        null
      } else {
        "FeatureTaskRuntimeSharedEvidenceArtifact.fingerprint must be non-blank; " +
          "evidence that cannot name the checkpoint it was derived against can never be safely reused."
      }
  }
}

data class FeatureTaskRuntimeSharedEvidenceFileEntry(
  val path: String,
  val changeKind: String,
) {
  init {
    val reason = violation(path, changeKind)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(
      path: String,
      changeKind: String,
    ): String? =
      when {
        path.isBlank() -> "FeatureTaskRuntimeSharedEvidenceFileEntry.path must be non-blank."
        changeKind.isBlank() -> "FeatureTaskRuntimeSharedEvidenceFileEntry.changeKind must be non-blank."
        else -> null
      }
  }
}

data class FeatureTaskRuntimeSharedEvidenceHunkEntry(
  val path: String,
  val header: String,
) {
  init {
    val reason = violation(path, header)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(
      path: String,
      header: String,
    ): String? =
      when {
        path.isBlank() -> "FeatureTaskRuntimeSharedEvidenceHunkEntry.path must be non-blank."
        header.isBlank() -> "FeatureTaskRuntimeSharedEvidenceHunkEntry.header must be non-blank."
        else -> null
      }
  }
}

data class FeatureTaskRuntimeSharedEvidenceDiffPayloadRef(
  val relativePath: String,
  val sizeBytes: Long,
) {
  init {
    val reason = violation(relativePath, sizeBytes)
    require(reason == null) { reason.orEmpty() }
  }

  companion object {
    fun violation(
      relativePath: String,
      sizeBytes: Long,
    ): String? =
      when {
        relativePath.isBlank() -> "FeatureTaskRuntimeSharedEvidenceDiffPayloadRef.relativePath must be non-blank."
        sizeBytes < 0 ->
          "FeatureTaskRuntimeSharedEvidenceDiffPayloadRef.sizeBytes must not be negative, " +
            "was $sizeBytes."
        else -> null
      }
  }
}
