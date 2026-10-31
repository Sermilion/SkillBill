package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import kotlinx.coroutines.flow.Flow
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

/**
 * One agent's chat: [run] runs one turn per collection, reports failures as [ChatEvent.Failed] instead of throwing, and
 * stops the agent process when the collection is cancelled.
 */
interface ChatTransport {
  val agentId: String

  fun run(request: ChatTurnRequest): Flow<ChatEvent>
}

/** Registers each agent's transport; another agent adds one more provider here. */
@ContributesTo(AppScope::class)
interface ChatTransportBindings {
  @Provides
  @IntoSet
  fun claude(transport: ClaudeChatTransport): ChatTransport = transport
}
