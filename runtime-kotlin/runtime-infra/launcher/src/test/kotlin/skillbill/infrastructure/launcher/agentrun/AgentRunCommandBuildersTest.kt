package skillbill.infrastructure.launcher.agentrun

import skillbill.config.model.PhaseCompactionDirective
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.process.launch.AgentRunIdlePolicy
import skillbill.infrastructure.skills.install.mcp.McpConfigFormat
import skillbill.install.model.MODEL_DIRECTIVE_CAPABLE_AGENTS
import skillbill.install.model.SupportedAgent
import skillbill.ports.review.model.ReviewLaunchIsolationStrategy
import skillbill.review.context.model.launch.ReviewConversationIsolation
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRunCommandBuildersTest {
  @Test
  fun `a compaction directive reaches the claude launch environment`() {
    val command =
      ClaudeAgentRunCommandBuilder().build(
        request(compaction = PhaseCompactionDirective(windowTokens = 400_000, triggerPct = 70)),
      )

    assertEquals("400000", command.environment["CLAUDE_CODE_AUTO_COMPACT_WINDOW"])
    assertEquals("70", command.environment["CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"])
  }

  @Test
  fun `claude launches keep every tool call in the foreground`() {
    val command = ClaudeAgentRunCommandBuilder().build(request())

    assertEquals("1", command.environment["CLAUDE_CODE_DISABLE_BACKGROUND_TASKS"])
  }

  @Test
  fun `no compaction directive leaves the launch environment untouched`() {
    val command = ClaudeAgentRunCommandBuilder().build(request())

    assertFalse(command.environment.containsKey("CLAUDE_CODE_AUTO_COMPACT_WINDOW"))
    assertFalse(command.environment.containsKey("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE"))
  }

  @Test
  fun `structured output decoders ignore provider usage dimensions`() {
    val claude =
      AgentRunOutputDecoder.CLAUDE_JSON.decode(
        """{"result":"done","usage":{"input_tokens":100,"cache_read_input_tokens":40,""" +
          """"output_tokens":20,"total_tokens":120}}""",
      )
    assertEquals("done", claude.text)
    val codex =
      AgentRunOutputDecoder.CODEX_JSONL.decode(
        """
        {"item":{"text":"finding"}}
        {"usage":{"input_tokens":90,"cached_input_tokens":30,"output_tokens":10,"reasoning_tokens":5,"total_tokens":100}}
        """.trimIndent(),
      )
    assertEquals("finding", codex.text)
    assertEquals("", AgentRunOutputDecoder.CLAUDE_JSON.decode("""{"usage":{"total_tokens":7}}""").text)
    assertEquals("", AgentRunOutputDecoder.CODEX_JSONL.decode("""{"usage":{"total_tokens":7}}""").text)
  }

  @Test
  fun `streamed claude output decodes identically to the buffered form`() {
    val buffered =
      """{"type":"result","subtype":"success","result":"PLAN-OK","usage":""" +
        """{"input_tokens":2,"cache_read_input_tokens":17931,"output_tokens":6,"total_tokens":120}}"""
    val streamed =
      listOf(
        """{"type":"system","subtype":"init","session_id":"s-1","cwd":"/repo"}""",
        """{"type":"assistant","message":{"model":"claude-opus-4-8"},"session_id":"s-1"}""",
        """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed"},"session_id":"s-1"}""",
        buffered,
      ).joinToString("\n")

    val fromBuffered = AgentRunOutputDecoder.CLAUDE_JSON.decode(buffered)
    val fromStream = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(streamed)

    assertEquals(fromBuffered.text, fromStream.text)
    assertEquals("PLAN-OK", fromStream.text)
  }

  @Test
  fun `stream decoding ignores non-terminal events and tolerates a truncated stream`() {
    val noResultEvent = """{"type":"assistant","message":{"model":"claude-opus-4-8"}}"""
    val undecodable = AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(noResultEvent)
    assertEquals("", undecodable.text, "a stream cut before its terminal event carries no answer")
    assertEquals(noResultEvent, undecodable.rawOutputPreview)

    val laterResultWins =
      listOf(
        """{"type":"result","subtype":"success","result":"stale"}""",
        """{"type":"assistant","message":{"content":"result"}}""",
        """{"type":"result","subtype":"success","result":"fresh"}""",
      ).joinToString("\n")
    assertEquals("fresh", AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(laterResultWins).text)

    val partialLine = """{"type":"result","subtype":"success","result":"kept"}""" + "\n{\"type\":\"resu"
    assertEquals("kept", AgentRunOutputDecoder.CLAUDE_STREAM_JSON.decode(partialLine).text)
  }

  @Test
  fun `a liveness-streamed claude launch swaps output format decoder and idle policy`() {
    val builder = ClaudeAgentRunCommandBuilder()

    val streamed = builder.build(request().copy(streamOutputForLiveness = true))

    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "stream-json",
        "--verbose",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      streamed.command,
    )
    assertEquals(AgentRunOutputDecoder.CLAUDE_STREAM_JSON, streamed.outputDecoder)
    assertEquals(AgentRunIdlePolicy.OUTPUT_EXTENDED, streamed.idlePolicy)

    val buffered = builder.build(request())
    assertEquals(null, buffered.outputDecoder, "a non-streamed launch keeps the builder default decoder")
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, buffered.idlePolicy)
  }

  @Test
  fun `a transport-streamed claude launch preserves durable-progress-only idle policy`() {
    val streamed = ClaudeAgentRunCommandBuilder().build(request().copy(streamProviderOutput = true))

    assertContains(streamed.command, "stream-json")
    assertEquals(AgentRunOutputDecoder.CLAUDE_STREAM_JSON, streamed.outputDecoder)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, streamed.idlePolicy)
  }

  @Test
  fun `builders that cannot stream fall back to process liveness instead of a watchdog they cannot satisfy`() {
    val streaming = request().copy(streamOutputForLiveness = true)

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, CodexAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, JunieAgentRunCommandBuilder().build(streaming).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(request()).idlePolicy)
  }

  @Test
  fun `claude renders exact commands for each directive shape`() {
    val builder = ClaudeAgentRunCommandBuilder(emptyMap())

    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--model",
        "claude-opus",
        "--effort",
        "high",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request(model = "claude-opus", effort = "high")).command,
    )
    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--model",
        "claude-opus",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request(model = "claude-opus")).command,
    )
    assertEquals(
      listOf(
        "claude",
        "--print",
        "--output-format",
        "json",
        "--dangerously-skip-permissions",
        "--add-dir",
        "/tmp/skillbill-agent-run",
      ),
      builder.build(request()).command,
    )
  }

  @Test
  fun `claude directive naming an anthropic model falls back to the parent model on a non-anthropic endpoint`() {
    val builder =
      ClaudeAgentRunCommandBuilder(
        mapOf(
          "ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
          "ANTHROPIC_MODEL" to "deepseek-v4-flash",
        ),
      )

    val command = builder.build(request(model = "claude-opus-5", effort = "high")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("deepseek-v4-flash", command[modelIndex + 1])
  }

  @Test
  fun `claude directive naming a served deepseek model passes through unchanged`() {
    val builder =
      ClaudeAgentRunCommandBuilder(
        mapOf("ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic"),
      )

    val command = builder.build(request(model = "deepseek-v4-flash")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("deepseek-v4-flash", command[modelIndex + 1])
  }

  @Test
  fun `claude directive passes through on the official anthropic endpoint`() {
    val builder =
      ClaudeAgentRunCommandBuilder(
        mapOf("ANTHROPIC_BASE_URL" to "https://api.anthropic.com"),
      )

    val command = builder.build(request(model = "claude-opus-5")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("claude-opus-5", command[modelIndex + 1])
  }

  @Test
  fun `claude directive without a parent model keeps the directive on a non-anthropic endpoint`() {
    val builder =
      ClaudeAgentRunCommandBuilder(
        mapOf("ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic"),
      )

    val command = builder.build(request(model = "claude-opus-5")).command

    val modelIndex = command.indexOf("--model")
    assertTrue(modelIndex >= 0)
    assertEquals("claude-opus-5", command[modelIndex + 1])
  }

  @Test
  fun `claude without a model directive stays flag free so the child inherits the parent model`() {
    val builder =
      ClaudeAgentRunCommandBuilder(
        mapOf(
          "ANTHROPIC_BASE_URL" to "https://api.deepseek.com/anthropic",
          "ANTHROPIC_MODEL" to "deepseek-v4-flash",
        ),
      )

    val command = builder.build(request()).command

    assertFalse(command.contains("--model"))
  }

  @Test
  fun `codex renders exact commands for each directive shape`() {
    val builder = CodexAgentRunCommandBuilder()
    assertTrue(builder.build(request()).inheritEnvironment)

    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
        "--model",
        "gpt-sol",
        "--config",
        "model_reasoning_effort=xhigh",
      ),
      builder.build(request(model = "gpt-sol", effort = "xhigh")).command,
    )
    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
        "--model",
        "gpt-sol",
      ),
      builder.build(request(model = "gpt-sol")).command,
    )
    assertEquals(
      listOf(
        "codex",
        "exec",
        "--json",
        "--cd",
        "/tmp/skillbill-agent-run",
        "--dangerously-bypass-approvals-and-sandbox",
        "--config",
        "shell_environment_policy.inherit=all",
      ),
      builder.build(request()).command,
    )
  }

  @Test
  fun `all feature task codex phases remain writable`() {
    FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.forEach { phase ->
      val command =
        CodexAgentRunCommandBuilder().build(
          request().copy(promptOverride = "Phase: $phase"),
        ).command

      assertTrue(command.contains("--dangerously-bypass-approvals-and-sandbox"))
      assertFalse(command.contains("read-only"))
    }
  }

  @Test
  fun `directive capable agents have builders that render their directives`() {
    val builders =
      listOf(
        ClaudeAgentRunCommandBuilder(),
        CodexAgentRunCommandBuilder(),
        CursorAgentRunCommandBuilder(),
      )

    assertEquals(MODEL_DIRECTIVE_CAPABLE_AGENTS, builders.map { it.agent }.toSet())
    builders.forEach { builder ->
      val command = builder.build(request(model = "model", effort = "high")).command
      assertTrue(command.contains("--model"))
      assertTrue(
        command.any { arg ->
          arg == "high" ||
            arg == "model_reasoning_effort=high" ||
            arg.contains("[effort=high]")
        },
        "builder ${builder.agent.id} must render effort for a model+effort directive: $command",
      )
    }
  }

  @Test
  fun `junie rejects model and effort directives`() {
    assertFailsWith<IllegalArgumentException> {
      JunieAgentRunCommandBuilder().build(request(model = "model", effort = "high"))
    }
  }

  @Test
  fun `junie accepts a directive free request`() {
    val command =
      JunieAgentRunCommandBuilder().build(
        request(),
      ).command

    assertEquals(SupportedAgent.JUNIE.id, command.first())
  }

  @Test
  fun `a governed claude launch is a fresh isolated process naming its worker`() {
    val isolated =
      request().copy(
        reviewEvidenceBroker = NoOpReviewEvidenceBroker,
        reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
        nativeReviewWorkerName = "bill-kotlin-code-review-architecture",
      )
    val builder = ClaudeAgentRunCommandBuilder()
    val command = builder.build(isolated)

    assertEquals(ReviewLaunchIsolationStrategy.FRESH_PROCESS, builder.reviewIsolation)
    assertEquals(ReviewConversationIsolation.FRESH, command.conversationIsolation)
    assertEquals(
      "bill-kotlin-code-review-architecture",
      command.command[command.command.indexOf("--agent") + 1],
    )
    assertFalse(
      CodexAgentRunCommandBuilder().build(request()).command.contains("--skip-git-repo-check"),
    )
  }

  @Test
  fun `a provider missing a governed launch capability fails with a typed error and no command`() {
    val governed = governedReviewRequest()
    val capable =
      GovernedReviewLaunchCapability(
        governedOnlyTooling = true,
        mcpIsolation = true,
        configFormat = McpConfigFormat.TOML,
      )
    listOf(
      capable.copy(governedOnlyTooling = false) to "governed-only tooling",
      capable.copy(mcpIsolation = false) to "MCP isolation",
    ).forEach { (capability, missing) ->
      val builder = CodexAgentRunCommandBuilder(governedReviewLaunchCapability = capability)
      val error = assertFailsWith<SkillBillRuntimeException> { builder.build(governed) }
      assertEquals(GovernedReviewFailureCode.LAUNCH_CAPABILITY, error.code)
      assertTrue(error.message.orEmpty().contains("Agent 'codex'"))
      assertTrue(error.message.orEmpty().contains("missing capability '$missing'"))
    }
  }

  @Test
  fun `governed review launches carry isolated mcp config and governed-only tools`() {
    val governed = governedReviewRequest(nativeReviewWorkerName = "bill-code-review-inline")
    listOf(
      ClaudeAgentRunCommandBuilder(),
      CodexAgentRunCommandBuilder(),
      CursorAgentRunCommandBuilder(),
      JunieAgentRunCommandBuilder(),
    ).forEach { builder ->
      assertGovernedReviewLaunch(builder, governed)
    }
  }

  @Test
  fun `governed claude review names only governed operations and no raw filesystem tool`() {
    val isolated =
      request().copy(
        reviewEvidenceBroker = NoOpReviewEvidenceBroker,
        reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
      )
    val governedOperations = GovernedReviewEvidenceContracts.OPERATIONS

    listOf(false to emptyList<String>(), true to listOf("Agent", "Task")).forEach { (fanOut, delegation) ->
      val command = ClaudeAgentRunCommandBuilder().build(isolated.copy(reviewFanOut = fanOut)).command
      assertEquals(
        StubReviewEvidenceEndpoint.descriptor.mcpConfigPath.toString(),
        command[command.indexOf("--mcp-config") + 1],
      )
      assertTrue(command.contains("--strict-mcp-config"))
      val tools = command[command.indexOf("--tools") + 1].split(",")
      assertEquals(
        delegation + governedOperations.map { "mcp__skill-bill-review-evidence__$it" },
        tools,
      )
      assertTrue(tools.none { it in setOf("Read", "Grep", "Glob", "Bash") })
    }
  }

  @Test
  fun `claude builder forwards provider passthrough keys when review evidence broker is present`() {
    val isolated =
      request().copy(
        reviewEvidenceBroker = NoOpReviewEvidenceBroker,
        reviewEvidenceEndpoint = StubReviewEvidenceEndpoint,
        nativeReviewWorkerName = "bill-kotlin-code-review-architecture",
      )
    val command = ClaudeAgentRunCommandBuilder().build(isolated)
    assertTrue(
      command.environmentPassthroughKeys.contains("ANTHROPIC_API_KEY"),
      "Isolated claude review worker must forward Anthropic direct-auth key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("CLAUDE_CODE_USE_BEDROCK"),
      "Isolated claude review worker must forward Bedrock provider selection key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("CLAUDE_CODE_USE_VERTEX"),
      "Isolated claude review worker must forward Vertex provider selection key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("AWS_ACCESS_KEY_ID"),
      "Isolated claude review worker must forward Bedrock credential key",
    )
    assertTrue(
      command.environmentPassthroughKeys.contains("GOOGLE_APPLICATION_CREDENTIALS"),
      "Isolated claude review worker must forward Vertex credential key",
    )
  }

  @Test
  fun `claude builder sets empty passthrough keys when review evidence broker is absent`() {
    val command = ClaudeAgentRunCommandBuilder().build(request())
    assertTrue(
      command.environmentPassthroughKeys.isEmpty(),
      "Non-isolated claude run must not passthrough provider keys",
    )
  }

  @Test
  fun `codex read-only phase requires durable progress without changing other providers`() {
    val readOnly = request().copy(readOnlyPhase = true)

    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, ClaudeAgentRunCommandBuilder().build(readOnly).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(readOnly).idlePolicy)
    assertEquals(AgentRunIdlePolicy.HEARTBEAT_EXTENDED, JunieAgentRunCommandBuilder().build(readOnly).idlePolicy)
  }

  @Test
  fun `read-only phase does not override output-extended when claude streams`() {
    val streamingReadOnly = request().copy(streamOutputForLiveness = true, readOnlyPhase = true)

    assertEquals(
      AgentRunIdlePolicy.OUTPUT_EXTENDED,
      ClaudeAgentRunCommandBuilder().build(streamingReadOnly).idlePolicy,
      "streaming takes precedence over read-only for claude",
    )
  }

  @Test
  fun `non-read-only non-streaming phases keep db-progress-only idle policy`() {
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, ClaudeAgentRunCommandBuilder().build(request()).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, CodexAgentRunCommandBuilder().build(request()).idlePolicy)
    assertEquals(AgentRunIdlePolicy.DB_PROGRESS_ONLY, JunieAgentRunCommandBuilder().build(request()).idlePolicy)
  }
}
