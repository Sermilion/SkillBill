package skillbill.infrastructure.contracts.workflow.featuretask

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import skillbill.error.core.SkillBillRuntimeException

class FeatureTaskRuntimeCheckpointIdentitySchemaValidatorTest {
  @Test
  fun `accepts a full checkpoint-identity record`() {
    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.2", "checkpoints" to listOf(entry())),
      SOURCE,
    )
  }

  @Test
  fun `accepts a record whose only checkpoint omits the optional loop and parent fields`() {
    val forwardEdge =
      entry().toMutableMap().apply {
        remove("loop_id")
        remove("parent_sha")
      }

    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.2", "checkpoints" to listOf(forwardEdge)),
      SOURCE,
    )
  }

  @Test
  fun `accepts an empty history`() {
    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf("contract_version" to "0.2", "checkpoints" to emptyList<Any?>()),
      SOURCE,
    )
  }

  @Test
  fun `rejects an unknown field rather than reinterpreting the record`() {
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
          mapOf(
            "contract_version" to "0.2",
            "checkpoints" to listOf(entry() + ("raw_diff" to "@@ -1 +1 @@")),
          ),
          SOURCE,
        )
      }

    assertContains(error.message.orEmpty(), "raw_diff")
  }

  @Test
  fun `rejects a wrong contract version`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf("contract_version" to "0.1", "checkpoints" to listOf(entry())),
        SOURCE,
      )
    }
  }

  @Test
  fun `accepts two checkpoints on one amended commit sha under distinct refs`() {
    val amendedSha = "d".repeat(40)

    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf(
        "contract_version" to "0.2",
        "checkpoints" to
          listOf(
            entry(sequenceNumber = 0, commitSha = amendedSha),
            entry(sequenceNumber = 1, commitSha = amendedSha),
          ),
      ),
      SOURCE,
    )
  }

  @Test
  fun `rejects a checkpoint missing its ref or owning subtask`() {
    listOf("checkpoint_ref", "subtask_id").forEach { field ->
      assertFailsWith<SkillBillRuntimeException> {
        FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
          mapOf("contract_version" to "0.2", "checkpoints" to listOf(entry() - field)),
          SOURCE,
        )
      }
    }
  }

  @Test
  fun `rejects a missing required identity field`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.2",
          "checkpoints" to listOf(entry() - "owned_path_digest"),
        ),
        SOURCE,
      )
    }
  }

  @Test
  fun `rejects a control-bearing issue key so an unbounded authority boundary cannot be recorded`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.2",
          "checkpoints" to listOf(entry() + ("issue_key" to "SKILL-150\nspoofed")),
        ),
        SOURCE,
      )
    }
  }

  @Test
  fun `accepts a digit-leading tracker key bound to a matching checkpoint ref`() {
    FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
      mapOf(
        "contract_version" to "0.2",
        "checkpoints" to
          listOf(
            entry() +
              ("issue_key" to "0AC-11") +
              ("checkpoint_ref" to "refs/skill-bill/checkpoints/0AC-11/2/0"),
          ),
      ),
      SOURCE,
    )
  }

  @Test
  fun `rejects a commit sha that is not a commit sha`() {
    assertFailsWith<SkillBillRuntimeException> {
      FeatureTaskRuntimeCheckpointIdentitySchemaValidator.validate(
        mapOf(
          "contract_version" to "0.2",
          "checkpoints" to listOf(entry() + ("commit_sha" to "checkpoint-sha")),
        ),
        SOURCE,
      )
    }
  }

  private fun entry(
    sequenceNumber: Int = 0,
    commitSha: String = "a".repeat(40),
  ): Map<String, Any?> =
    mapOf(
      "sequence_number" to sequenceNumber,
      "issue_key" to "SKILL-150",
      "subtask_id" to "2",
      "checkpoint_ref" to "refs/skill-bill/checkpoints/SKILL-150/2/$sequenceNumber",
      "branch" to "feat/SKILL-150-scoped-checkpoint",
      "phase_id" to "audit",
      "loop_id" to "audit_gap",
      "generation" to 1,
      "parent_sha" to "b".repeat(40),
      "owned_path_digest" to "c".repeat(64),
      "owned_path_count" to 3,
      "commit_sha" to commitSha,
      "recorded_at" to "2026-08-04T00:00:00Z",
    )

  private companion object {
    const val SOURCE = "checkpoint-identity-test"
  }
}
