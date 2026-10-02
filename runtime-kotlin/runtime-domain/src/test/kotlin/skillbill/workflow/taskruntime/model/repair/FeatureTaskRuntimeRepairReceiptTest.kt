package skillbill.workflow.taskruntime.model.repair

import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeRepairReceiptError
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairConstruct
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairOutcome
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceipt
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptDecodeObservations
import skillbill.workflow.model.goalreview.FeatureTaskRuntimeRepairReceiptEntry
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import skillbill.workflow.model.goalreview.REPAIR_RECEIPT_MAX_ENTRIES
import skillbill.workflow.model.goalreview.REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES
import skillbill.workflow.model.goalreview.attemptedUnresolvedEntries
import skillbill.workflow.model.goalreview.coversCarriedFindings
import skillbill.workflow.model.goalreview.featureTaskRuntimeRemediationRoundNumber
import skillbill.workflow.model.goalreview.omittedCarriedFindings
import skillbill.workflow.model.goalreview.withoutRefutedFindings
import skillbill.workflow.taskruntime.artifact.decodeRepairReceiptFromArtifactWithObservations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepairReceiptTest {
  private val sha = "a".repeat(40)

  @Test
  fun `a symbol and a file-plus-symbol construct are accepted while a repository path is rejected`() {
    FeatureTaskRuntimeRepairConstruct(symbol = "Type")
    FeatureTaskRuntimeRepairConstruct(symbol = "Type.member", file = "Type.kt")
    val pathOnly =
      assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
        FeatureTaskRuntimeRepairConstruct(symbol = "runtime-kotlin/src/Type.kt")
      }
    assertTrue(pathOnly.payloadFreeReason.contains("never a repository path"))
    assertTrue(pathOnly.message.orEmpty().none { it == '/' }, "rejection must not echo the path")
  }

  @Test
  fun `salvageCompactReceiptSymbol keeps Type when the member is a spaced display name`() {
    val spacedDisplayName =
      "AgentRunServiceRuntimeComponentTest.runtime component exposes agent run " +
        "service with filesystem launcher binding"
    assertEquals(
      "AgentRunServiceRuntimeComponentTest",
      salvageCompactReceiptSymbol(spacedDisplayName),
    )
    assertEquals(null, salvageCompactReceiptSymbol("Type.member"))
    assertEquals(null, salvageCompactReceiptSymbol("runtime-kotlin/src/Type.kt"))
    assertEquals(
      FeatureTaskRuntimeRepairConstruct(
        symbol = "AgentRunServiceRuntimeComponentTest",
        file = "AgentRunServiceRuntimeComponentTest.kt",
      ),
      FeatureTaskRuntimeRepairConstruct.fromArtifactMap(
        mapOf(
          "symbol" to spacedDisplayName,
          "file" to "AgentRunServiceRuntimeComponentTest.kt",
        ),
        "repair_receipt.entries[0].constructs[0]",
      ),
    )
  }

  @Test
  fun `construct identity normalizes case to one stable key`() {
    val first = FeatureTaskRuntimeRepairConstruct(symbol = "Type.member", file = "Type.kt")
    val second = FeatureTaskRuntimeRepairConstruct(symbol = "TYPE.MEMBER", file = "TYPE.kt")
    assertEquals(first.identity, second.identity)
    assertNotEquals(
      first.identity,
      FeatureTaskRuntimeRepairConstruct(symbol = "Type.other").identity,
    )
  }

  @Test
  fun `an entry set over its named collection budget throws and never returns a shortened receipt`() {
    val over =
      List(REPAIR_RECEIPT_MAX_ENTRIES + 1) { index ->
        addressedEntry(findingId = "F-${index.toString().padStart(3, '0')}")
      }
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
        FeatureTaskRuntimeRepairReceipt(
          roundNumber = 1,
          preFixCheckpointSha = sha,
          entries = over,
        )
      }
    assertEquals("entries", error.fieldPath)
    assertTrue(error.payloadFreeReason.contains("$REPAIR_RECEIPT_MAX_ENTRIES"))
  }

  @Test
  fun `receipt contract version is the pinned 0_3 constant`() {
    assertEquals("0.3", FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION)
    assertEquals(
      FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
      validReceipt().contractVersion,
    )
  }

  @Test
  fun `legacy 0_2 repair receipt contract version loud-fails`() {
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
        FeatureTaskRuntimeRepairReceipt.fromArtifactMap(
          mapOf(
            "contract_version" to "0.2",
            "round_number" to 1,
            "pre_fix_checkpoint_sha" to sha,
            "entries" to listOf(mapOf("finding_id" to "F-001", "outcome" to "addressed")),
          ),
          "repair_receipt",
        )
      }
    assertEquals("repair_receipt.contract_version", error.fieldPath)
  }

  @Test
  fun `remediation round number is the completed pass count at implement_fix entry`() {
    assertEquals(2, featureTaskRuntimeRemediationRoundNumber(2))
    assertFailsWith<InvalidFeatureTaskRuntimeRepairReceiptError> {
      featureTaskRuntimeRemediationRoundNumber(0)
    }
  }

  @Test
  fun `coverage keys on finding_id only`() {
    val carried =
      listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "blocker",
          label = "ReducerLabel",
          text = "sanitized compact finding text",
          findingId = "F-001",
        ),
        GoalSubtaskReviewCompactFinding(
          severity = "major",
          label = "OtherReducerLabel",
          text = "other sanitized compact finding text",
          findingId = "F-002",
        ),
      )
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = sha,
        entries =
          listOf(
            addressedEntry(findingId = "F-001"),
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.NO_EDIT_REQUIRED,
              findingId = "F-002",
              noEditReason = "construct already matched the finding",
            ),
          ),
      )
    assertTrue(receipt.coversCarriedFindings(carried))
    val omittedSecond = receipt.copy(entries = receipt.entries.take(1))
    assertTrue(!omittedSecond.coversCarriedFindings(carried))
  }

  @Test
  fun `finding_ref alias on a receipt entry satisfies coverage for that finding`() {
    val carried =
      listOf(
        GoalSubtaskReviewCompactFinding(
          severity = "nit",
          label = "StormLabel",
          text = "a".repeat(300),
          findingId = "F-003",
        ),
      )
    val entry =
      FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
        mapOf(
          "finding_ref" to "F-003",
          "outcome" to "addressed",
          "constructs" to listOf(mapOf("symbol" to "SomeClass")),
          "intent" to "ignored decoration",
        ),
        "repair_receipt.entries[0]",
      )
    assertEquals("F-003", entry.findingId)
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = sha,
        entries = listOf(entry),
      )
    assertTrue(receipt.coversCarriedFindings(carried))
  }

  @Test
  fun `census-only entry decodes finding_id and outcome and ignores decoration`() {
    val entry =
      FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
        mapOf(
          "finding_id" to "F-001",
          "outcome" to "addressed",
          "severity" to "blocker",
          "constructs" to listOf(mapOf("symbol" to "Type.member")),
          "intent" to "ignored decoration",
        ),
        "repair_receipt.entries[0]",
      )
    assertEquals("F-001", entry.findingId)
    assertEquals(FeatureTaskRuntimeRepairOutcome.ADDRESSED, entry.outcome)
  }

  @Test
  fun `optional unresolved_reason forwards after truncation with observability record`() {
    val collector = FeatureTaskRuntimeRepairReceiptDecodeObservations.Collector()
    val oversized = "x".repeat(REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES + 1)
    val entry =
      FeatureTaskRuntimeRepairReceiptEntry.fromArtifactMap(
        mapOf(
          "finding_id" to "F-010",
          "outcome" to "attempted_unresolved",
          "unresolved_reason" to oversized,
        ),
        "repair_receipt.entries[0]",
        collector,
      )
    assertTrue(collector.finish().truncationRecords.isNotEmpty())
    val unresolvedBytes = entry.unresolvedReason.orEmpty().encodeToByteArray().size
    assertTrue(unresolvedBytes <= REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES)
  }

  @Test
  fun `repair receipt decode result carries truncation observations`() {
    val decoded =
      assertNotNull(
        decodeRepairReceiptFromArtifactWithObservations(
          raw =
            mapOf(
              "contract_version" to FEATURE_TASK_RUNTIME_REPAIR_RECEIPT_CONTRACT_VERSION,
              "round_number" to 1,
              "pre_fix_checkpoint_sha" to sha,
              "entries" to
                listOf(
                  mapOf(
                    "finding_id" to "F-011",
                    "outcome" to "attempted_unresolved",
                    "unresolved_reason" to "x".repeat(REPAIR_RECEIPT_MAX_UNRESOLVED_REASON_UTF8_BYTES + 1),
                  ),
                ),
            ),
          sourceLabel = "repair_receipt",
        ),
      )

    assertEquals(1, decoded.observations.truncationRecords.size)
    assertTrue(decoded.observations.truncationRecords.single().contains("entries[0].unresolved_reason"))
  }

  @Test
  fun `an attempted_unresolved entry keeps the finding accounted for and separable from a closed one`() {
    val carried =
      listOf(
        GoalSubtaskReviewCompactFinding("blocker", "TypeKt", "closed this round", "F-001"),
        GoalSubtaskReviewCompactFinding("major", "Policy", "still open", "F-002"),
      )
    val receipt =
      FeatureTaskRuntimeRepairReceipt(
        roundNumber = 1,
        preFixCheckpointSha = sha,
        entries =
          listOf(
            addressedEntry(findingId = "F-001"),
            FeatureTaskRuntimeRepairReceiptEntry(
              outcome = FeatureTaskRuntimeRepairOutcome.ATTEMPTED_UNRESOLVED,
              findingId = "F-002",
              unresolvedReason = "the gate cannot reach the review pass ids it would compare",
            ),
          ),
      )

    assertTrue(receipt.omittedCarriedFindings(carried).isEmpty())
    assertEquals(listOf("F-002"), receipt.attemptedUnresolvedEntries().map { it.findingId })
  }

  @Test
  fun `refutation drops a carried finding by normalized ref and leaves an unnamed one carried`() {
    val carried =
      listOf(
        GoalSubtaskReviewCompactFinding("blocker", "TypeKt", "closed this round", "F-001"),
        GoalSubtaskReviewCompactFinding("nit", "Query", "the selection is never read", "F-003"),
        GoalSubtaskReviewCompactFinding("major", "Policy", "review named no ref"),
      )

    val remaining = withoutRefutedFindings(carried, setOf(" f-003 "))

    assertEquals(listOf("F-001", null), remaining.map(GoalSubtaskReviewCompactFinding::findingId))
    assertEquals(carried, withoutRefutedFindings(carried, emptySet()))
  }

  private fun validReceipt() =
    FeatureTaskRuntimeRepairReceipt(
      roundNumber = 1,
      preFixCheckpointSha = sha,
      entries = listOf(addressedEntry()),
    )

  private fun addressedEntry(findingId: String = "F-001") =
    FeatureTaskRuntimeRepairReceiptEntry(
      outcome = FeatureTaskRuntimeRepairOutcome.ADDRESSED,
      findingId = findingId,
    )
}
