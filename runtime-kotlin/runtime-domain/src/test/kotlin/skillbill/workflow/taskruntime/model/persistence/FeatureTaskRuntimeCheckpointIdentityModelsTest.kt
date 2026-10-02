package skillbill.workflow.taskruntime.model.persistence

import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeCheckpointIdentityVersionError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class FeatureTaskRuntimeCheckpointIdentityModelsTest {
  @Test
  fun `an identity with a digit-leading tracker key round-trips through the durable artifact shape`() {
    val identity =
      FeatureTaskRuntimeCheckpointIdentity(
        sequenceNumber = 0,
        issueKey = "0AC-11",
        subtaskId = "2",
        checkpointRef = featureTaskRuntimeCheckpointRefName("0AC-11", "2", 0),
        branch = "sermilionrestless/0ac-11-be-sessions",
        phaseId = "implement",
        generation = 0,
        ownedPathDigest = featureTaskRuntimeOwnedPathDigest(listOf("src/Owned.kt")),
        ownedPathCount = 1,
        commitSha = "a".repeat(40),
        recordedAt = "2026-09-03T00:00:00Z",
      )

    val decoded =
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        featureTaskRuntimeCheckpointIdentitiesToArtifact(listOf(identity)),
      )

    assertEquals(listOf(identity), decoded)
  }

  @Test
  fun `an identity round-trips through the durable artifact shape`() {
    val identity = identity(sequenceNumber = 3)

    val decoded =
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        featureTaskRuntimeCheckpointIdentitiesToArtifact(listOf(identity)),
      )

    assertEquals(listOf(identity), decoded)
  }

  @Test
  fun `an absent artifact decodes to an empty history so a legacy workflow needs no migration`() {
    assertEquals(emptyList(), featureTaskRuntimeCheckpointIdentitiesFromArtifact(null))
  }

  @Test
  fun `an unsupported contract version loud-fails with both versions so quarantine can be distinguished`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeCheckpointIdentityVersionError> {
        featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          mapOf("contract_version" to "0.1", "checkpoints" to emptyList<Any?>()),
        )
      }

    assertEquals("0.2", error.expectedContractVersion)
    assertEquals("0.1", error.actualContractVersion)
  }

  @Test
  fun `an unsupported field loud-fails rather than being reinterpreted`() {
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf(
          "contract_version" to "0.2",
          "checkpoints" to listOf(identity().toArtifactMap() + ("raw_prompt" to "…")),
        ),
      )
    }
  }

  @Test
  fun `an amended commit sha is shared across checkpoints but a duplicated ref loud-fails`() {
    val amended = listOf(identity(sequenceNumber = 0), identity(sequenceNumber = 1))

    assertEquals(
      amended,
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf("contract_version" to "0.2", "checkpoints" to amended.map { it.toArtifactMap() }),
      ),
      "an amend leaves two checkpoints on one sha; only the ref is the identity",
    )

    val duplicatedRef =
      listOf(
        identity(sequenceNumber = 0).toArtifactMap(),
        identity(sequenceNumber = 0, commitSuffix = 2).toArtifactMap(),
      )
    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          mapOf("contract_version" to "0.2", "checkpoints" to duplicatedRef),
        )
      }

    assertContains(error.message.orEmpty(), "more than once")
  }

  @Test
  fun `a record whose ref names a different subtask than its own fields fails the whole read`() {
    val drifted =
      identity(sequenceNumber = 0).toArtifactMap() +
        ("checkpoint_ref" to featureTaskRuntimeCheckpointRefName("SKILL-150", "9", 0))

    val error =
      assertFailsWith<InvalidWorkflowStateSchemaError> {
        featureTaskRuntimeCheckpointIdentitiesFromArtifact(
          mapOf(
            "contract_version" to "0.2",
            "checkpoints" to listOf(identity(sequenceNumber = 1).toArtifactMap(), drifted),
          ),
        )
      }

    assertContains(error.message.orEmpty(), "does not derive from")
  }

  @Test
  fun `a ledger mixing current records with one legacy-shaped record fails whole`() {
    val legacy = identity(sequenceNumber = 1).toArtifactMap() - "checkpoint_ref"

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      featureTaskRuntimeCheckpointIdentitiesFromArtifact(
        mapOf(
          "contract_version" to "0.2",
          "checkpoints" to listOf(identity(sequenceNumber = 0).toArtifactMap(), legacy),
        ),
      )
    }
  }

  @Test
  fun `re-appending an already-recorded ref is a no-op while a later ref on the same sha appends`() {
    val history = featureTaskRuntimeAppendCheckpointIdentity(emptyList(), identity(sequenceNumber = 0))

    val resumed = featureTaskRuntimeAppendCheckpointIdentity(history, identity(sequenceNumber = 0))
    assertEquals(history, resumed)

    val postAmend = featureTaskRuntimeAppendCheckpointIdentity(resumed, identity(sequenceNumber = 1))
    assertEquals(2, postAmend.size)
  }

  @Test
  fun `appending prunes oldest-first at the retention limit`() {
    val history =
      (0 until FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT).fold(
        emptyList<FeatureTaskRuntimeCheckpointIdentity>(),
      ) { acc, index ->
        featureTaskRuntimeAppendCheckpointIdentity(acc, identity(sequenceNumber = index, commitSuffix = index))
      }
    assertEquals(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, history.size)

    val overflowed =
      featureTaskRuntimeAppendCheckpointIdentity(
        history,
        identity(sequenceNumber = FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, commitSuffix = 9_999),
      )

    assertEquals(FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES_LIMIT, overflowed.size)
    assertEquals(1, overflowed.first().sequenceNumber, "the oldest entry is the one dropped")
  }

  @Test
  fun `the owned-path digest is order-independent but content-sensitive`() {
    val ordered = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Two.kt"))
    val reordered = featureTaskRuntimeOwnedPathDigest(listOf("b/Two.kt", "a/One.kt"))
    val different = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Three.kt"))

    assertEquals(ordered, reordered)
    assertNotEquals(ordered, different)
  }

  @Test
  fun `a path containing the join delimiter cannot forge another inventory's digest`() {
    val split = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt", "b/Two.kt"))
    val joined = featureTaskRuntimeOwnedPathDigest(listOf("a/One.kt\u0000b/Two.kt"))

    assertNotEquals(split, joined)
  }

  private fun identity(
    sequenceNumber: Int = 0,
    commitSuffix: Int = 1,
  ): FeatureTaskRuntimeCheckpointIdentity =
    FeatureTaskRuntimeCheckpointIdentity(
      sequenceNumber = sequenceNumber,
      issueKey = "SKILL-150",
      subtaskId = "2",
      checkpointRef = featureTaskRuntimeCheckpointRefName("SKILL-150", "2", sequenceNumber),
      branch = "feat/SKILL-150-scoped-checkpoint",
      phaseId = "audit",
      generation = 1,
      ownedPathDigest = featureTaskRuntimeOwnedPathDigest(listOf("src/Owned.kt")),
      ownedPathCount = 1,
      commitSha = commitSuffix.toString(16).padStart(40, '0'),
      recordedAt = "2026-08-04T00:00:00Z",
      loopId = "audit_gap",
      parentSha = "b".repeat(40),
    )
}
