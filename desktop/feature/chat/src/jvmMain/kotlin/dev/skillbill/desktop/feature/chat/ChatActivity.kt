package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.domain.model.ChatAgent
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.CommandChip
import dev.skillbill.desktop.core.designsystem.component.LocalUiTicker
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbTag
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val DETAIL_LIMIT = 160

// A command that returns within this time never gets a chip, so quick reads and checks do not flash one.
private val FOREGROUND_CHIP_DELAY = 3.seconds

/** A shell command the chat is waiting on: [name] is short enough for a chip and [detail] says what it runs on hover. */
internal data class WaitingCommand(val id: String, val name: String, val detail: String, val startedAt: Instant)

/** The pill above a chat's input that says what the agent is doing now, with a chip for the command it is waiting on. */
@Composable
internal fun ChatActivityPill(thread: ChatThread, gone: Boolean = false) {
  val busy by thread.busy.collectAsState()
  val agent by thread.agent.collectAsState()
  val transcript by thread.transcript.collectAsState()
  val background by thread.backgroundCommands.collectAsState()
  val ticker = LocalUiTicker.current
  val ticking = !gone && runningCommands(transcript).isNotEmpty()
  if (ticking) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  val waiting = if (gone) emptyList() else waitingCommands(background, transcript, ticker.now.value)
  Row(
    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SbLabel(
      chatActivityStatus(busy, agent, gone, transcript),
      style = if (busy) SbLabelStyle.Dot(SkillBillTheme.colors.fg2) else SbLabelStyle.Plain,
    )
    waiting.firstOrNull()?.let { CommandChip(it.name, it.startedAt, tooltip = it.detail) }
    if (waiting.size > 1) SbTag("+${waiting.size - 1}", tooltip = waiting.drop(1).joinToString("\n") { it.name })
  }
}

internal fun chatActivityStatus(busy: Boolean, agent: ChatAgent?, gone: Boolean, transcript: List<ChatMessage>): String {
  if (gone) return "Execution unavailable"
  if (!busy) return "Ready"
  if (agent == null) return "Preparing chat..."
  val reply = (transcript.lastOrNull() as? ChatMessage.Agent)?.takeIf { it.streaming } ?: return "Thinking..."
  val active = reply.tools.lastOrNull { it.result == null }
  if (active != null) return toolActivityLabel(active.call)
  return "Working..."
}

/**
 * What the chat waits on at [now], oldest first: commands left running in the background, then shell commands of the
 * streaming reply that have run for a few seconds without a result.
 */
internal fun waitingCommands(background: List<WaitingCommand>, transcript: List<ChatMessage>, now: Instant): List<WaitingCommand> =
  background + runningCommands(transcript).mapNotNull { entry ->
    entry.startedAt?.takeIf { now - it >= FOREGROUND_CHIP_DELAY }?.let { entry.waitingCommand(entry.call.id, it) }
  }

internal fun ChatToolEntry.waitingCommand(id: String, startedAt: Instant): WaitingCommand? {
  val command = command ?: return null
  val detail = call.summary?.takeIf { it.isNotBlank() && it != command } ?: command
  return WaitingCommand(id, commandName(command), if (detail.length > DETAIL_LIMIT) detail.take(DETAIL_LIMIT - 1) + "…" else detail, startedAt)
}

private fun runningCommands(transcript: List<ChatMessage>): List<ChatToolEntry> =
  transcript.filterIsInstance<ChatMessage.Agent>().filter { it.streaming }.flatMap { it.tools }
    .filter { it.result == null && it.command != null }
