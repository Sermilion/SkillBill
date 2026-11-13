package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyHeader(run: VerifyRunDetails, onAction: (DetailAction) -> Unit, runActions: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  Column {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
      VerifyPill(verifyStatusLabel(run), verifyStatusTone(run))
      BasicText(run.issueKey ?: "No issue key", style = SkillBillTheme.type.monoSm.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = colors.fgMuted))
      VerifyPill("Verify")
    }
    Spacer(Modifier.height(8.dp))
    BasicText(run.issueKey?.let { "Verify $it" } ?: "Verify run", Modifier.semantics { heading() }, style = SkillBillTheme.type.tXl.copy(color = colors.fg, lineHeight = 1.45.em, letterSpacing = (-.01).em))
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val root = run.repoRoot
      VerifyMeta(LucideIcons.folder, root?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\') ?: "Unknown repository", root, { onAction(DetailAction.RevealFolder) })
      run.target?.let { VerifyMeta(LucideIcons.branch, it, onClick = { onAction(DetailAction.OpenBranch(it)) }) }
      VerifyMeta(LucideIcons.commit, "${run.baseRevision?.take(7) ?: "?"}..${run.headRevision?.take(7) ?: "?"}", "Compared range")
      val intake = run.intake
      val intakeText = if (intake != null && (intake.startsWith("https://") || intake.startsWith("http://"))) {
        buildAnnotatedString { withLink(LinkAnnotation.Url(intake)) { append(verifyIntakeLabel(intake)) } }
      } else rememberRuntimeText(verifyIntakeLabel(intake))
      Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { SbIcon(if (intake?.startsWith("http") == true) LucideIcons.link else LucideIcons.file, 12.dp) }
        BasicText(intakeText, style = SkillBillTheme.type.tSm.copy(color = if (intake?.startsWith("http") == true) colors.accentText else colors.fgMuted))
      }
    }
    Spacer(Modifier.height(12.dp))
    VerifyControlStyle { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { runActions() } }
    run.supersededBy?.let { newerRun ->
      val shape = RoundedCornerShape(8.dp)
      FlowRow(Modifier.padding(top = 12.dp).background(colors.accentTint, shape).border(1.dp, colors.accent.copy(alpha = .35f), shape).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CompositionLocalProvider(LocalContentColor provides colors.accentText) { SbIcon(LucideIcons.info, 14.dp) }
        BasicText("A newer verify run replaced this one. Starting verify again for the same repository abandoned this run before you confirmed its criteria.", Modifier.weight(1f), style = SkillBillTheme.type.tSm.copy(color = colors.fg2))
        VerifyControlStyle { SbButton("Open newer run", { onAction(DetailAction.OpenNewerVerifyRun(newerRun)) }) }
      }
    }
  }
}

@Composable
private fun VerifyMeta(icon: ImageVector, text: String, tooltip: String? = null, onClick: (() -> Unit)? = null) {
  SbTooltip(tooltip ?: text) {
    Row(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides SkillBillTheme.colors.fgMuted) { SbIcon(icon, 12.dp) }
      BasicText(text, style = SkillBillTheme.type.monoSm.copy(fontSize = 11.5.sp, lineHeight = 1.45.em, color = SkillBillTheme.colors.fg2))
    }
  }
}

@Composable
private fun verifyStatusTone(run: VerifyRunDetails) = when (verifyRunState(run)) {
  VerifyRunState.Pending, VerifyRunState.Superseded -> SkillBillTheme.colors.fgFaint
  VerifyRunState.Running -> SkillBillTheme.colors.stActive
  VerifyRunState.Parked -> SkillBillTheme.colors.stBlocked
  VerifyRunState.Stopped -> SkillBillTheme.colors.stFailed
  VerifyRunState.Finished -> SkillBillTheme.colors.stDone
}
