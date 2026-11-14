package skillbill.infrastructure.skills.scaffold.rendering

import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionDirective
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionKind
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentSource
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentBundle
import skillbill.infrastructure.skills.nativeagent.composition.renderNativeAgentSource

private const val DEFAULT_CODEX_MAX_THREADS = 6

internal fun renderNativeAgentSourceStub(
  name: String,
  parentSkill: String,
): String {
  return renderNativeAgentSource(
    NativeAgentSource(
      name = name,
      description = "TODO: one-line description for the $name specialist subagent. Fill in before shipping.",
      body = nativeAgentStubBody(name, parentSkill),
    ),
  )
}

internal fun renderNativeAgentBundleStubs(
  names: List<String>,
  descriptions: Map<String, String> = emptyMap(),
  bodyNames: Set<String> = emptySet(),
  parentSkill: String = "",
): String =
  renderNativeAgentBundle(
    names.map { name ->
      NativeAgentSource(
        name = name,
        description =
          descriptions[name]
            ?: "TODO: one-line description for the $name specialist subagent. Fill in before shipping.",
        body = if (name in bodyNames) nativeAgentStubBody(name, parentSkill) else "",
        composition =
          if (name in bodyNames) {
            null
          } else {
            NativeAgentCompositionDirective(NativeAgentCompositionKind.GovernedContent)
          },
      )
    },
  )

internal fun renderSubagentSpawnRuntimeNotes(
  orchestratorName: String,
  specialists: List<String>,
): String {
  if (specialists.isEmpty()) {
    return ""
  }
  val paragraphs =
    mutableListOf(
      "### Subagent Spawn Runtime Notes",
      subagentResolutionParagraph(orchestratorName, specialists),
      claudeSpawnParagraph(orchestratorName, specialists),
      codexSpawnParagraph(),
    )

  if (specialists.size > DEFAULT_CODEX_MAX_THREADS) {
    paragraphs +=
      "**On Codex (wave limit).** Selected fan-out exceeds Codex's `agents.max_threads = 6` default; run " +
      "waves of at most 6 specialists, with the orchestrator merging wave outputs before final review. This " +
      "limit is Codex-specific and constrains no other runtime's launch rules."
  }
  paragraphs += cursorSpawnParagraph(orchestratorName, specialists)
  return paragraphs.joinToString("\n\n")
}

private fun subagentResolutionParagraph(
  orchestratorName: String,
  specialists: List<String>,
): String {
  val exampleSpecialist = specialists.first()
  return "Specialist spawn instructions in this orchestrator are runtime-neutral. Each phrase such as " +
    "\"spawn the `$exampleSpecialist` subagent\" maps to the native subagent surface of the host runtime " +
    "(parent skill: `$orchestratorName`). The per-runtime paragraphs below are imperative: the orchestrator " +
    "MUST follow the paragraph that matches the runtime it is running in, including how it collects the " +
    "subagent's `RESULT:` JSON. Picking a different mechanism (for example, emitting a natural-language " +
    "\"please spawn\" message instead of calling the listed tool) causes the workflow to stall because no " +
    "subagent actually runs and no `RESULT:` is ever returned."
}

private fun claudeSpawnParagraph(
  orchestratorName: String,
  specialists: List<String>,
): String {
  val exampleSpecialist = specialists.first()
  return "**On Claude (Claude Code, Anthropic SDK agents).** The orchestrator MUST invoke the built-in `Agent` " +
    "tool with `subagent_type` set to the matching specialist name (for example, `subagent_type: " +
    "\"$exampleSpecialist\"` for that role in `$orchestratorName`) and pass the per-phase briefing as the " +
    "tool's `prompt`. Call the tool in the **foreground** (the default — do NOT pass " +
    "`run_in_background: true`). The `Agent` tool blocks until the subagent finishes and returns the " +
    "subagent's final text message as the tool result; the orchestrator parses the `RESULT:` JSON directly " +
    "from that returned message in the same turn. Do NOT sleep, poll, ping, re-call, or otherwise check on " +
    "the subagent — Claude's `Agent` tool surfaces completion synchronously and any polling loop is both " +
    "unnecessary and explicitly discouraged by the tool contract. If the tool returns and the message is " +
    "missing a `RESULT:` block or contains malformed JSON, fall through to the `RESULT:` block parsing " +
    "tolerance rules (best-effort recovery, then exactly one corrective re-spawn via another foreground " +
    "`Agent` call) instead of waiting."
}

