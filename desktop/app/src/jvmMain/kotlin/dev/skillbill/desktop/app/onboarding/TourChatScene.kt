package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.dataColor
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

internal enum class StartCardStage { Hidden, Shown, Starting }

internal data class TourChatState(
  val chrome: StageChrome = StageChrome(TourNav.NewTask),
  val overlay: StageOverlay = StageOverlay(),
  val homeVisible: Boolean = true,
  val messages: List<TourChatLine> = emptyList(),
  val composerText: String = "",
  val composerFocused: Boolean = false,
  val sendReady: Boolean = false,
  val sendPressed: Boolean = false,
  val startCard: StartCardStage = StartCardStage.Hidden,
)

/**
 * The step 02 timeline. Typing emits one cue per character at a fixed cadence — no jitter — so the
 * final state stays a pure fold of the cue list and lands the same under reduced motion.
 */
internal fun tourChatTimeline(): TourTimeline<TourChatState> {
  val cues = mutableListOf<TourCue<TourChatState>>()
  fun at(ms: Int, apply: (TourChatState) -> TourChatState) { cues += TourCue(ms, apply) }
  fun type(start: Int, text: String, cps: Int): Int {
    val step = 1000 / cps
    text.indices.forEach { i -> at(start + i * step) { it.copy(composerText = text.substring(0, i + 1), sendReady = true) } }
    return start + text.length * step + step
  }
  fun send(t: Int): Int {
    at(t) { it.copy(sendPressed = true) }
    at(t + 140) {
      it.copy(
        composerText = "", sendReady = false, sendPressed = false, homeVisible = false,
        messages = it.messages + TourChatLine.User(it.composerText),
      )
    }
    return t + 140
  }
  fun reply(t: Int, text: String, think: Int): Int {
    at(t) { it.copy(messages = it.messages + TourChatLine.AgentThinking) }
    at(t + think) { it.copy(messages = it.messages.dropLast(1) + TourChatLine.Agent(text)) }
    return t + think
  }
  at(350) { it.copy(composerFocused = true) }
  val e1 = type(500, TourFixture.chatMessage1, 44)
  val s1 = send(e1 + 350)
  val r1 = reply(s1 + 400, TourFixture.chatReply1, 1100)
  val e2 = type(r1 + 900, TourFixture.chatMessage2, 30)
  val s2 = send(e2 + 300)
  val r2 = reply(s2 + 400, TourFixture.chatReply2, 1200)
  at(r2 + 500) { it.copy(startCard = StartCardStage.Shown) }
  at(r2 + 1100) {
    it.copy(overlay = it.overlay.copy(spotlight = Spotlight(TourTarget.StartCard, TourFixture.tipYourCall, TipSide.Bottom, pad = 5.dp)))
  }
  at(r2 + 1300) { it.copy(overlay = it.overlay.copy(cursor = DemoCursor(TourTarget.StartGoalButton))) }
  at(r2 + 3000) { it.copy(overlay = it.overlay.copy(cursor = it.overlay.cursor?.copy(presses = 1))) }
  at(r2 + 3160) { it.copy(startCard = StartCardStage.Starting, overlay = it.overlay.copy(spotlight = null)) }
  at(r2 + 3510) {
    it.copy(overlay = it.overlay.copy(cursor = null, flight = CardFlight(TourTarget.StartCard, TourTarget.NavWork, 1)))
  }
  at(r2 + 4460) {
    it.copy(
      chrome = it.chrome.copy(activeNav = TourNav.Work),
      overlay = it.overlay.copy(flight = null, spotlight = Spotlight(TourTarget.NavWork, TourFixture.tipStarted, pad = 3.dp)),
    )
  }
  return TourTimeline(TourChatState(), cues, r2 + 5000)
}

@Composable
internal fun TourChatScene(step: Int, replayToken: Int, onEnd: () -> Unit) {
  val timeline = remember { tourChatTimeline() }
  val state by rememberTourTimeline(step, replayToken, timeline, LocalReducedMotion.current, onEnd)
  TourStageFrame(
    state.chrome,
    state.overlay,
    flightContent = {
      StatusIcon(StatusKind.Planning, null)
      BasicText(TourFixture.goalTitle, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
    },
  ) {
    TourNewTaskPanel(state)
  }
}

@Composable
internal fun TourUserBubble(text: String) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(12.dp, 12.dp, 4.dp, 12.dp)
  Box(Modifier.fillMaxWidth()) {
    BasicText(
      text,
      Modifier.align(Alignment.CenterEnd).widthIn(max = 530.dp)
        .background(colors.bgRaised, shape)
        .border(1.dp, colors.border, shape)
        .padding(horizontal = 12.dp, vertical = 9.dp),
      style = SkillBillTheme.type.tBase.copy(color = colors.fg),
    )
  }
}

@Composable
internal fun TourAgentMessage(text: String) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(
      Modifier.size(22.dp).background(colors.dataColor(TourFixture.claude.dataSlot), CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      BasicText("C", style = type.tXs.copy(color = colors.bgFrame, fontWeight = FontWeight.Bold))
    }
    BasicText(
      agentText(text, type.mono.fontFamily),
      Modifier.weight(1f),
      style = type.tBase.copy(color = colors.fg2),
    )
  }
}

internal fun agentText(text: String, mono: FontFamily?) = buildAnnotatedString {
  var i = 0
  while (i < text.length) {
    val open = text.indexOf('`', i)
    if (open < 0) { append(text.substring(i)); break }
    append(text.substring(i, open))
    val close = text.indexOf('`', open + 1)
    if (close < 0) { append(text.substring(open)); break }
    withStyle(SpanStyle(fontFamily = mono)) { append(text.substring(open + 1, close)) }
    i = close + 1
  }
}

@Composable
internal fun TourThinkingDots() {
  val colors = SkillBillTheme.colors
  val reduced = LocalReducedMotion.current
  Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    repeat(3) { index ->
      val dy = if (reduced) {
        0f
      } else {
        rememberInfiniteTransition(label = "dots").animateFloat(
          0f, -4f,
          infiniteRepeatable(tween(1000), RepeatMode.Reverse, initialStartOffset = StartOffset(index * 150)),
          label = "dot-$index",
        ).value
      }
      Box(Modifier.size(5.dp).offset(y = dy.dp).background(colors.fgFaint, CircleShape))
    }
  }
}
