package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

/**
 * One agent's chat: [run] runs one turn per collection, reports failures as [ChatEvent.Failed] instead of throwing, and
 * stops the agent process when the collection is cancelled before the turn ends.
 */
interface ChatTransport {
  val agentId: String

  /**
   * Whether this agent's chat answers permission requests through an operator approval card. A transport that cannot is
   * refused before it starts, since its process would otherwise run with permission checks skipped.
   */
  val approvalCapable: Boolean get() = false

  val approvalUnavailableReason: String get() =
    "$agentId cannot host operator approval cards, so its chat was not started. Select a supported provider explicitly."

  fun run(request: ChatTurnRequest): Flow<ChatEvent>

  /** Replies the agent starts on its own between turns; a transport that runs one process per turn has none. */
  fun unprompted(sessionId: String): Flow<ChatEvent> = emptyFlow()

  /** Stops the process kept for [sessionId] between turns; a transport that keeps none has nothing to stop. */
  fun end(sessionId: String) = Unit
}

/** Registers each agent's transport; another agent adds one more provider here. */
@ContributesTo(AppScope::class)
interface ChatTransportBindings {
  @Provides
  @IntoSet
  fun claude(transport: ClaudeChatTransport): ChatTransport = transport

  @Provides
  @IntoSet
  fun codex(transport: CodexChatTransport): ChatTransport = transport

  @Provides
  @IntoSet
  fun cursor(transport: CursorChatTransport): ChatTransport = transport

  @Provides
  @IntoSet
  fun junie(transport: JunieChatTransport): ChatTransport = transport
}
