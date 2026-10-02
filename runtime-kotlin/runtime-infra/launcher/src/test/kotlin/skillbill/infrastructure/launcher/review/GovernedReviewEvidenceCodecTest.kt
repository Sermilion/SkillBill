package skillbill.infrastructure.launcher.review

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.InvalidGovernedReviewEvidenceRequestError
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.review.context.model.execution.ForbiddenReviewOperation
import skillbill.review.context.model.execution.GovernedReviewJsonRpcArguments
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class GovernedReviewEvidenceCodecTest {
  @Test
  fun `broker tools advertise bounded read only behavior to approval clients`() {
    GovernedReviewEvidenceCodec.toolSpecList().asToolPayloads().forEach { tool ->
      val annotations = tool["annotations"] as Map<*, *>
      assertEquals(true, annotations["readOnlyHint"])
      assertEquals(false, annotations["destructiveHint"])
      assertEquals(false, annotations["openWorldHint"])
    }
  }

  @Test
  fun `non-object read selector fails with typed request error`() {
    val error =
      assertFailsWith<InvalidGovernedReviewEvidenceRequestError> {
        GovernedReviewEvidenceCodec.readRequest(
          lane = "lane-a",
          arguments =
            GovernedReviewJsonRpcArguments.from(
              mapOf("requests" to listOf("not-an-object")),
            ),
          expansionById = { null },
        )
      }

    assertEquals("review-evidence", error.operation)
  }

  @Test
  fun `a refused read serialises a reason and no content field`() {
    val payload =
      GovernedReviewEvidenceCodec.batchResultPayload(
        ReviewEvidenceBatchResult(
          results =
            listOf(
              ReviewEvidenceResult(
                content = "package secrets",
                bytes = 15,
                cumulativeBytes = 15,
                expansionCount = 0,
                forbidden =
                  ForbiddenReviewOperation(
                    category = "unreachable_path",
                    target = "src/Other.kt",
                    reason = "outside the assignment surface",
                  ),
              ),
            ),
          cumulativeBytes = 0,
          expansions = emptyList(),
        ),
      )
    val result = requireNotNull(JsonCodec.anyToStringAnyMapList(payload.toPayload()["results"])).single()
    assertFalse(result.containsKey("content"))
    assertEquals(true, result["refused"])
    assertEquals("outside the assignment surface", result["reason"])
  }

  @Test
  fun `a read naming an issued expansion inherits that expansion's reachability reason`() {
    val record =
      ReviewExpansionRecord(
        expansionId = "exp-1",
        assignmentDigest = "a".repeat(64),
        requestedPath = "src/Other.kt",
        reachabilityReason = "called by the assigned hunk",
        authorized = true,
        sequence = 1,
      )

    val request =
      GovernedReviewEvidenceCodec.readRequest(
        lane = "lane-a",
        arguments =
          GovernedReviewJsonRpcArguments.from(
            mapOf("requests" to listOf(mapOf("path" to "src/Other.kt", "expansion_id" to "exp-1"))),
          ),
        expansionById = { id -> record.takeIf { id == it.expansionId } },
      )

    assertEquals("called by the assigned hunk", request.requests.single().reachabilityReason)
  }

  @Test
  fun `the governed surface is exactly two operations`() {
    assertEquals(GovernedReviewEvidenceContracts.OPERATIONS, listOf("read_evidence", "request_expansion"))
    assertEquals(
      GovernedReviewEvidenceCodec.toolSpecList().asToolPayloads().map { it["name"] },
      GovernedReviewEvidenceContracts.OPERATIONS,
    )
  }
}
