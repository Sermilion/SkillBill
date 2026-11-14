package dev.skillbill.desktop.app.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.LocalReducedMotion
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.dataColor
import dev.skillbill.desktop.core.designsystem.composite.Edge
import dev.skillbill.desktop.core.designsystem.composite.hairline
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize

@Composable
internal fun TourReviewHeader(state: TourReviewState) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().height(44.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    BasicText(TourFixture.goalKey, style = type.monoSm.copy(color = colors.fgFaint))
    BasicText("›", Modifier.padding(horizontal = 8.dp), style = type.tSm.copy(color = colors.fgFaint))
    BasicText("Changes", style = type.tBaseMedium.copy(color = colors.fg))
    Spacer(Modifier.width(6.dp))
    TourChip { BasicText("All subtasks ▾", style = type.tXs.copy(color = colors.fgMuted)) }
    Spacer(Modifier.width(6.dp))
    TourChip {
      BasicText("5 files", style = type.tXs.copy(color = colors.fgMuted))
      BasicText("+156", style = type.tXs.copy(color = colors.stActive))
      BasicText("−8", style = type.tXs.copy(color = colors.stFailed))
    }
    Spacer(Modifier.weight(1f))
    SbButton(
      state.reviewLabel,
      {},
      modifier = Modifier.tourTarget(TourTarget.AddReview),
      variant = if (state.reviewPrimary) SbButtonVariant.Primary else SbButtonVariant.Secondary,
      size = SbControlSize.Small,
    )
  }
}

