package skillbill.contracts.approval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApprovalContractsTest {
  @Test
  fun `round trips typed request identity and complete ordered approval content`() {
    val request =
      ApprovalRequestRecord(
        contractVersion = OPERATOR_APPROVAL_CONTRACT_VERSION,
        approvalId = "approval-1",
        repositoryIdentity = "repo-identity",
        checkoutRoot = "/repo/worktree",
        runId = "run-1",
        processGeneration = "generation-2",
        provider = "claude",
        providerRequestId = ApprovalProviderRequestId.Integer("9223372036854775808123"),
        title = "Run command",
        reason = null,
        subject = "Bash",
        inputText = "x".repeat(20_000),
        options = listOf(ApprovalOption("allow", "Allow once", "allow"), ApprovalOption("deny", "Deny", "deny")),
        createdAt = "2026-11-05T12:00:00Z",
        deadlineAt = "2026-11-05T12:05:00Z",
        state = ApprovalState.PENDING,
        providerPayload = "{\"opaque\":true}",
      )
    val encoded = ApprovalContractJson.encode(request)
    val decoded = ApprovalContractJson.decode(encoded)

    assertEquals(request, decoded)
    assertTrue(encoded.contains("\"provider_request_id\":9223372036854775808123"))
    assertTrue(encoded.contains("\"input_text\":\"${"x".repeat(20_000)}\""))

    val textualId = request.copy(providerRequestId = ApprovalProviderRequestId.Text("9223372036854775808123"))
    val textualRoundTrip = ApprovalContractJson.decode(ApprovalContractJson.encode(textualId))
    assertNotEquals(request.providerRequestId, textualRoundTrip.providerRequestId)
  }

  @Test
  fun `integer request ids accept only the canonical JSON integer grammar`() {
    listOf("0", "-7", "9223372036854775808123").forEach { ApprovalProviderRequestId.Integer(it) }
    listOf("+5", "007", "-0x1", "1.0", "", "1e3").forEach { raw ->
      assertFailsWith<IllegalArgumentException> { ApprovalProviderRequestId.Integer(raw) }
    }
  }
}
