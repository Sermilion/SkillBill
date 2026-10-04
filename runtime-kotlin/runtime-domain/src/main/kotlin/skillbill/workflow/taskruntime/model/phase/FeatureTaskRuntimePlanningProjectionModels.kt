
package skillbill.workflow.taskruntime.model.phase

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
import skillbill.text.sha256HexUtf8
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionField
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact

object FeatureTaskRuntimePlanningProjectionContract {
  const val SHARED_REVIEW_EVIDENCE_ID: String = "feature_task_runtime.shared_review_evidence"
  val VERSION: String = FEATURE_TASK_RUNTIME_PLANNING_PROJECTIONS_CONTRACT_VERSION
}

data class FeatureTaskRuntimeSharedReviewEvidenceReference(
  val storePath: String,
  val checkpointFingerprint: String,
  val baseRef: String?,
  val headRef: String?,
  val changedFileCount: Int,
  val changedHunkCount: Int,
  val fileHunkIndexDigest: String,
) {
  init {
    require(storePath.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.storePath must be non-blank; a reference that " +
        "cannot name its artifact is not dereferenceable."
    }
    require(checkpointFingerprint.isNotBlank()) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.checkpointFingerprint must be non-blank; the " +
        "fingerprint is the artifact's only reuse key."
    }
    require(changedFileCount >= 0 && changedHunkCount >= 0) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference index counts must be non-negative, had " +
        "files=$changedFileCount hunks=$changedHunkCount."
    }
    require(fileHunkIndexDigest.matches(FILE_HUNK_INDEX_DIGEST_PATTERN)) {
      "FeatureTaskRuntimeSharedReviewEvidenceReference.fileHunkIndexDigest must be a lowercase " +
        "SHA-256 hex digest, had '$fileHunkIndexDigest'."
    }
  }

  fun toProjectionFields(): List<FeatureTaskRuntimeHandoffProjectionField> =
    listOfNotNull(
      FeatureTaskRuntimeHandoffProjectionField(
        name = FIELD_STORE_PATH,
        value =
          FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
            value = storePath,
          ),
      ),
      FeatureTaskRuntimeHandoffProjectionField(
        name = FIELD_CHECKPOINT_FINGERPRINT,
        value =
          FeatureTaskRuntimeHandoffProjectionValue.CompactReference(
            kind = FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
            value = checkpointFingerprint,
          ),
      ),
      baseRef?.let {
        FeatureTaskRuntimeHandoffProjectionField(FIELD_BASE_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
      },
      headRef?.let {
        FeatureTaskRuntimeHandoffProjectionField(FIELD_HEAD_REF, FeatureTaskRuntimeHandoffProjectionValue.Text(it))
      },
      FeatureTaskRuntimeHandoffProjectionField(
        name = FIELD_CHANGED_FILE_COUNT,
        value = FeatureTaskRuntimeHandoffProjectionValue.Text(changedFileCount.toString()),
      ),
      FeatureTaskRuntimeHandoffProjectionField(
        name = FIELD_CHANGED_HUNK_COUNT,
        value = FeatureTaskRuntimeHandoffProjectionValue.Text(changedHunkCount.toString()),
      ),
      FeatureTaskRuntimeHandoffProjectionField(
        name = FIELD_FILE_HUNK_INDEX_DIGEST,
        value = FeatureTaskRuntimeHandoffProjectionValue.Text(fileHunkIndexDigest),
      ),
    )

  companion object {
    const val FIELD_STORE_PATH: String = "store_path"
    const val FIELD_CHECKPOINT_FINGERPRINT: String = "checkpoint_fingerprint"
    const val FIELD_BASE_REF: String = "base_ref"
    const val FIELD_HEAD_REF: String = "head_ref"
    const val FIELD_CHANGED_FILE_COUNT: String = "changed_file_count"
    const val FIELD_CHANGED_HUNK_COUNT: String = "changed_hunk_count"
    const val FIELD_FILE_HUNK_INDEX_DIGEST: String = "file_hunk_index_digest"

    private val FILE_HUNK_INDEX_DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")

    val DECLARED_FIELD_NAMES: List<String> =
      listOf(
        FIELD_STORE_PATH,
        FIELD_CHECKPOINT_FINGERPRINT,
        FIELD_BASE_REF,
        FIELD_HEAD_REF,
        FIELD_CHANGED_FILE_COUNT,
        FIELD_CHANGED_HUNK_COUNT,
        FIELD_FILE_HUNK_INDEX_DIGEST,
      )

    fun of(
      storePath: String,
      artifact: FeatureTaskRuntimeSharedEvidenceArtifact,
    ): FeatureTaskRuntimeSharedReviewEvidenceReference =
      FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = storePath,
        checkpointFingerprint = artifact.fingerprint,
        baseRef = artifact.baseRef?.takeIf(String::isNotBlank),
        headRef = artifact.headRef?.takeIf(String::isNotBlank),
        changedFileCount = artifact.files.size,
        changedHunkCount = artifact.hunks.size,
        fileHunkIndexDigest = fileHunkIndexDigest(artifact),
      )

    fun fileHunkIndexDigest(artifact: FeatureTaskRuntimeSharedEvidenceArtifact): String {
      val hunkCounts = artifact.hunks.groupingBy { it.path }.eachCount()
      val entries =
        artifact.files
          .map { file -> "${file.changeKind} ${file.path} hunks=${hunkCounts[file.path] ?: 0}" }
          .sorted()
      return sha256HexUtf8(entries.joinToString("") { entry -> entry + '\u0000' })
    }
  }
}
