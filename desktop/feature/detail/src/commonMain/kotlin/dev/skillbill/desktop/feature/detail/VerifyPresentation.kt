package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails

internal fun DetailSections.verifySection(key: String, title: String, count: String? = null, content: DetailSections.() -> Unit) {
  item(key = key) {
    Row(Modifier.padding(top = 24.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicText(title, style = SkillBillTheme.type.tBaseMedium.copy(color = SkillBillTheme.colors.fg))
      count?.let { BasicText(it, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fgFaint)) }
    }
  }
  content()
}

@Composable
internal fun VerifyQuote(text: String) {
  SbBox(fill = SkillBillTheme.colors.bgInset) {
    BasicText(rememberRuntimeText(text), Modifier.padding(12.dp), style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2))
  }
}

@Composable
internal fun VerifyWorkSummary(run: VerifyRunDetails, summary: String) {
  val intent = run.verdict?.summary == null
  SbBox(Modifier.padding(top = 20.dp), fill = SkillBillTheme.colors.bgRaised.copy(alpha = .55f)) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      BasicText(if (intent) "What this work is meant to do" else "What this work does", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
      SbTooltip(summary) {
        BasicText(rememberRuntimeText(summary), style = SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fg), maxLines = if (intent) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
      }
      if (intent) {
        val more = (run.criteria.size - 1).takeIf { it > 0 }?.let { "$it more criteria below. " }.orEmpty()
        BasicText("${more}A summary of the change comes with the verdict${if (verifyRunState(run) == VerifyRunState.Parked) ", after you confirm" else ""}.", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint))
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