@Composable
internal fun TourReviewFileList() {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(
    Modifier.width(250.dp).fillMaxHeight().hairline(Edge.End, colors.borderSubtle)
      .padding(horizontal = 8.dp, vertical = 12.dp),
  ) {
    TourSectionLabel("Files", Modifier.padding(horizontal = 8.dp))
    Column(Modifier.padding(top = 10.dp)) {
      TourFixture.reviewFiles.forEachIndexed { index, file ->
        Row(
          Modifier.fillMaxWidth().height(30.dp)
            .background(if (index == 0) colors.bgActive else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          BasicText(
            file.path,
            Modifier.weight(1f),
            style = type.monoSm.copy(color = if (index == 0) colors.fg else colors.fgMuted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          BasicText("+${file.adds}", style = type.monoSm.copy(color = colors.stActive, fontSize = 11.sp))
          if (file.deletes > 0) BasicText("−${file.deletes}", style = type.monoSm.copy(color = colors.stFailed, fontSize = 11.sp))
        }
      }
    }
  }
}

@Composable
internal fun TourReviewDiff(state: TourReviewState, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.fillMaxHeight()) {
    Row(
      Modifier.fillMaxWidth().height(36.dp).hairline(Edge.Bottom, colors.borderSubtle).padding(horizontal = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(LucideIcons.file, SbIconSize.Xs) }
      BasicText("src/export/csv.ts", style = type.monoSm.copy(color = colors.fg2))
      Spacer(Modifier.weight(1f))
      BasicText("+64", style = type.monoSm.copy(color = colors.stActive))
    }
    Column(Modifier.padding(vertical = 8.dp)) {
      state.rows.forEach { row ->
        TourDiffRow(row, state)
        if (row.target && (state.draft != null || state.youTag != null)) {
          TourCommentArea(state)
        }
      }
    }
  }
}

@Composable
private fun TourDiffRow(row: TourDiffLine, state: TourReviewState) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val reduced = LocalReducedMotion.current
  val flash = remember { Animatable(0f) }
  LaunchedEffect(state.lineFlash) {
    if (!state.lineFlash || !row.target) return@LaunchedEffect
    flash.snapTo(.3f)
    if (reduced) flash.snapTo(0f) else flash.animateTo(0f, tween(1400))
  }
  val bg = when {
    row.target && state.targetTint -> colors.accent.copy(alpha = .10f)
    row.sign == '+' -> colors.diffAddedBg
    row.sign == '-' -> colors.diffRemovedBg
    else -> Color.Transparent
  }
  Box(Modifier.fillMaxWidth().then(if (row.target) Modifier.tourTarget(TourTarget.DiffLine) else Modifier)) {
    Row(Modifier.fillMaxWidth().height(22.dp).background(bg), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(46.dp).padding(end = 10.dp), contentAlignment = Alignment.CenterEnd) {
        BasicText(row.number?.toString() ?: "", style = type.mono.copy(color = colors.fgFaint, fontSize = 12.sp))
        if (row.target) {
          TourPlusButton(state.plusShown, Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        }
      }
      BasicText(
        if (row.sign == ' ') "" else row.sign.toString(),
        Modifier.width(16.dp),
        style = type.mono.copy(
          fontSize = 12.sp,
          color = when (row.sign) {
            '+' -> colors.stActive
            '-' -> colors.stFailed
            else -> colors.fgFaint
          },
        ),
      )
      BasicText(
        row.text,
        style = type.mono.copy(color = colors.fg2, fontSize = 12.sp),
        softWrap = false,
      )
    }
    if (flash.value > 0f) Box(Modifier.matchParentSize().background(colors.accent.copy(alpha = flash.value)))
  }
}

@Composable
private fun TourPlusButton(shown: Boolean, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val reduced = LocalReducedMotion.current
  val appear = remember { Animatable(0f) }
  LaunchedEffect(shown) {
    appear.animateTo(if (shown) 1f else 0f, if (reduced) snap() else tween(350, easing = CubicBezierEasing(.34f, 1.56f, .64f, 1f)))
  }
  if (appear.value <= 0f) return
  Box(
    modifier.size(18.dp)
      .graphicsLayer { alpha = appear.value; scaleX = .7f + .3f * appear.value; scaleY = scaleX }
      .clip(RoundedCornerShape(5.dp)).background(colors.accent)
      .tourTarget(TourTarget.DiffPlus)
      .semantics { contentDescription = "Comment on line 8" }
      .clickable(role = Role.Button) {},
    contentAlignment = Alignment.Center,
  ) {
    BasicText("+", style = SkillBillTheme.type.tSmMedium.copy(color = colors.accentFg, fontSize = 14.sp))
  }
}

/** The draft box under line 8, which becomes the comment thread once the review is added. */
@Composable
private fun TourCommentArea(state: TourReviewState) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val shape = RoundedCornerShape(10.dp)
  Column(
    Modifier.padding(start = 62.dp, top = 6.dp, end = 14.dp, bottom = 8.dp)
      .border(1.dp, colors.borderStrong, shape)
      .background(colors.bgRaised, shape),
  ) {
    state.draft?.let { draft ->
      Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 10.dp),
      ) {
        BasicText(draft, Modifier.weight(1f, fill = false), style = type.tSm.copy(color = colors.fg))
        TourCaret()
      }
      Row(
        Modifier.fillMaxWidth().hairline(Edge.Top, colors.borderSubtle).padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        BasicText(TourFixture.reviewDraftFooter, Modifier.weight(1f), style = type.tXs.copy(color = colors.fgFaint))
        SbButton("Cancel", {}, size = SbControlSize.Small)
        SbButton("Save", {}, size = SbControlSize.Small)
      }
    }
    if (state.youTag != null) {
      TourThreadEntry(
        TourAgent("B", 1), "You", state.youTag, TourFixture.reviewCommentThread,
        first = state.draft == null,
      )
    }
    if (state.claude != TourClaudeReply.None) {
      TourThreadEntry(
        TourAgent("C", 4), "Claude", null,
        if (state.claude == TourClaudeReply.Replied) TourFixture.reviewReply else null,
        first = state.youTag == null,
      )
    }
  }
}

@Composable
private fun TourThreadEntry(agent: TourAgent, who: String, tag: String?, text: String?, first: Boolean) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth()
      .then(if (first) Modifier else Modifier.hairline(Edge.Top, colors.borderSubtle))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(9.dp),
  ) {
    Box(Modifier.size(22.dp).background(colors.dataColor(agent.dataSlot), CircleShape), contentAlignment = Alignment.Center) {
      BasicText(agent.name, style = type.tXs.copy(color = colors.bgFrame, fontWeight = FontWeight.Bold))
    }
    Column {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(who, style = type.tSm.copy(color = colors.fg, fontWeight = FontWeight.SemiBold))
        tag?.let {
          SbLabel(
            it,
            style = SbLabelStyle.Tint(if (it == "Sent to agent") colors.stActive else colors.accentText),
            size = SbControlSize.XSmall,
          )
        }
      }
      if (text != null) {
        BasicText(agentText(text, type.mono.fontFamily), Modifier.padding(top = 2.dp), style = type.tSm.copy(color = colors.fg2))
      } else {
        TourThinkingDots()
      }
    }
  }
}
