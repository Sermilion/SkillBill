package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The tour-only pull request card: header, title, branch range and the six-check grid. */
@Composable
internal fun TourPrCard(state: TourFinishState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(12.dp)
  Column(
    modifier.tourTarget(TourTarget.PrCard)
      .border(1.dp, if (state.prReady) colors.stActive.copy(alpha = .45f) else colors.border, shape)
      .background(colors.bgRaised, shape)
      .padding(horizontal = 18.dp, vertical = 16.dp),
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides colors.stActive) { SbIcon(LucideIcons.branch, SbIconSize.Sm) }
      BasicText(TourFixture.prNumber, style = type.monoSm.copy(color = colors.fgMuted))
      Spacer(Modifier.weight(1f))
      BasicText(
        if (state.prReady) TourFixture.prStatusReady else TourFixture.prStatusRunning,
        Modifier
          .background(if (state.prReady) colors.stActive.copy(alpha = .15f) else colors.bgActive, RoundedCornerShape(10.dp))
          .padding(horizontal = 8.dp, vertical = 2.dp),
        style = type.tXs.copy(
          color = if (state.prReady) colors.stActive else colors.fgMuted,
          fontWeight = FontWeight.SemiBold,
        ),
      )
    }
    BasicText(TourFixture.goalTitle, Modifier.padding(top = 10.dp), style = type.tMd.copy(color = colors.fg))
    Row(Modifier.padding(top = 6.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      BasicText(TourFixture.prRange, style = type.monoSm.copy(color = colors.fgFaint))
      BasicText("+156", style = type.tXs.copy(color = colors.stActive))
      BasicText("−8", style = type.tXs.copy(color = colors.stFailed))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      TourFixture.checkNames.chunked(2).forEachIndexed { rowIndex, pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          pair.forEachIndexed { i, name -> TourCheckCell(name, state.checks[rowIndex * 2 + i], Modifier.weight(1f)) }
        }
      }
    }
  }
}

@Composable
private fun TourCheckCell(name: String, kind: StatusKind, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  Row(
    modifier.height(26.dp).background(colors.bgInset, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(7.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    StatusIcon(kind, null, modifier = Modifier.size(12.dp))
    BasicText(name, style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted, fontSize = 12.sp))
  }
}

private data class SparkParticle(
  val angle: Float,
  val distance: Float,
  val span: Float,
  val size: Float,
  val spin: Float,
  val colorIndex: Int,
)

/**
 * A one-shot confetti burst out of the card's center; [burst] generations restart it. Seeded per
 * particle so repeats look identical; draws nothing under reduced motion. The canvas registers itself
 * as [TourTarget.SparkLayer] so the card's frame-space rect can be translated into canvas space.
 */
@Composable
internal fun TourSparkBurst(target: TourTarget, burst: Int, modifier: Modifier = Modifier) {
  val reduced = LocalReducedMotion.current
  if (reduced || burst <= 0) return
  val colors = SkillBillTheme.colors
  val targets = LocalTourTargets.current ?: return
  val rect = targets.rects[target] ?: return
  val sparkColors = listOf(colors.accent, colors.stActive, colors.fg)
  val progress = remember(burst) { Animatable(0f) }
  LaunchedEffect(burst) { progress.animateTo(1f, tween(1700, easing = CubicBezierEasing(.16f, 1f, .3f, 1f))) }
  val particles = remember(burst) {
    List(22) { i ->
      val rand = Random(burst * 1000 + i)
      SparkParticle(
        angle = rand.nextFloat() * (2f * PI).toFloat(),
        distance = 120f + rand.nextFloat() * 160f,
        span = (1100f + rand.nextFloat() * 600f) / 1700f,
        size = 5f + rand.nextFloat() * 3f,
        spin = rand.nextFloat() * 540f,
        colorIndex = if (i % 3 == 1) 1 else if (i % 5 == 2) 2 else 0,
      )
    }
  }
  Canvas(modifier.fillMaxSize().tourTarget(TourTarget.SparkLayer)) {
    val layer = targets.rects[TourTarget.SparkLayer] ?: return@Canvas
    val center = rect.center - layer.topLeft
    val unit = 1.dp.toPx()
    particles.forEach { p ->
      val t = (progress.value / p.span).coerceIn(0f, 1f)
      if (t in 0.01f..0.99f) {
        val size = p.size * unit * (1f - .6f * t)
        withTransform({
          translate(
            center.x + cos(p.angle) * p.distance * t * unit,
            center.y + sin(p.angle) * p.distance * .7f * t * unit + 40f * t * unit,
          )
          rotate(p.spin * t, pivot = Offset.Zero)
        }) {
          drawRect(sparkColors[p.colorIndex], Offset(-size / 2f, -size / 2f), Size(size, size), alpha = 1f - t)
        }
      }
    }
  }
}
