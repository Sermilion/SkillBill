package dev.skillbill.desktop.core.data.chat

import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.domain.model.ChatEvent
import dev.skillbill.desktop.core.domain.model.ChatTurnRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringPolicy
import dev.skillbill.desktop.core.domain.model.ChatSteeringRequest
import dev.skillbill.desktop.core.domain.model.ChatSteeringResult
import dev.skillbill.desktop.core.domain.model.ChatSteeringUpdate
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

  /** Whether this agent's chat answers permission requests through an operator approval card. */
  val approvalCapable: Boolean get() = false

  val steeringPolicy: ChatSteeringPolicy get() = ChatSteeringPolicy.LegacySendNow

  suspend fun steer(request: ChatSteeringRequest): ChatSteeringResult =
    ChatSteeringResult.Unsupported(request.deliveryId, "Live delivery is unavailable")

  fun steeringUpdates(sessionId: String): Flow<ChatSteeringUpdate> = emptyFlow()

  fun settleSteering(sessionId: String, deliveryId: String): ChatSteeringResult =
    ChatSteeringResult.DeliveryUncertain(deliveryId, "Delivery could not be confirmed; the follow-up remains retained")

  fun retireSteering(sessionId: String, deliveryId: String) = Unit

  fun run(request: ChatTurnRequest): Flow<ChatEvent>

  /** Replies the agent starts on its own between turns; a transport that runs one process per turn has none. */
  fun unprompted(sessionId: String): Flow<ChatEvent> = emptyFlow()

  /** Stops the process kept for [sessionId] between turns; a transport that keeps none has nothing to stop. */
  fun end(sessionId: String) = Unit

  /** Waits until cleanup started by [end] has published any retained steering responses. */
  suspend fun awaitEnd(sessionId: String) = Unit
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
  fun devin(transport: DevinChatTransport): ChatTransport = transport
}
