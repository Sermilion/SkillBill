package skillbill.mcp.review

import skillbill.contracts.JsonCodec
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GovernedReviewEvidenceBridgeTest {
  @Test
  fun `the bridge advertises exactly the two governed operations`() {
    var forwarded = false
    val reply =
      requireNotNull(
        GovernedReviewEvidenceBridge.handleLine(
          JsonCodec.mapToJsonString(linkedMapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list")),
        ) {
          forwarded = true
          JsonCodec.mapToJsonString(
            linkedMapOf(
              "jsonrpc" to "2.0",
              "id" to 1,
              "result" to
                linkedMapOf(
                  "tools" to
                    listOf(
                      linkedMapOf("name" to "read_evidence"),
                      linkedMapOf("name" to "request_expansion"),
                    ),
                ),
            ),
          )
        },
      )

    assertTrue(forwarded)
    val result =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.parseObjectOrNull(reply)?.get("result")?.let(JsonCodec::jsonElementToValue),
      ).orEmpty()
    val tools = requireNotNull(JsonCodec.anyToStringAnyMapList(result["tools"]))
    assertEquals(listOf("read_evidence", "request_expansion"), tools.map { it["name"] })
  }

  @Test
  fun `bridge forwards tools list to the governed evidence socket`() {
    val socketPath = Files.createTempFile("skillbill-evidence-tools", ".sock")
    Files.delete(socketPath)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socketPath))
    var forwardedRequest: String? = null
    val thread =
      Thread {
        server.use {
          it.accept().use { channel ->
            val reader = Channels.newInputStream(channel).bufferedReader()
            val writer = Channels.newOutputStream(channel).bufferedWriter()
            reader.readLine()
            writer.appendLine(
              JsonCodec.mapToJsonString(
                linkedMapOf(
                  "jsonrpc" to "2.0",
                  "id" to 0,
                  "result" to emptyMap<String, Any?>(),
                ),
              ),
            )
            writer.flush()
            forwardedRequest = reader.readLine()
            writer.appendLine(
              JsonCodec.mapToJsonString(
                linkedMapOf(
                  "jsonrpc" to "2.0",
                  "id" to 1,
                  "result" to
                    linkedMapOf(
                      "tools" to
                        listOf(
                          linkedMapOf("name" to "read_evidence"),
                          linkedMapOf("name" to "request_expansion"),
                        ),
                    ),
                ),
              ),
            )
            writer.flush()
          }
        }
      }
    thread.start()

    val request =
      JsonCodec.mapToJsonString(
        linkedMapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"),
      )
    val response =
      GovernedReviewEvidenceConnection.connect(socketPath, "token").use { connection ->
        GovernedReviewEvidenceBridge.handleLine(request, connection::forward)
      }

    thread.join(5_000)
    assertEquals(request, forwardedRequest)
    val result =
      JsonCodec.anyToStringAnyMap(
        JsonCodec.parseObjectOrNull(requireNotNull(response))?.get("result")?.let(JsonCodec::jsonElementToValue),
      ).orEmpty()
    val tools = requireNotNull(JsonCodec.anyToStringAnyMapList(result["tools"]))
    assertEquals(listOf("read_evidence", "request_expansion"), tools.map { it["name"] })
  }

  @Test
  fun `a call to any other tool is refused instead of forwarded`() {
    var forwarded = false
    val reply =
      requireNotNull(
        GovernedReviewEvidenceBridge.handleLine(
          JsonCodec.mapToJsonString(
            linkedMapOf(
              "jsonrpc" to "2.0",
              "id" to 2,
              "method" to "tools/call",
              "params" to mapOf("name" to "Read", "arguments" to mapOf("path" to "/etc/passwd")),
            ),
          ),
        ) {
          forwarded = true
          "{}"
        },
      )

    assertTrue(!forwarded)
    assertTrue(reply.contains("\"error\""))
  }

  @Test
  fun `a forwarded response over the frame limit is rejected by a real unix socket`() {
    val socketPath = Files.createTempFile("skillbill-evidence", ".sock")
    Files.delete(socketPath)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socketPath))
    val thread =
      Thread {
        server.use {
          it.accept().use { channel ->
            val reader = Channels.newInputStream(channel).bufferedReader()
            val writer = Channels.newOutputStream(channel).bufferedWriter()
            reader.readLine()
            writer.appendLine("x".repeat(GovernedReviewEvidenceContracts.RESPONSE_FRAME_BYTES + 1))
            writer.flush()
          }
        }
      }
    thread.isDaemon = true
    thread.start()
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GovernedReviewEvidenceConnection.connect(socketPath, "token").use { connection ->
          connection.forward("{}")
        }
      }
    assertEquals(GovernedReviewFailureCode.EVIDENCE_TRANSPORT, error.code)
    thread.join(5_000)
  }

  @Test
  fun `a handshake refusal is reported as a transport error`() {
    val socketPath = Files.createTempFile("skillbill-evidence-refusal", ".sock")
    Files.delete(socketPath)
    val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socketPath))
    val thread =
      Thread {
        server.use {
          it.accept().use { channel ->
            Channels.newInputStream(channel).bufferedReader().readLine()
          }
        }
      }
    thread.start()
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        GovernedReviewEvidenceConnection.connect(socketPath, "token")
      }
    assertEquals(GovernedReviewFailureCode.EVIDENCE_TRANSPORT, error.code)
    thread.join()
  }
}
