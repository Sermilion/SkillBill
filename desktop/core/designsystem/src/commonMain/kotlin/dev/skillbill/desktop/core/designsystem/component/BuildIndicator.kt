@file:OptIn(kotlin.time.ExperimentalTime::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbBuildMeta.Kind
import dev.skillbill.desktop.core.designsystem.component.SbBuildMeta.Plain
import dev.skillbill.desktop.core.designsystem.component.SbBuildMeta.Previous
import dev.skillbill.desktop.core.designsystem.composite.formatClockTime
import dev.skillbill.desktop.core.designsystem.detail.relativeAge
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.tabular
import kotlin.math.min
import kotlin.time.Instant

enum class SbBuildState { Running, Passed, Failed, Finished }

@Immutable
data class SbBuildPrevious(val state: SbBuildState, val durationMs: Long, val finishedAt: Instant)

@Immutable
data class SbBuild(
  val state: SbBuildState,
  val command: String,
  val shortCommand: String,
  val tool: String,
  val projectKind: String,
  val pid: Long,
  val startedAt: Instant,
  val elapsedMs: Long?,
  val elapsedAsOf: Instant?,
  val usualMs: Long?,
  val exitCode: Int?,
  val finishedAt: Instant?,
  val previous: SbBuildPrevious?,
) {
  internal fun elapsedAt(now: Instant): Long? {
    val elapsed = if (state == SbBuildState.Running) {
      if (elapsedMs != null && elapsedAsOf != null) elapsedMs + (now - elapsedAsOf).inWholeMilliseconds
      else (now - startedAt).inWholeMilliseconds
    } else {
      elapsedMs ?: finishedAt?.let { (it - startedAt).inWholeMilliseconds }
    }
    return elapsed?.coerceAtLeast(0)
  }

  internal fun isSlow(now: Instant): Boolean =
    state == SbBuildState.Running && usualMs != null && (elapsedAt(now) ?: 0) > usualMs

  internal fun tone(now: Instant): SbBuildTone = when {
    state == SbBuildState.Failed -> SbBuildTone.Failed
    isSlow(now) -> SbBuildTone.Slow
    else -> SbBuildTone.None
  }

  internal fun word(): String = when (state) {
    SbBuildState.Running -> "Building"
    SbBuildState.Passed -> "Build passed"
    SbBuildState.Failed -> "Build failed"
    SbBuildState.Finished -> "Build finished"
  }

  internal fun subLine(now: Instant): String? = when {
    state == SbBuildState.Running && usualMs == null -> "first run, no usual time yet"
    isSlow(now) -> "${formatActiveDuration((elapsedAt(now) ?: 0) - (usualMs ?: 0), true)} over usual"
    state == SbBuildState.Running -> "usually ${formatActiveDuration(usualMs, true)}"
    finishedAt != null -> "finished ${relativeAge(now - finishedAt)}"
    else -> null
  }

  internal fun barRatio(now: Instant): Float? {
    val usual = usualMs?.takeIf { it > 0 } ?: return null
    if (state != SbBuildState.Running) return null
    return min(1f, (elapsedAt(now) ?: 0).toFloat() / usual)
  }

  internal fun chipTooltip(now: Instant): String = when {
    state == SbBuildState.Failed && exitCode != null -> "$command exited $exitCode after ${formatActiveDuration(elapsedAt(now), true)}"
    state == SbBuildState.Failed -> "$command failed after ${formatActiveDuration(elapsedAt(now), true)}"
    else -> "$command · $projectKind" + (usualMs?.let { " · usually ${formatActiveDuration(it, true)}" } ?: "")
  }

  internal fun metaSegments(now: Instant, startedText: String): List<SbBuildMeta> = buildList {
    add(Kind(projectKind))
    add(Plain(tool))
    add(Plain("pid $pid"))
    add(Plain("started $startedText"))
    if (state != SbBuildState.Running && exitCode != null) add(Plain("exit $exitCode"))
    previous?.let {
      add(Previous(it.state.statusKind(), "${formatActiveDuration(it.durationMs, true)}, ${relativeAge(now - it.finishedAt)}"))
    }
  }
}

internal enum class SbBuildTone { None, Slow, Failed }

internal sealed interface SbBuildMeta {
  data class Kind(val text: String) : SbBuildMeta
  data class Plain(val text: String) : SbBuildMeta
  data class Previous(val kind: StatusKind, val text: String) : SbBuildMeta
}

private fun SbBuildState.statusKind(): StatusKind = when (this) {
  SbBuildState.Passed -> StatusKind.Completed
  SbBuildState.Failed -> StatusKind.Failed
  SbBuildState.Finished, SbBuildState.Running -> StatusKind.CompletedNoChange
}

@Composable
private fun BuildSpinner(size: Dp, stroke: Dp, slow: Boolean, description: String?) {
  val colors = SkillBillTheme.colors
  val reducedMotion = LocalReducedMotion.current
  val rotation = if (reducedMotion) 0f else rememberInfiniteTransition().animateFloat(
    0f,
    360f,
    infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
  ).value
  val arcColor = if (slow && !reducedMotion) colors.stBlocked else colors.stPlanning
  val semantics = if (description == null) Modifier.clearAndSetSemantics {} else Modifier.semantics { contentDescription = description }
  Canvas(semantics.size(size).graphicsLayer { rotationZ = rotation }) {
    drawArc(colors.borderStrong, 0f, 360f, false, style = Stroke(stroke.toPx()))
    drawArc(arcColor, -135f, if (reducedMotion) 180f else 90f, false, style = Stroke(stroke.toPx(), cap = StrokeCap.Butt))
  }
}

@Composable
private fun rememberBuildNow(ticking: Boolean): Instant {
  val ticker = LocalUiTicker.current
  if (ticking) DisposableEffect(ticker) { ticker.retain(); onDispose { ticker.release() } }
  return ticker.now.value
}

@Composable
fun BuildChip(build: SbBuild, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val now = rememberBuildNow(build.state == SbBuildState.Running)
  val tone = build.tone(now)
  val failed = tone == SbBuildTone.Failed
  val slow = tone == SbBuildTone.Slow
  val shape = SkillBillRadius.full
  val border = when {
    failed -> colors.stFailed.copy(alpha = .35f)
    slow -> colors.stBlocked.copy(alpha = .35f)
    else -> colors.border
  }
  Box(modifier) {
    SbTooltip(build.chipTooltip(now)) {
      Row(
        Modifier.height(22.dp).border(1.dp, border, shape)
          .then(if (failed) Modifier.background(colors.stFailed.copy(alpha = .08f), shape) else Modifier)
          .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (failed) {
          CompositionLocalProvider(LocalContentColor provides colors.stFailed) { SbIcon(LucideIcons.x, SbIconSize.Xs) }
          BasicText(build.shortCommand, style = type.t2xs.copy(fontWeight = FontWeight.Normal, color = colors.stFailed), maxLines = 1, softWrap = false)
          BasicText("failed", style = type.tXs.copy(fontWeight = FontWeight.Normal).tabular().copy(color = colors.stFailed), maxLines = 1, softWrap = false)
        } else {
          BuildSpinner(10.dp, 1.5.dp, slow, null)
          BasicText(build.shortCommand, style = type.t2xs.copy(fontWeight = FontWeight.Normal, color = colors.fg2), maxLines = 1, softWrap = false)
          val clockColor = if (slow) colors.stBlocked else colors.fgMuted
          CompositionLocalProvider(LocalContentColor provides clockColor) {
            if (build.state == SbBuildState.Running) {
              val (duration, asOf) = build.clockBase()
              SbClock(duration, asOf, live = true, showSeconds = true, style = type.tXs.copy(fontWeight = FontWeight.Normal))
            } else {
              BasicText(formatActiveDuration(build.elapsedAt(now), true), style = type.tXs.copy(fontWeight = FontWeight.Normal).tabular().copy(color = clockColor), maxLines = 1)
            }
          }
        }
      }
    }
  }
}

@Composable
fun BuildStrip(build: SbBuild, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val now = rememberBuildNow(build.state == SbBuildState.Running || build.finishedAt != null || build.previous != null)
  val tone = build.tone(now)
  val failed = tone == SbBuildTone.Failed
  val slow = tone == SbBuildTone.Slow
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val (duration, asOf) = build.clockBase()
  Column(
    modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
      .border(1.dp, if (failed) colors.stFailed.copy(alpha = .3f) else colors.borderSubtle, shape)
      .background(colors.bgRaised, shape).padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
      if (build.state == SbBuildState.Running) BuildSpinner(14.dp, 2.dp, slow, "Running")
      else StatusIcon(build.state.statusKind(), progress = null)
      BasicText(build.word(), style = type.tBaseMedium.copy(color = if (failed) colors.stFailed else colors.fg), maxLines = 1, softWrap = false)
      Box(Modifier.weight(1f)) {
        SbTooltip(build.command) {
          Box(
            Modifier.fillMaxWidth().background(colors.bgInset, RoundedCornerShape(SkillBillRadius.xs))
              .border(1.dp, colors.borderSubtle, RoundedCornerShape(SkillBillRadius.xs)).padding(horizontal = 6.dp, vertical = 2.dp),
          ) {
            BasicText(build.command, style = type.monoSm.copy(color = colors.fg2), maxLines = 1, overflow = TextOverflow.Ellipsis)
          }
        }
      }
      Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val clockColor = if (slow) colors.stBlocked else colors.fg
        if (build.state == SbBuildState.Running) {
          CompositionLocalProvider(LocalContentColor provides clockColor) {
            SbClock(duration, asOf, live = true, showSeconds = true, style = type.tMd)
          }
        } else {
          BasicText(formatActiveDuration(build.elapsedAt(now), true), style = type.tMd.tabular().copy(color = clockColor), maxLines = 1)
        }
        build.subLine(now)?.let {
          BasicText(it, style = type.t2xs.copy(fontFamily = type.tXs.fontFamily, fontWeight = FontWeight.Normal, color = if (slow) colors.stBlocked else colors.fgFaint), maxLines = 1)
        }
      }
    }
    build.barRatio(now)?.let { ratio ->
      val reducedMotion = LocalReducedMotion.current
      val animatedRatio = animateFloatAsState(ratio, if (reducedMotion) snap() else tween(1_000, easing = LinearEasing), label = "build-progress").value
      Box(Modifier.fillMaxWidth().height(3.dp).clip(SkillBillRadius.full).background(colors.border).clearAndSetSemantics {}) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animatedRatio).background(if (slow) colors.stBlocked else colors.stPlanning, SkillBillRadius.full))
      }
    }
    val meta = build.metaSegments(now, formatClockTime(build.startedAt))
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      meta.forEachIndexed { index, segment ->
        if (index > 0) BasicText("·", style = type.tXs.copy(color = colors.fgFaint), maxLines = 1)
        when (segment) {
          is Kind -> BasicText(segment.text, style = type.tXs.copy(fontWeight = FontWeight.Medium, color = colors.fgMuted), maxLines = 1)
          is Plain -> BasicText(segment.text, style = type.tXs.copy(fontWeight = FontWeight.Normal, color = colors.fgFaint), maxLines = 1)
          is Previous -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("previous", style = type.tXs.copy(fontWeight = FontWeight.Normal, color = colors.fgFaint), maxLines = 1)
            StatusIcon(segment.kind, progress = null, modifier = Modifier.size(11.dp))
            BasicText(segment.text, style = type.tXs.copy(fontWeight = FontWeight.Normal, color = colors.fgFaint), maxLines = 1)
          }
        }
      }
    }
  }
}

private fun SbBuild.clockBase(): Pair<Long?, Instant?> =
  if (elapsedMs != null && elapsedAsOf != null) elapsedMs to elapsedAsOf else 0L to startedAt
