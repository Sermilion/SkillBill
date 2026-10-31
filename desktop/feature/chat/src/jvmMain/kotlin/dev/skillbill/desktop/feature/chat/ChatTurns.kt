package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatFailure
import dev.skillbill.desktop.core.domain.model.ChatPermissionMode
import dev.skillbill.desktop.core.domain.model.ChatScope
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.DetailSections
import dev.skillbill.desktop.core.domain.model.DetailState
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.ExecutionChatRepository
import dev.skillbill.desktop.core.domain.repository.WorkDetailRepository
import kotlinx.coroutines.flow.firstOrNull

internal const val GONE_NOTICE = "This execution is no longer in the open database."
internal const val UNREADABLE_NOTICE = "Execution detail could not be read; the agent sees the summary only."
internal const val NO_AGENT_NOTICE = "No agent is recorded for this execution and no default agent is configured."
internal const val ACT_PREFIX =
  "The operator paused this execution so you can act on it. When this turn ends the app resumes it; " +
    "do not resume, relaunch or continue it yourself."

internal const val ALREADY_RUNNING_NOTICE = "The execution is already running again; the app did not relaunch it."

/** The one mode rule: a running or active execution is only read; anything else may be acted on. */
internal fun modeFor(header: WorkItem): ChatPermissionMode =
  if (header.running || header.lifecycle == Lifecycle.Active) ChatPermissionMode.ReadOnly else ChatPermissionMode.Act

/** One fresh read of the execution: [detail] and [runOptions] are null when the detail could not be read. */
internal class ExecutionRead(val header: WorkItem, val detail: Detail?, val runOptions: RunOptions?)

internal fun ControlOutcome.problemDetail(): String? = when (this) {
  is ControlOutcome.Refused -> detail ?: rejection.name
  is ControlOutcome.Failed -> when (val problem = problem) {
    is ReadProblem.Runtime -> problem.message
    is ReadProblem.Storage -> problem.failure.name
  }
  else -> null
}

/** Reads, snapshots and streams the turns of [ChatWorkspace]; every call runs inside a session job. */
internal class ChatTurns(private val chat: ExecutionChatRepository, private val details: WorkDetailRepository) {
  /** The first settled read; a flow that ends without one counts as unreadable. */
  suspend fun freshRead(item: WorkItem): DetailState? = details.observe(item).firstOrNull { it.sections !is DetailSections.Pending }

  /** Turns [state] into a read, or null after marking the session gone. Every read refreshes the session's header and mode. */
  fun accept(session: ChatSession, state: DetailState?): ExecutionRead? {
    val sections = state?.sections
    if (sections is DetailSections.Gone) {
      markGone(session)
      return null
    }
    val header = state?.header ?: session.item
    session.item = header
    session.modeState.value = modeFor(header)
    if (sections is DetailSections.Loaded) return ExecutionRead(header, sections.detail, sections.runOptions)
    session.append(ChatMessage.Notice(UNREADABLE_NOTICE))
    return ExecutionRead(header, null, null)
  }

  fun markGone(session: ChatSession) {
    if (session.goneState.value) return
    session.goneState.value = true
    session.append(ChatMessage.Notice(GONE_NOTICE))
  }

  /** Sends [text] with the full snapshot on a first turn and the diff since the last delivered one after that. */
  suspend fun turn(session: ChatSession, read: ExecutionRead, mode: ChatPermissionMode, text: String, prefix: String? = null) {
    val agent = session.agentState.value ?: resolve(session, read) ?: run {
      session.append(ChatMessage.Notice(NO_AGENT_NOTICE))
      return
    }
    val current = snapshot(read.header, read.detail)
    val delivered = session.delivered
    val context = if (session.started && delivered != null) diff(delivered, current).render() else current.render()
    stream(session, agent, read.header, mode, listOfNotNull(prefix, context, text).joinToString("\n\n"), current)
  }

  private suspend fun resolve(session: ChatSession, read: ExecutionRead): ChatAgent? =
    chat.resolveAgent(read.header, read.detail, read.runOptions)?.also {
      session.agentState.value = it
      session.agentSupportedState.value = chat.supports(it.agentId)
    }

  // The snapshot counts as delivered, and the CLI session as created, only once the turn produced output.
  private suspend fun stream(
    session: ChatSession,
    agent: ChatAgent,
    header: WorkItem,
    mode: ChatPermissionMode,
    prompt: String,
    sent: ExecutionSnapshot,
  ) {
    val scope = ChatScope(header.id, header.kind, header.issueKey, header.title, header.repositoryRoot)
    val request = ChatTurnRequest(session.sessionId, !session.started, agent, scope, mode, prompt)
    var delivered = false
    try {
      chat.send(request).collect { event ->
        if (event !is ChatEvent.Failed && !delivered) {
          delivered = true
          session.delivered = sent
          session.started = true
        }
        when (event) {
          is ChatEvent.TextDelta -> session.appendDelta(event.text)
          is ChatEvent.ToolUse -> session.appendTool(event.call)
          is ChatEvent.ToolResult -> session.recordToolResult(event)
          is ChatEvent.SuggestedActions -> session.suggestActions(event.actions)
          ChatEvent.Completed -> session.endStreaming()
          is ChatEvent.Failed -> {
            session.endStreaming()
            session.append(
              if (event.failure == ChatFailure.AgentUnsupported) {
                ChatMessage.Notice("Chat is not available for agent ${event.detail ?: agent.agentId} yet.")
              } else {
                ChatMessage.Error(event.failure, event.detail ?: event.failure.name)
              },
            )
          }
        }
      }
    } finally {
      session.endStreaming()
    }
  }
}