private fun codexSpawnParagraph(): String =
  "**On Codex.** The spawn is a natural-language directive in the orchestrator's turn. Codex resolves the " +
    "subagent by `name` against the installed TOML files in the Codex user agents directory (with the legacy " +
    "Agents agents fallback), respecting `agents.max_threads` and `agents.max_depth`. Because Codex runs " +
    "subagents asynchronously, the orchestrator MUST poll for completion between turns before consuming the " +
    "subagent's `RESULT:` block — do not proceed to the next phase until the subagent has visibly finished " +
    "and its `RESULT:` JSON is available in the conversation."

private fun cursorSpawnParagraph(
  orchestratorName: String,
  specialists: List<String>,
): String {
  val exampleSpecialist = specialists.first()
  return "**On Cursor.** The orchestrator MUST launch each selected specialist by naming its installed Cursor " +
    "subagent — the matching file under `~/.cursor/agents/` or project `.cursor/agents/` (project scope wins " +
    "on a name conflict) — via `/name` or an explicit \"use the `<name>` subagent\" instruction (for " +
    "example, \"use the `$exampleSpecialist` subagent\" for that role in `$orchestratorName`), and pass the " +
    "per-phase briefing as that subagent's prompt. Request every selected lane in one instruction that names " +
    "every selected lane so they launch in parallel, not one-at-a-time. Do NOT compose a rubric inline, do " +
    "NOT substitute `generalPurpose` or any other built-in when a matching installed specialist exists, and " +
    "do NOT answer a lane's rubric in the parent context — answering it there is an inline review and must " +
    "be reported as such. The installed native agent's embedded governed rubric is authoritative. Collect " +
    "each lane's structured findings (including any `RESULT:` JSON) from the returned subagent message; " +
    "Cursor lane identity is the routed area plus the assignment digest from the launch plan. A lane that " +
    "launches but returns no structured findings report attributable to that lane's identity is a failed " +
    "lane: report it explicitly and never absorb it into the merged output as covered. Two conditions stop " +
    "the whole delegated run instead of failing one lane: when no installed agent matching a selected lane " +
    "exists, and when the specialist files are installed but this session cannot launch them by name. The " +
    "second is typical of the Cursor `agent` CLI, whose `Task` tool exposes only built-in types such as " +
    "`generalPurpose`; the Cursor IDE agent chat can launch installed specialists by name. In both cases " +
    "stop and report that delegated review is required for this scope but unavailable here, naming the " +
    "re-run path (the Cursor IDE agent chat) or `mode:inline`. Do not silently downgrade to inline, " +
    "substitute a built-in worker, or claim delegated coverage from the parent context."
}

private fun nativeAgentStubBody(
  name: String,
  parentSkill: String,
): String =
  buildString {
    appendLine("# ${titleCaseSpecialist(name)} Specialist")
    appendLine()
    appendLine("TODO: replace this placeholder with the specialist briefing.")
    appendLine()
    appendLine(
      "Specialist contract pointer: see specialist-contract.md for the F-XXX Risk Register format used by " +
        "this orchestrator's review specialists (parent skill: $parentSkill).",
    )
  }.trimEnd()

private fun titleCaseSpecialist(name: String): String =
  name.split("-").filter { it.isNotBlank() }.joinToString(" ") { part ->
    part.replaceFirstChar { first -> if (first.isLowerCase()) first.titlecase() else first.toString() }
  }
