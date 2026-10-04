package skillbill.workflow.taskruntime.model.feature

import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT
import skillbill.workflow.taskruntime.model.handoff.task.FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionValue
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceArtifact
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceDiffPayloadRef
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceFileEntry
import skillbill.workflow.taskruntime.model.review.FeatureTaskRuntimeSharedEvidenceHunkEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeSharedReviewEvidenceReferenceTest {
  @Test
  fun `declared field names are exactly the seven reference fields`() {
    assertEquals(
      listOf(
        "store_path",
        "checkpoint_fingerprint",
        "base_ref",
        "head_ref",
        "changed_file_count",
        "changed_hunk_count",
        "file_hunk_index_digest",
      ),
      FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES,
    )
  }

  @Test
  fun `every rendered field is inside the allowlist and none can carry diff text`() {
    val fields = reference().toProjectionFields()
    val allowlist = FeatureTaskRuntimeSharedReviewEvidenceReference.DECLARED_FIELD_NAMES
    assertEquals(allowlist, fields.map { it.name })
    fields.forEach { field ->
      assertTrue(field.name in allowlist, "${field.name} is outside the declared allowlist")
      assertTrue(field.name !in FEATURE_TASK_RUNTIME_FORBIDDEN_PROJECTION_FIELD_NAMES)
      assertTrue(
        field.value is FeatureTaskRuntimeHandoffProjectionValue.CompactReference ||
          field.value is FeatureTaskRuntimeHandoffProjectionValue.Text,
      )
    }
    val storePath = fields.single { it.name == "store_path" }.value
    assertEquals(
      FeatureTaskRuntimeCompactReferenceKind.PRIVATE_EVIDENCE_ARTIFACT,
      (storePath as FeatureTaskRuntimeHandoffProjectionValue.CompactReference).kind,
    )
    val fingerprint = fields.single { it.name == "checkpoint_fingerprint" }.value
    assertEquals(
      FeatureTaskRuntimeCompactReferenceKind.REPOSITORY_CHECKPOINT,
      (fingerprint as FeatureTaskRuntimeHandoffProjectionValue.CompactReference).kind,
    )
  }

  @Test
  fun `an unnamed artifact or checkpoint is rejected rather than delivered blank`() {
    assertFailsWith<IllegalArgumentException> { reference(storePath = " ") }
    assertFailsWith<IllegalArgumentException> { reference(fingerprint = " ") }
  }

  @Test
  fun `rendered size stays constant as changed file and hunk counts grow`() {
    val oneFile = FeatureTaskRuntimeSharedReviewEvidenceReference.of("store", artifact(fileCount = 1))
    val manyFiles =
      FeatureTaskRuntimeSharedReviewEvidenceReference.of(
        "store",
        artifact(fileCount = FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT * 2, hunksPerFile = 8),
      )

    assertEquals(
      oneFile.toProjectionFields().sumOf { it.value.itemCount },
      manyFiles.toProjectionFields().sumOf { it.value.itemCount },
    )
    assertTrue(
      manyFiles.toProjectionFields().sumOf { it.value.utf8ByteSize } <
        oneFile.toProjectionFields().sumOf { it.value.utf8ByteSize } + PATH_FREE_GROWTH_BYTES,
      "rendered bytes must not grow with the changed-file catalog",
    )
    assertEquals(FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT * 2, manyFiles.changedFileCount)
    assertEquals(FEATURE_TASK_RUNTIME_CHANGED_PATH_MAX_COUNT * 2 * 8, manyFiles.changedHunkCount)
  }

  @Test
  fun `the digest separates two artifacts that index different files`() {
    val original = FeatureTaskRuntimeSharedReviewEvidenceReference.of("store", artifact(fileCount = 2))
    val renamed =
      FeatureTaskRuntimeSharedReviewEvidenceReference.of(
        "store",
        artifact(fileCount = 2).let { it.copy(files = it.files.map { file -> file.copy(path = "x-${file.path}") }) },
      )

    assertNotEquals(original.fileHunkIndexDigest, renamed.fileHunkIndexDigest)
    assertEquals(
      original.fileHunkIndexDigest,
      FeatureTaskRuntimeSharedReviewEvidenceReference.of("store", artifact(fileCount = 2)).fileHunkIndexDigest,
    )
  }

  @Test
  fun `a digest that is not a sha256 hex string is rejected`() {
    assertFailsWith<IllegalArgumentException> { reference(digest = "not-a-digest") }
  }

  private fun artifact(
    fileCount: Int = 1,
    hunksPerFile: Int = 1,
  ) = FeatureTaskRuntimeSharedEvidenceArtifact(
    fingerprint = "fp",
    baseRef = "base",
    headRef = "head",
    files = (1..fileCount).map { FeatureTaskRuntimeSharedEvidenceFileEntry("f$it.kt", "modified") },
    hunks =
      (1..fileCount).flatMap { file ->
        (1..hunksPerFile).map { FeatureTaskRuntimeSharedEvidenceHunkEntry("f$file.kt", "@@ -$it +$it @@") }
      },
    diffPayload = FeatureTaskRuntimeSharedEvidenceDiffPayloadRef("diff.patch", 1),
  )

  private fun reference(
    storePath: String = "store",
    fingerprint: String = "fp",
    digest: String = "0".repeat(64),
  ) = FeatureTaskRuntimeSharedReviewEvidenceReference(
    storePath = storePath,
    checkpointFingerprint = fingerprint,
    baseRef = "base",
    headRef = "head",
    changedFileCount = 1,
    changedHunkCount = 1,
    fileHunkIndexDigest = digest,
  )

  private companion object {
    const val PATH_FREE_GROWTH_BYTES = 16
  }
}
