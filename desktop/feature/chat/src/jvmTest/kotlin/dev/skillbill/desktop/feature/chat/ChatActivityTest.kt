package dev.skillbill.desktop.feature.chat

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatToolCall
import dev.skillbill.desktop.core.testing.workItem
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class ChatActivityTest {
  private val agent = ChatAgent("codex", null, null)

  @Test
  fun `tool events show file edits and commands while the completed turn always returns to ready`() {
    val session = ChatSession(workItem("SKILL-1"), "session", Job())
    fun status(busy: Boolean = true) = chatActivityStatus(busy, agent, false, session.transcript.value)

    assertEquals("Thinking...", status())
    session.appendDelta("I'll implement this.")
    assertEquals("Working...", status())
    val read = ChatToolCall("read", "Read", "View.kt", "Read the view")
    session.appendTool(read)
    assertEquals("Reading View.kt", status())
    session.recordToolResult(ChatEvent.ToolResult(read.id, "contents", false))
    val edit = ChatToolCall("edit", "functions.apply_patch", "patch", "Update the view")
    session.appendTool(edit)
    assertEquals("Editing files...", status())
    session.recordToolResult(ChatEvent.ToolResult(edit.id, "done", false))
    assertEquals("Working...", status())
    val command = ChatToolCall("check", "shell", "./gradlew check", "Check the change")
    session.appendTool(command)
    assertEquals("Running command...", status())
    session.recordToolResult(ChatEvent.ToolResult(command.id, "passed", false))
    assertEquals("Working...", status())
    session.endStreaming()
    assertEquals("Ready", status(busy = false))
  }

  @Test
  fun `failed edits and promises in a finished reply never imply implementation is still running`() {
    val call = ChatToolCall("patch", "mcp__tools__apply_patch", "patch", null)
    val failed = ChatToolEntry(call, ChatEvent.ToolResult(call.id, "rejected", true))
    val reply = ChatMessage.Agent("I haven't added the button.", streaming = true, tools = listOf(failed))
    assertEquals("Working...", chatActivityStatus(true, agent, false, listOf(reply)))
    assertEquals("Ready", chatActivityStatus(false, agent, false, listOf(reply)))
  }

  @Test
  fun `activity uses structured tool arguments and names unknown MCP tools`() {
    fun label(name: String, input: String) = toolActivityLabel(ChatToolCall("tool", name, input, null))

    assertEquals("Reading ChatActivity.kt", label("Read", """{"file_path":"/repo/ChatActivity.kt"}"""))
    assertEquals("Editing View.kt", label("Edit", """{"file_path":"C:\\repo\\View.kt"}"""))
    assertEquals("Searching for chatActivityStatus", label("Grep", """{"pattern":"chatActivityStatus"}"""))
    assertEquals("Running command...", label("functions.exec_command", """{"cmd":"./gradlew check"}"""))
    assertEquals("Calling codegraph MCP", label("mcp__codegraph__codegraph_explore", "{}"))
    assertEquals("Editing View.kt", label("functions.apply_patch", "*** Begin Patch\n*** Update File: /repo/View.kt\n@@\n-old\n+new"))
    assertEquals("Reading files...", label("Read", "{incomplete"))
    assertEquals("Searching...", label("search", "{}"))
    assertEquals("Running command...", label("functions.exec", "const results = await tools.exec_command(...)"))
    val longQuery = label("search", """{"query":"${"x".repeat(200)}"}""")
    assertEquals("Searching for " + "x".repeat(71) + "...", longQuery)
  }

  @Test
  fun `shell commands and wrapper scripts never become pill text even when summaries repeat them`() {
    val commands = listOf(
      "./gradlew check",
      "bash -lc 'cd /repo && ./gradlew check --stacktrace'",
      "git status --short && " + "echo details; ".repeat(50),
      "python3 - <<'PY'\nprint('checking repository')\nPY",
      "const results = await tools.exec_command({cmd: 'git status'});",
    )
    for (name in listOf("Bash", "shell", "functions.exec", "functions.exec_command", "run", "command_execution")) {
      for (command in commands) {
        for (input in listOf(command, JsonObject(mapOf("cmd" to JsonPrimitive(command))).toString())) {
          val call = ChatToolCall("shell", name, input, command)
          val reply = ChatMessage.Agent("", streaming = true, tools = listOf(ChatToolEntry(call)))
          assertEquals("Running command...", chatActivityStatus(true, agent, false, listOf(reply)))
        }
      }
    }
    assertEquals("Using tools...", toolActivityLabel(ChatToolCall("unknown", commands[1], "", commands[1])))
    assertEquals("Searching...", toolActivityLabel(ChatToolCall("search", "search", "{}", commands[1])))
    assertEquals("Searching...", toolActivityLabel(ChatToolCall("search", "search", """{"query":"ls; cat file"}""", null)))
    assertEquals("Reading files...", toolActivityLabel(ChatToolCall("read", "Read", "{broken\ncommand", commands[1])))
  }

  @Test
  fun `service calls and explicit waits describe activity without exposing their payloads`() {
    val mcp = ChatToolCall("mcp", "mcp__skill-bill__work_status", """{"command":"long internal command"}""", "work_status")
    val wait = ChatToolCall("wait", "functions.wait", """{"cell_id":"private-id"}""", null)
    val reply = ChatMessage.Agent("", streaming = true, tools = listOf(ChatToolEntry(mcp)))
    assertEquals("Calling skill-bill MCP", chatActivityStatus(true, agent, false, listOf(reply)))
    val waiting = reply.copy(tools = listOf(ChatToolEntry(wait)))
    assertEquals("Waiting for response...", chatActivityStatus(true, agent, false, listOf(waiting)))
    assertEquals("Ready", chatActivityStatus(false, agent, false, listOf(waiting)))
    assertEquals("Reading MainActivity.kt", toolActivityLabel(ChatToolCall("read", "Read", """{"file_path":"/repo/MainActivity.kt"}""", null)))
  }

  // Codex and Cursor end their process with the turn, so what they wait on is a command still running in the reply.
  @Test
  fun `a running shell command gets a chip only after a few seconds and loses it with its result`() {
    val clock = MutableClock()
    val thread = ChatThread(clock)
    fun waiting() = waitingCommands(thread.backgroundCommands.value, thread.transcript.value, clock.now).map { it.name }

    val build = ChatToolCall("build", "Terminal", """{"rawInput":{"command":"cd desktop && ./gradlew :feature:chat:jvmTest --tests '*Chat*'"}}""", null)
    thread.show(agent, ChatEvent.ToolUse(build))
    thread.show(agent, ChatEvent.ToolUse(ChatToolCall("read", "Read", """{"file_path":"/repo/View.kt"}""", null)))
    assertEquals(emptyList(), waiting())
    clock.now += 3.seconds
    assertEquals(listOf("gradlew jvmTest"), waiting())
    thread.show(agent, ChatEvent.ToolUse(build.copy(input = """{"status":"in_progress"}""")))
    assertEquals(listOf("gradlew jvmTest"), waiting())
    thread.show(agent, ChatEvent.ToolResult(build.id, "BUILD SUCCESSFUL", false))
    assertEquals(emptyList(), waiting())

    thread.show(agent, ChatEvent.ToolUse(ChatToolCall("serve", "shell", "/bin/zsh -lc 'npm run dev'", null)))
    clock.now += 5.seconds
    assertEquals(listOf("npm dev"), waiting())
    thread.show(agent, ChatEvent.Failed(ChatFailure.ProcessFailed, "stopped"))
    assertEquals(emptyList(), waiting())
  }

  // Claude keeps its process after the turn, so a background command outlives the reply that started it.
  @Test
  fun `background commands stay after the turn until each ends or the process stops`() {
    val clock = MutableClock()
    val thread = ChatThread(clock)
    fun waiting() = waitingCommands(thread.backgroundCommands.value, thread.transcript.value, clock.now)

    val build = ChatToolCall("build", "Bash", """{"command":"./gradlew check","run_in_background":true}""", "Run the desktop checks")
    thread.show(agent, ChatEvent.ToolUse(build))
    thread.show(agent, ChatEvent.ToolUse(ChatToolCall("tail", "Bash", """{"command":"tail -f /tmp/app.log"}""", "tail -f /tmp/app.log")))
    thread.show(agent, ChatEvent.BackgroundTaskStarted("b1", "build"))
    thread.show(agent, ChatEvent.BackgroundTaskStarted("b2", "tail"))
    thread.show(agent, ChatEvent.BackgroundTaskStarted("b1", "build"))
    thread.show(agent, ChatEvent.ToolResult("build", "Command running in background with ID: b1.", false))
    thread.show(agent, ChatEvent.ToolResult("tail", "Command running in background with ID: b2.", false))
    thread.show(agent, ChatEvent.Completed)

    assertEquals(
      listOf(WaitingCommand("b1", "gradlew check", "Run the desktop checks", clock.now), WaitingCommand("b2", "tail app.log", "tail -f /tmp/app.log", clock.now)),
      waiting(),
    )
    assertEquals("Ready", chatActivityStatus(false, agent, false, thread.transcript.value))
    thread.show(agent, ChatEvent.BackgroundTaskEnded("b1"))
    assertEquals(listOf("b2"), waiting().map { it.id })
    thread.clearBackgroundCommands()
    assertEquals(emptyList(), waiting())
  }

  @Test
  fun `the pill shows the waiting command beside ready and drops it when the command ends`() = runComposeUiTest {
    val thread = ChatThread(Clock.System)
    thread.show(agent, ChatEvent.ToolUse(ChatToolCall("build", "Bash", """{"command":"./gradlew assembleDebug"}""", null)))
    thread.show(agent, ChatEvent.Completed)
    setContent { SkillBillTheme(reducedMotion = true) { ChatActivityPill(thread) } }

    onNodeWithText("Ready").assertExists()
    onNodeWithText("gradlew assembleDebug").assertDoesNotExist()
    runOnIdle { thread.show(agent, ChatEvent.BackgroundTaskStarted("b1", "build")) }
    onNodeWithText("Ready").assertExists()
    onNodeWithText("gradlew assembleDebug").assertExists()
    runOnIdle { thread.show(agent, ChatEvent.BackgroundTaskEnded("b1")) }
    onNodeWithText("gradlew assembleDebug").assertDoesNotExist()
  }

  @Test
  fun `command names stay short and name the step that matters`() {
    val names = mapOf(
      "./gradlew :feature:chat:jvmTest --tests '*ChatActivityTest' --continue" to "gradlew jvmTest",
      "cd desktop && ./gradlew check 2>&1 | tail -50" to "gradlew check",
      "/bin/zsh -lc 'cd /repo && make -j8'" to "make",
      "JAVA_HOME=/opt/jdk timeout 600 xcodebuild -scheme App build" to "xcodebuild App",
      "export CI=1; pnpm run build:prod" to "pnpm build:prod",
      "python3 -m pytest -q tests/" to "python3 pytest",
      "sleep 4 && echo done" to "sleep 4",
      "cargo test --workspace" to "cargo test",
      "docker compose -f infra/docker-compose.yml up --build" to "docker compose",
      "node scripts/a-really-long-generated-script-name-for-release.mjs" to "node",
      "cd /repo" to "command",
    )
    assertEquals(names, names.mapValues { (command, _) -> commandName(command) })
    assertEquals(true, commandName("./gradlew assembleInternalDebugAndroidTest").length <= 32)
  }

  @Test
  fun `each agent's shell call is read from where it puts the command`() {
    fun command(name: String, input: String) = shellCommand(ChatToolCall("call", name, input, null))

    assertEquals("./gradlew check", command("Bash", """{"command":"./gradlew check","run_in_background":true}"""))
    assertEquals("/bin/zsh -lc 'make'", command("shell", "/bin/zsh -lc 'make'"))
    assertEquals("./gradlew check", command("functions.exec_command", """{"cmd":"./gradlew check"}"""))
    assertEquals("./gradlew check", command("Terminal", """{"toolCallId":"t1","kind":"execute","rawInput":{"command":"./gradlew check"}}"""))
    assertEquals("./gradlew check", command("`./gradlew check`", """{"rawInput":{"command":["./gradlew","check"]}}"""))
    assertEquals(null, command("Read", "./gradlew"))
    assertEquals(null, command("mcp__tools__run_task", """{"command":"./gradlew check"}"""))
    assertEquals(null, command("Terminal", """{"toolCallId":"t1","status":"in_progress"}"""))
  }

  private class MutableClock(var now: Instant = Instant.fromEpochMilliseconds(1_000_000)) : Clock {
    override fun now(): Instant = now
  }

  @Test
  fun `finished text does not hide a concurrent tool and completed tools never stay active`() {
    val first = ChatToolCall("first", "Read", "First.kt", null)
    val second = ChatToolCall("second", "Read", "Second.kt", null)
    val reply = ChatMessage.Agent("Earlier commentary", streaming = true, tools = listOf(
      ChatToolEntry(first),
      ChatToolEntry(second, ChatEvent.ToolResult(second.id, "done", false)),
    ))
    assertEquals("Reading First.kt", chatActivityStatus(true, agent, false, listOf(reply)))
    val settled = reply.copy(tools = reply.tools.map { it.copy(result = ChatEvent.ToolResult(it.call.id, "done", false)) })
    assertEquals("Working...", chatActivityStatus(true, agent, false, listOf(settled)))
    assertEquals("Execution unavailable", chatActivityStatus(true, agent, true, listOf(reply)))
  }
}
