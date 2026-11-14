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
 * The step 02 timeline as manual beats: the first message typed, sent and answered; the second
 * exchange; the start card with its "your call" tip; the press; the flight to Work; the landed tip.
 */
internal fun tourChatTimeline(): TourTimeline<TourChatState> = TourTimeline(
  TourChatState(),
  listOf(
    TourBeat { it.copy(composerFocused = true, composerText = TourFixture.chatMessage1, sendReady = true) },
    TourBeat {
      it.copy(
        composerText = "", sendReady = false, homeVisible = false,
        messages = listOf(
          TourChatLine.User(TourFixture.chatMessage1),
          TourChatLine.Agent(TourFixture.chatReply1),
        ),
      )
    },
    TourBeat {
      it.copy(
        messages = it.messages + TourChatLine.User(TourFixture.chatMessage2) + TourChatLine.Agent(TourFixture.chatReply2),
      )
    },
    TourBeat {
      it.copy(
        startCard = StartCardStage.Shown,
        overlay = it.overlay.copy(
          spotlight = Spotlight(TourTarget.StartCard, TourFixture.tipYourCall, TipSide.Bottom, pad = 5.dp),
        ),
      )
    },
    TourBeat {
      it.copy(overlay = it.overlay.copy(cursor = DemoCursor(TourTarget.StartGoalButton, presses = 1)))
    },
    TourBeat {
      it.copy(
        startCard = StartCardStage.Starting,
        overlay = it.overlay.copy(
          spotlight = null, cursor = null,
          flight = CardFlight(TourTarget.StartCard, TourTarget.NavWork, 1),
        ),
      )
    },
    TourBeat {
      it.copy(
        chrome = it.chrome.copy(activeNav = TourNav.Work),
        overlay = it.overlay.copy(
          flight = null,
          spotlight = Spotlight(TourTarget.NavWork, TourFixture.tipStarted, pad = 3.dp),
        ),
      )
    },
  ),
)

@Composable
internal fun TourChatScene(step: Int, replayToken: Int, beat: Int) {
  val timeline = remember { tourChatTimeline() }
  val state = timeline.stateAt(beat)
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
