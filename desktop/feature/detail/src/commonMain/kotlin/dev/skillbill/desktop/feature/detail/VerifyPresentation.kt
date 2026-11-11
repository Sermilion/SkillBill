package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun DetailSections.verifySection(key: String, title: String, count: String? = null, end: (@Composable () -> Unit)? = null, content: DetailSections.() -> Unit) {
  item(key = key) {
    Row(Modifier.padding(top = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(title, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg))
      count?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint)) }
      if (end != null) { Spacer(Modifier.weight(1f)); end() }
    }
  }
  content()
}

@Composable
internal fun VerifyQuote(text: String) {
  val colors = SkillBillTheme.colors
  val rule = colors.borderStrong
  Column(Modifier.fillMaxWidth().background(colors.bgInset, RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)).drawBehind { drawRect(rule, size = Size(2.dp.toPx(), size.height)) }) {
    VerifyReportText(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
  }
}

@Composable
internal fun VerifyWorkSummary(run: VerifyRunDetails, summary: String, onScrollToSection: (String) -> Unit) {
  val intent = run.verdict?.summary == null
  VerifyBox(Modifier.padding(top = 20.dp), fill = SkillBillTheme.colors.bgRaised.copy(alpha = .55f)) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(if (intent) "What this work is meant to do" else "What this work does", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted, fontWeight = FontWeight.Medium, lineHeight = 1.45.em))
        if (intent) BasicText("· from the extracted criteria", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
      }
      BasicText(verifyText(summary.replace(Regex("\\s+"), " ").trim()), style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fg, lineHeight = 1.55.em), maxLines = if (intent) 1 else 3, overflow = TextOverflow.Ellipsis)
      if (!intent) VerifyTextLink("Full summary in Verdict step") { onScrollToSection(verifyStepKey("verdict")) }
      if (intent) {
        val more = (run.criteria.size - 1).takeIf { it > 0 }?.let { "$it more criteria below. " }.orEmpty()
        BasicText("${more}A summary of the change comes with the verdict${if (verifyRunState(run) == VerifyRunState.Parked) ", after you confirm" else ""}.", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal))
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VerifyReportActions(state: DetailUiState, run: VerifyRunDetails, onAction: (DetailAction) -> Unit) {
  val status = verifyRunState(run)
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (status == VerifyRunState.Finished) SbButton("Copy report", { onAction(DetailAction.Copy(verifyReportText(run))) })
    if (run.changedFiles.isNotEmpty() && status !in setOf(VerifyRunState.Parked, VerifyRunState.Superseded)) {
      SbButton("Open changes · ${run.changedFiles.size}", { onAction(DetailAction.OpenChangesWindow()) })
    }
    if (canVerifyAgain(run, (state.sections as? SectionsUi.Loaded)?.runOptions?.agentChoices.orEmpty())) {
      SbButton(if (status == VerifyRunState.Finished) "Verify again" else "Run again", { onAction(DetailAction.VerifyAgain) }, enabled = !state.verifyAgainInFlight)
    }
  }
}

@Composable
internal fun VerifyHeaderActions(state: DetailUiState, run: VerifyRunDetails, view: RunActionsView, recoverFocus: FocusRequester, onAction: (DetailAction) -> Unit, onOpenChat: () -> Unit) {
  if (verifyRunState(run) == VerifyRunState.Parked) {
    SbButton("Review criteria in Chat", onOpenChat, enabled = run.criteria.isNotEmpty())
    SbButton("Adjust intake in Chat", onOpenChat)
  }
  val choices = (state.sections as? SectionsUi.Loaded)?.runOptions?.agentChoices.orEmpty()
  RunActions(verifyRunActionsView(view, run), choices, state.controls.agentMenuOpen, recoverFocus, onAction)
  VerifyReportActions(state, run, onAction)
}
