package skillbill.infrastructure.launcher.review

import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.ReviewEvidenceBatchRequest
import skillbill.ports.review.model.ReviewEvidenceBatchResult
import skillbill.ports.review.model.ReviewEvidenceResult
import skillbill.ports.review.model.ReviewExpansionAuthorizationRequest
import skillbill.ports.review.model.ReviewLaneAccounting
import skillbill.ports.review.model.ReviewToolCall
import skillbill.ports.review.model.ReviewToolCallResult
import skillbill.review.context.model.accounting.ReviewBudgetOutcome
import skillbill.review.context.model.execution.ForbiddenReviewOperation
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GovernedReviewEvidenceEndpointTest {
  private class RecordingBroker : ReviewEvidenceBroker {
    val reads = mutableListOf<ReviewEvidenceBatchRequest>()

    override fun authorizeExpansion(request: ReviewExpansionAuthorizationRequest): ReviewExpansionRecord =
      error("unused")

    override fun readBatch(request: ReviewEvidenceBatchRequest): ReviewEvidenceBatchResult {
      reads += request
      return ReviewEvidenceBatchResult(
        results =
          listOf(
            ReviewEvidenceResult(
              content = "package secrets",
              bytes = 15,
              cumulativeBytes = 0,
              expansionCount = 0,
              forbidden = ForbiddenReviewOperation("unreachable_path", request.requests.single().path, "not assigned"),
            ),
          ),
        cumulativeBytes = 0,
        expansions = emptyList(),
      )
    }

    override fun recordToolCall(call: ReviewToolCall): ReviewToolCallResult = error("unused")

    override fun recordModelTurn(): ReviewBudgetOutcome? = null

    override fun validateLaneResult(result: String): ReviewBudgetOutcome? = null

    override fun observeLaneResultChunk(chunk: String): ReviewBudgetOutcome? = null

    override fun accounting(): ReviewLaneAccounting =
      ReviewLaneAccounting(
        lane = "architecture",
        evidenceBytes = 0,
        expansions = emptyList(),
        toolCalls = 0,
        modelTurns = 0,
        resultBytes = 0,
      )

    override fun terminalOutcome(): ReviewBudgetOutcome? = null
  }

  @Test
  fun `an out-of-surface read is refused with no content and still reaches the broker`() {
    val protocol = RecordingBroker()
    GovernedReviewEvidenceEndpoint.bind("architecture", protocol, listOf("/bin/true")).use { endpoint ->
      connect(endpoint, endpoint.descriptor.token).use { connection ->
        val reply = requireNotNull(connection.call(readFrame("src/Elsewhere.kt")))
        val payload = toolPayload(reply)
        val result = requireNotNull(JsonCodec.anyToStringAnyMapList((payload["results"]))).single()
        assertEquals(true, result["refused"])
        assertFalse(result.containsKey("content"))
      }
      assertEquals(listOf("src/Elsewhere.kt"), protocol.reads.map { it.requests.single().path })
    }
  }

  @Test
  fun `a connection presenting a foreign token is served nothing`() {
    val protocol = RecordingBroker()
    GovernedReviewEvidenceEndpoint.bind("architecture", protocol, listOf("/bin/true")).use { endpoint ->
      Client(SocketChannel.open(UnixDomainSocketAddress.of(endpoint.descriptor.socketPath))).use { connection ->
        assertNull(connection.handshake("not-this-launch"))
      }
      assertTrue(protocol.reads.isEmpty())
    }
  }

  @Test
  fun `a bind that fails after opening the listener leaves no per-launch directory behind`() {
    val tempRoot = GovernedReviewEvidenceEndpoint.perLaunchRoot()
    val before = perLaunchDirectories(tempRoot)

    val bindError =
      assertFailsWith<SkillBillRuntimeException> {
        GovernedReviewEvidenceEndpoint.bind("architecture", RecordingBroker(), emptyList())
      }
    assertEquals(GovernedReviewFailureCode.EVIDENCE_TRANSPORT, bindError.code)

    assertEquals(before, perLaunchDirectories(tempRoot))
  }

  @Test
  fun `an isolated home without runtime-mcp loud-fails instead of inheriting the host binary`() {
    val home = Files.createTempDirectory("review-evidence-home")
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        bridgeCommand(emptyMap(), home)
      }
    assertEquals(GovernedReviewFailureCode.EVIDENCE_TRANSPORT, error.code)
    assertTrue(error.message!!.contains(home.toString()))
    assertTrue(error.message!!.contains("missing or not executable"))
  }

  private fun perLaunchDirectories(root: Path): Set<String> =
    Files.list(root).use { paths ->
      paths.map { it.fileName.toString() }
        .filter { it.startsWith("skill-bill-review-evidence-") }
        .toList()
        .toSet()
    }

  @Test
  fun `a temp root too long for a unix socket path still yields a bindable endpoint`() {
    val longRoot = Files.createTempDirectory("review-evidence-long-root").resolve("d".repeat(80))
    Files.createDirectories(longRoot)
    val previousTempRoot = System.getProperty("java.io.tmpdir")
    System.setProperty("java.io.tmpdir", longRoot.toString())
    try {
      val protocol = RecordingBroker()
      GovernedReviewEvidenceEndpoint.bind("architecture", protocol, listOf("/bin/true")).use { endpoint ->
        connect(endpoint, endpoint.descriptor.token).use { connection ->
          connection.call(readFrame("src/Elsewhere.kt"))
        }
        assertEquals(listOf("src/Elsewhere.kt"), protocol.reads.map { it.requests.single().path })
      }
    } finally {
      System.setProperty("java.io.tmpdir", previousTempRoot)
    }
  }

  @Test
  fun `closing the endpoint removes the per-launch socket and config`() {
    val endpoint = GovernedReviewEvidenceEndpoint.bind("architecture", RecordingBroker(), listOf("/bin/true"))
    assertTrue(Files.exists(endpoint.descriptor.socketPath))
    assertTrue(Files.exists(endpoint.descriptor.mcpConfigPath))
    val cursorConfig =
      GovernedReviewMcpConfigWriter.cursorProjectConfigPath(
        endpoint.descriptor.mcpConfigPath,
      )
    val tomlConfig =
      GovernedReviewMcpConfigWriter.tomlConfigPath(
        endpoint.descriptor.mcpConfigPath,
      )
    assertEquals(Files.readString(endpoint.descriptor.mcpConfigPath), Files.readString(cursorConfig))
    assertTrue(Files.exists(tomlConfig))
    assertTrue(Files.readString(tomlConfig).contains("[mcp_servers.skill-bill-review-evidence]"))

    endpoint.close()

    assertFalse(Files.exists(endpoint.descriptor.socketPath))
    assertFalse(Files.exists(endpoint.descriptor.mcpConfigPath))
    assertFalse(Files.exists(tomlConfig))
    assertFalse(Files.exists(cursorConfig))
  }

  private class Client(private val channel: SocketChannel) : AutoCloseable {
    private val reader = Channels.newInputStream(channel).bufferedReader()
    private val writer = Channels.newOutputStream(channel).bufferedWriter()

    fun handshake(token: String): String? {
      send(
        JsonCodec.mapToJsonString(
          linkedMapOf("jsonrpc" to "2.0", "method" to "handshake", "params" to mapOf("token" to token)),
        ),
      )
      return reader.readLine()
    }

    fun call(frame: String): String? {
      send(frame)
      return reader.readLine()
    }

    private fun send(frame: String) {
      writer.appendLine(frame)
      writer.flush()
    }

    override fun close() {
      channel.close()
    }
  }

  private fun connect(
    endpoint: GovernedReviewEvidenceEndpoint,
    token: String,
  ): Client =
    Client(SocketChannel.open(UnixDomainSocketAddress.of(endpoint.descriptor.socketPath)))
      .also { it.handshake(token) }

  private fun readFrame(path: String): String =
    JsonCodec.mapToJsonString(
      linkedMapOf(
        "jsonrpc" to "2.0",
        "id" to 1,
        "method" to "tools/call",
        "params" to
          linkedMapOf(
            "name" to "read_evidence",
            "arguments" to mapOf("requests" to listOf(mapOf("path" to path))),
          ),
      ),
    )

  private fun toolPayload(reply: String): Map<String, Any?> {
    val message = requireNotNull(JsonCodec.parseObjectOrNull(reply))
    val result = JsonCodec.anyToStringAnyMap(message["result"]?.let(JsonCodec::jsonElementToValue)).orEmpty()
    val content = requireNotNull(JsonCodec.anyToStringAnyMapList((result["content"]))).single()
    return requireNotNull(
      JsonCodec.anyToStringAnyMap(
        JsonCodec.parseObjectOrNull(content["text"].toString())?.let(JsonCodec::jsonElementToValue),
      ),
    )
  }
}
