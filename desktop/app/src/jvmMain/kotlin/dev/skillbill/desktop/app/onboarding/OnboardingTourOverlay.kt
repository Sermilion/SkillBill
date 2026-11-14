package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.Kbd
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.icon.BrandMark
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.ui.pane.MOD_KEY

@Composable
fun OnboardingTourOverlay(tour: OnboardingTourState, onStartFirstTask: () -> Unit) {
  val colors = SkillBillTheme.colors
  val focus = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val reduced = LocalReducedMotion.current
  var timelineEnded by remember(tour.step, tour.replayToken) { mutableStateOf(false) }
  BoxWithConstraints(
    Modifier.fillMaxSize().background(colors.bgFrame)
      .focusRequester(focus).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
      .pointerInput(Unit) {
        awaitPointerEventScope {
          while (true) awaitPointerEvent(PointerEventPass.Final).changes.forEach { it.consume() }
        }
      },
  ) {
    val frame = tourFrameLayout(maxWidth, maxHeight)
    LaunchedEffect(tour.step, frame.compact) {
      focusManager.clearFocus(force = true)
      focus.requestFocus()
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
      TourTopBar(tour, Modifier.fillMaxWidth())
      if (tour.step == OnboardingTourState.WELCOME_STEP || tour.step == OnboardingTourState.LAST_STEP) {
        TourHero(tour, onStartFirstTask, Modifier.weight(1f).fillMaxWidth(), reduced, frame.compact)
      } else {
        Column(
          Modifier.fillMaxWidth().weight(1f),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            val scale = tourFrameScale(maxWidth, maxHeight)
            ScaledTourFrame(scale) {
              when (tour.step) {
                1 -> TourSidebarScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                2 -> TourChatScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                3 -> TourWorkScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                4 -> TourRunDetailScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                5 -> TourControlScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                6 -> TourReviewScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                7 -> TourFinishScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                8 -> TourCommandsScene(tour.step, tour.replayToken, onEnd = { timelineEnded = true })
                else -> EmptyStagedFrame(Modifier.fillMaxSize())
              }
            }
          }
          Spacer(Modifier.height(12.dp))
          TourCaption(tour, frame.compact, timelineEnded)
        }
      }
    }
  }
}

