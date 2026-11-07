package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbDivider
import dev.skillbill.desktop.core.designsystem.component.SbLabel
import dev.skillbill.desktop.core.designsystem.component.SbLabelStyle
import dev.skillbill.desktop.core.designsystem.component.SbSpinner
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

@Composable
internal fun QaCaseCard(case: QaCaseView, invocationDirectory: String?, onOpenFinding: (String) -> Unit, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  val border = if (case.status.label == "Running") colors.stActive else colors.borderSubtle
  Column(Modifier.fillMaxWidth().clip(shape).background(colors.bgRaised).border(1.dp, border, shape)) {
    val interaction = remember { MutableInteractionSource() }
    Row(
      Modifier.fillMaxWidth().heightIn(min = 56.dp).sbFocusRing(interaction, shape)
        .pointerHoverIcon(PointerIcon.Hand)
        .clickable(interaction, indication = null, role = Role.Button) { onAction(DetailAction.ToggleQaCase(case.key)) }
        .semantics {
          contentDescription = "${if (case.open) "Collapse" else "Expand"} case ${case.id} ${case.title}"
          stateDescription = "${case.status.label}, ${case.done} of ${case.total} steps"
        }
        .padding(horizontal = 18.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
      CaseStatusIcon(case.status.label)
      BasicText(case.id, Modifier.width(56.dp), style = SkillBillTheme.type.monoSm.copy(color = colors.fgMuted))
      BasicText(case.title, Modifier.weight(1f), style = SkillBillTheme.type.tMd.copy(color = colors.fg), maxLines = 2)
      val statusColor = when (case.status.label) {
        "Failed" -> colors.stFailed
        "Passed" -> colors.stDone
        "Running" -> colors.stActive
        else -> colors.fgMuted
      }
      SbLabel(case.status.label, style = SbLabelStyle.Tint(statusColor))
      BasicText("${case.done}/${case.total}", Modifier.width(40.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgMuted), maxLines = 1)
      CompositionLocalProvider(LocalContentColor provides colors.fgFaint) {
        SbIcon(LucideIcons.chevronDown, 16.dp, modifier = Modifier.graphicsLayer { rotationZ = if (case.open) 180f else 0f })
      }
    }
    if (case.open) {
      SbDivider(color = colors.borderSubtle)
      Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        case.steps.forEach { step -> QaStepRow(step, invocationDirectory, onOpenFinding, onAction) }
      }
    }
  }
}

@Composable
private fun CaseStatusIcon(status: String) {
  when (status) {
    "Running" -> SbSpinner(20.dp, contentDescription = null)
    "Failed" -> StatusIcon(StatusKind.Failed, null, modifier = Modifier.size(20.dp))
    "Passed" -> StatusDescription("Passed") { StatusIcon(StatusKind.Completed, null, modifier = Modifier.size(20.dp)) }
    "Stopped" -> StatusDescription("Stopped") { StatusIcon(StatusKind.Canceled, null, modifier = Modifier.size(20.dp)) }
    "Not run" -> StatusDescription("Not run") { StatusIcon(StatusKind.Skipped, null, modifier = Modifier.size(20.dp)) }
    else -> StatusDescription("Pending") { StatusIcon(StatusKind.Pending, null, modifier = Modifier.size(20.dp)) }
  }
}

@Composable
private fun QaStepRow(step: QaStepView, invocationDirectory: String?, onOpenFinding: (String) -> Unit, onAction: (DetailAction) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val running = step.display == QaStepDisplay.Running
  val background = if (running) colors.stActive.copy(alpha = .10f) else Color.Transparent
  val frame = if (running) colors.stActive.copy(alpha = .35f) else Color.Transparent
  Row(Modifier.fillMaxWidth().background(background, shape).border(1.dp, frame, shape).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { StepStatusIcon(step.display) }
    BasicText(step.number.toString(), Modifier.width(18.dp), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      val textColor = when (step.display) {
        QaStepDisplay.Pending, QaStepDisplay.NotRun -> colors.fgMuted
        else -> colors.fg
      }
      BasicText(step.text, style = SkillBillTheme.type.tBase.copy(color = textColor, fontWeight = if (running) FontWeight.SemiBold else FontWeight.Normal))
      step.note?.let { note ->
        val noteColor = when (step.display) {
          QaStepDisplay.Failed -> colors.stFailed
          QaStepDisplay.Running -> colors.stActive
          else -> colors.fgMuted
        }
        BasicText(note, style = SkillBillTheme.type.tSm.copy(color = noteColor))
      }
      if (step.findingId != null || (invocationDirectory != null && step.evidence.isNotEmpty())) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          step.findingId?.let { FindingChip(it, onOpenFinding) }
          if (invocationDirectory != null) step.evidence.forEach { path -> EvidenceChip(path) { onAction(DetailAction.OpenQaEvidence(path, invocationDirectory)) } }
        }
      }
    }
    step.updatedAt?.let { BasicText(formatInstant(it, "HH:mm"), style = SkillBillTheme.type.tSm.copy(color = colors.fgFaint)) }
  }
}

@Composable
private fun StepStatusIcon(display: QaStepDisplay) {
  val colors = SkillBillTheme.colors
  when (display) {
    QaStepDisplay.Passed -> CompositionLocalProvider(LocalContentColor provides colors.stDone) { SbIcon(LucideIcons.check, 16.dp, contentDescription = "Passed") }
    QaStepDisplay.Failed -> CompositionLocalProvider(LocalContentColor provides colors.stFailed) { SbIcon(LucideIcons.x, 16.dp, contentDescription = "Failed") }
    QaStepDisplay.Running -> SbSpinner(16.dp, contentDescription = "Running")
    QaStepDisplay.Pending -> StatusDescription("Pending") { StatusIcon(StatusKind.Pending, null, modifier = Modifier.size(16.dp)) }
    QaStepDisplay.Stopped -> StatusDescription("Stopped") { StatusIcon(StatusKind.Canceled, null, modifier = Modifier.size(16.dp)) }
    QaStepDisplay.NotRun -> StatusDescription("Not run") { StatusIcon(StatusKind.Skipped, null, modifier = Modifier.size(16.dp)) }
  }
}

@Composable
private fun StatusDescription(description: String, content: @Composable () -> Unit) {
  Box(Modifier.clearAndSetSemantics { contentDescription = description }) { content() }
}

@Composable
private fun FindingChip(id: String, onOpen: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  BasicText(
    "Finding $id",
    Modifier.background(colors.stFailed.copy(alpha = .15f), shape).clickable(role = Role.Button, onClick = { onOpen(id) }).padding(horizontal = 8.dp, vertical = 2.dp),
    style = SkillBillTheme.type.tSm.copy(color = colors.stFailed, fontWeight = FontWeight.SemiBold),
  )
}

@Composable
internal fun EvidenceChip(path: String, onOpen: () -> Unit) {
  val name = fileName(path)
  QaChip(name, LucideIcons.file, "Open evidence $name", mono = true, onClick = onOpen)
}

@Composable
internal fun QaChip(label: String, icon: ImageVector, description: String = label, mono: Boolean = false, onClick: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.xs)).border(1.dp, colors.border, RoundedCornerShape(SkillBillRadius.xs))
      .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }
      .padding(horizontal = 8.dp, vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
  ) {
    SbIcon(icon, 12.dp)
    BasicText(label, style = (if (mono) type.monoSm else type.tSm).copy(color = colors.fg2))
  }
}
