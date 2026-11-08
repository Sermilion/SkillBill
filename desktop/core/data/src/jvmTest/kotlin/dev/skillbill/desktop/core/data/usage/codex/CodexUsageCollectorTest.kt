package dev.skillbill.desktop.core.data.usage.codex

import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.data.chat.BoundedProcessStart
import dev.skillbill.desktop.core.data.chat.BoundedProtocolConnection
import dev.skillbill.desktop.core.data.chat.BoundedProtocolInput
import dev.skillbill.desktop.core.data.chat.BoundedProtocolLauncher
import dev.skillbill.desktop.core.data.chat.ChatCommand
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CodexUsageCollectorTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `reader sends only the initialized read-only capability request sequence and closes`() = runTest {
    val connection = ScriptedConnection()
    val collector = collector(connection)

    val result = collector.read(context())

    assertEquals(UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified)), result)
    assertEquals(listOf("initialize", "initialized", "account/read", "account/rateLimits/read"), connection.methods)
    assertTrue(connection.closed)
    val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "codex" else directory.resolve("codex").toString()
    assertEquals(listOf(executable, "app-server", "--listen", "stdio://"), connection.command?.argv)
    assertEquals(directory.toString(), connection.command?.workingDirectory)
    assertEquals(context().environment, connection.command?.environment)
    assertEquals("false", connection.requests.single { it.getValue("method").jsonPrimitive.content == "account/read" }
      .getValue("params").jsonObject.getValue("refreshToken").jsonPrimitive.content)
  }

  @Test
  fun `wrong response identity fails initialization and closes without account reads`() = runTest {
    val connection = ScriptedConnection { _, _ -> """{"id":99,"result":{}}""" }

    val result = collector(connection).read(context())

    assertIs<UsageResult.Unsupported>(result)
    assertEquals(listOf("initialize"), connection.methods)
    assertTrue(connection.closed)
  }

  @Test
  fun `malformed account evidence cannot become a sign-in request or a supported account`() = runTest {
    for (account in listOf("{}", """{"account":"null"}""", """{"account":{"type":"future"}}""")) {
      val connection = ScriptedConnection { method, id ->
        if (method == "account/read") """{"id":$id,"result":$account}""" else defaultResponse(method, id)
      }

      assertIs<UsageResult.Unsupported>(collector(connection).read(context()))
      assertEquals(listOf("initialize", "initialized", "account/read"), connection.methods)
      assertTrue(connection.closed)
    }
  }

  @Test
  fun `hung initialization and account reads reach their deadlines and close`() = runTest {
    for ((hungMethod, deadlineMillis) in listOf("initialize" to 15_000L, "account/read" to 10_000L)) {
      val connection = ScriptedConnection { method, id -> if (method == hungMethod) null else defaultResponse(method, id) }
      val collector = collector(connection)
      val read = async { collector.read(context()) }
      runCurrent()
      assertFalse(read.isCompleted)

      advanceTimeBy(deadlineMillis)
      runCurrent()

      assertIs<UsageResult.TransientFailure>(read.await())
      assertTrue(read.isCompleted)
      assertTrue(connection.closed)
    }
  }

  @Test
  fun `account change invalidates the acquisition before any further account read`() = runTest {
    val events = mutableListOf<CodexAcquisitionEvent>()
    val connection = ScriptedConnection { method, id ->
      if (method == "account/read") """{"method":"account/updated","params":{}}""" else defaultResponse(method, id)
    }
    val context = context().copy(emit = { events += it })

    assertIs<UsageResult.Unavailable>(collector(connection).read(context))
    assertEquals(listOf<CodexAcquisitionEvent>(CodexAcquisitionEvent.AccountInvalidated(context.reservationId)), events)
    assertEquals(listOf("initialize", "initialized", "account/read"), connection.methods)
    assertTrue(connection.closed)
  }

  private fun context() = CodexAcquisitionContext(
    UsageProfileLookup(UsageProvider.Codex, "/selected/codex", 31),
    mapOf("CODEX_HOME" to "/selected/codex", "PATH" to directory.toString()),
    "reservation",
  ) { }

  private fun TestScope.collector(connection: ScriptedConnection): CodexUsageCollector {
    directory.resolve("codex").toFile().apply {
      writeText("")
      setExecutable(true)
    }
    val dispatcher = StandardTestDispatcher(testScheduler)
    val launcher = BoundedProtocolLauncher { supplied ->
      connection.command = supplied
      BoundedProcessStart.Started(connection)
    }
    val host = HostEnvironment(emptyMap(), directory.toString(), "/repo")
    return CodexUsageCollector(host, launcher, RecordingDiagnostics(), AppDispatchers(dispatcher, dispatcher, dispatcher))
  }

  private class ScriptedConnection(private val respond: (String, String) -> String? = ::defaultResponse) : BoundedProtocolConnection {
    private val incoming = Channel<BoundedProtocolInput>(32)
    val methods = mutableListOf<String>()
    val requests = mutableListOf<JsonObject>()
    var command: ChatCommand? = null
    var closed = false

    override suspend fun sendLine(line: String): Boolean {
      val message = Json.parseToJsonElement(line).jsonObject
      requests += message
      val method = message.getValue("method").jsonPrimitive.content
      methods += method
      if ("id" !in message) return true
      val id = message.getValue("id").jsonPrimitive.content
      respond(method, id)?.let { incoming.send(BoundedProtocolInput.Line(it)) }
      return true
    }

    override suspend fun receive(): BoundedProtocolInput = incoming.receive()
    override fun beginResponseBudget() = Unit
    override fun endResponseBudget() = Unit
    override suspend fun close() {
      closed = true
      incoming.close()
    }
  }

  private companion object {
    fun defaultResponse(method: String, id: String): String {
      val result = when (method) {
        "initialize" -> "{}"
        "account/read" -> """{"account":{"type":"chatgpt"},"requiresOpenaiAuth":false}"""
        "account/rateLimits/read" -> """{"rateLimitsByLimitId":{}}"""
        else -> error("Unexpected method $method")
      }
      return """{"jsonrpc":"2.0","id":$id,"result":$result}"""
    }
  }
}