@Composable
private fun TourTopBar(tour: OnboardingTourState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  Column(modifier.height(TOP_BAR_HEIGHT).background(colors.bgFrame)) {
    Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
      BrandMark(18.dp)
      Spacer(Modifier.width(8.dp))
      BasicText("Sona", style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
      Spacer(Modifier.width(12.dp))
      BasicText("First-run tour", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      Spacer(Modifier.weight(1f))
      if (tour.step in 1..8) {
        SbButton("Skip tour", tour::skip, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
    }
    val progress by animateFloatAsState(
      tour.step / OnboardingTourState.LAST_STEP.toFloat(),
      if (LocalReducedMotion.current) snap() else tween(800, easing = CubicBezierEasing(.65f, 0f, .25f, 1f)),
      label = "tour-progress",
    )
    Box(Modifier.fillMaxWidth().height(2.dp).background(colors.border.copy(alpha = .4f))) {
      Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(colors.accent))
    }
  }
}

@Composable
private fun EmptyStagedFrame(modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(14.dp)
  Box(
    modifier.layeredShadow(SkillBillTheme.shadows.popover, shape)
      .background(colors.bgWindow, shape).border(1.dp, colors.border, shape),
  )
}

@Composable
private fun TourCaption(tour: OnboardingTourState, compact: Boolean, ended: Boolean) {
  val colors = SkillBillTheme.colors
  val step = tour.step.coerceIn(1, 8)
  val nextLabel = if (step == 8) "Finish" else "Next"
  val copy = TOUR_STEP_COPY[step - 1]
  val shape = RoundedCornerShape(14.dp)
  Column(
    Modifier.widthIn(max = 800.dp).fillMaxWidth(.92f).layeredShadow(SkillBillTheme.shadows.popover, shape)
      .background(colors.bgOverlay.copy(alpha = .74f), shape).border(1.dp, colors.border, shape)
      .padding(horizontal = 18.dp, vertical = if (compact) 8.dp else 14.dp),
  ) {
    if (compact) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BasicText("%02d / 08".format(step), style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint))
        BasicText(copy.title, Modifier.weight(1f), style = SkillBillTheme.type.tSmMedium.copy(color = colors.fg))
        SbButton("Back", tour::back, size = SbControlSize.Small)
        TourNextButton(tour, nextLabel, ended)
      }
    } else {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
              BasicText("%02d / 08".format(step), style = SkillBillTheme.type.monoSm.copy(color = colors.fgFaint))
              BasicText(copy.title, style = SkillBillTheme.type.tMd.copy(color = colors.fg))
            }
            BasicText(copy.body, style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted))
          }
          Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              (1..8).forEach { index ->
                Box(
                  Modifier.size(width = if (index == step) 20.dp else 6.dp, height = 6.dp).background(
                    when { index == step -> colors.accent; index < step -> colors.fgFaint; else -> colors.borderStrong }, CircleShape,
                  ).clickable { tour.goTo(index) }.semantics {
                    contentDescription = "Step $index of 8"
                    role = Role.Button
                  },
                )
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
              SbButton(null, tour::replayStep, icon = LucideIcons.refresh, size = SbControlSize.Small, tooltip = "Replay this step")
              SbButton("Back", tour::back, size = SbControlSize.Small)
              TourNextButton(tour, nextLabel, ended)
            }
          }
        }
        if (step == 8) {
          Spacer(Modifier.height(6.dp))
          TourKeyGrid()
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourKeyGrid() {
  val colors = SkillBillTheme.colors
  FlowRow(
    Modifier.fillMaxWidth(),
    maxItemsInEachRow = 3,
    horizontalArrangement = Arrangement.spacedBy(14.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    TOUR_KEY_GRID.forEach { (keys, label) ->
      Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Kbd(keys)
        BasicText(label, style = SkillBillTheme.type.t2xs.copy(color = colors.fgFaint))
      }
    }
  }
}

@Composable
private fun TourNextButton(tour: OnboardingTourState, label: String, ended: Boolean) {
  val reduced = LocalReducedMotion.current
  val pulse = if (ended && !reduced) {
    rememberInfiniteTransition().animateFloat(
      0f, 1f, infiniteRepeatable(tween(800), repeatMode = RepeatMode.Reverse), label = "tour-next-pulse",
    ).value
  } else if (ended) 1f else 0f
  Box(
    Modifier.border(1.dp, if (ended) SkillBillTheme.colors.accentRing.copy(alpha = pulse) else Color.Transparent, RoundedCornerShape(7.dp))
      .padding(2.dp),
  ) {
    SbButton(label, tour::next, size = SbControlSize.Small, variant = SbButtonVariant.Primary)
  }
}

@Composable
private fun TourHero(tour: OnboardingTourState, onStartFirstTask: () -> Unit, modifier: Modifier, reduced: Boolean, compact: Boolean) {
  val welcome = tour.step == OnboardingTourState.WELCOME_STEP
  val colors = SkillBillTheme.colors
  val timeline = remember {
    TourTimeline(HeroMarkState(0, false), listOf(
      TourCue(150) { it.copy(bands = 1) }, TourCue(320) { it.copy(bands = 2) },
      TourCue(490) { it.copy(bands = 3) }, TourCue(1300) { it.copy(ripples = true) },
    ), 1390)
  }
  val mark by rememberTourTimeline(tour.step, tour.replayToken, timeline, reduced) {}
  Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
    PingMark(mark.bands, mark.ripples)
    Spacer(Modifier.height(22.dp))
    BasicText(if (welcome) "Welcome to Sona" else "That's the loop", style = SkillBillTheme.type.t2xl.copy(fontSize = if (compact) 26.sp else 40.sp, color = colors.fg, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    Spacer(Modifier.height(10.dp))
    BasicText(
      if (welcome) "Sona takes your coding agent from plan to pull request: plan, implement, review, validate, ship. You decide what gets built and when it runs."
      else "Describe it, start it, steer it, review it, ship it. The rest of Sona builds on those five moves.",
      Modifier.widthIn(max = 520.dp), style = SkillBillTheme.type.tLg.copy(color = colors.fgMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
    )
    Spacer(Modifier.height(24.dp))
    if (welcome) {
      SbButton("Take the tour · 90 sec", { tour.goTo(1) }, variant = SbButtonVariant.Primary)
      Spacer(Modifier.height(8.dp))
      SbButton("Skip for now", tour::skip, variant = SbButtonVariant.Ghost)
      Spacer(Modifier.height(16.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Kbd("← →")
        BasicText("to move,", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
        Kbd("Esc")
        BasicText("to skip", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      }
    } else {
      SbButton("Start your first task", onStartFirstTask, variant = SbButtonVariant.Primary)
      Spacer(Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SbButton("Replay tour", { tour.goTo(1) }, variant = SbButtonVariant.Ghost)
        SbButton("Close", tour::close, variant = SbButtonVariant.Ghost)
      }
      Spacer(Modifier.height(14.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("Open the tour again from the command menu:", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
        Kbd("$MOD_KEY K")
        BasicText("then \"Show tour\"", style = SkillBillTheme.type.tXs.copy(color = colors.fgFaint))
      }
    }
  }
}

private data class HeroMarkState(val bands: Int, val ripples: Boolean)
