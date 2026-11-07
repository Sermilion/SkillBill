package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.sbBoxSegment
import dev.skillbill.desktop.core.designsystem.list.sbRowSurface
import dev.skillbill.desktop.core.domain.model.Detail

internal fun DetailSections.validationRunsSection(detail: Detail, currentInvocationId: String?) {
  val runs = detail.validationBranchRuns
  if (runs.size < 2) return
  section("validation-runs", "Validation runs on this branch", count = runs.size.toString()) {
    itemsIndexed(runs, key = { _, run -> "validation-run-${run.invocationId}" }) { index, run ->
      val current = run.invocationId == currentInvocationId
      val interaction = remember { MutableInteractionSource() }
      val result = run.result
      val first = if (!result.isNullOrBlank()) firstSentence(result).first else run.currentActivity.orEmpty()
      Row(
        Modifier.fillMaxWidth().sbRowSurface(interaction, selected = current, shape = RoundedCornerShape(6.dp))
          .padding(10.dp).sbBoxSegment(index, runs.size),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        BasicText("●", style = SkillBillTheme.type.tSm.copy(color = lifecycleColor(run.lifecycle)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("#${run.runSequence}", style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg))
            if (current) BasicText("this run", style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted))
            BasicText(lifecycleLabel(run.lifecycle), style = SkillBillTheme.type.tXs.copy(color = lifecycleColor(run.lifecycle)))
          }
          BasicText(first, style = SkillBillTheme.type.tSm.copy(color = SkillBillTheme.colors.fg2), maxLines = 1)
          BasicText(
            listOfNotNull(run.agent ?: "—", formatInstant(run.startedAt, "d MMM HH:mm"), run.activeDurationMs?.let(::activityDuration)).joinToString(" · "),
            style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgMuted),
          )
        }
      }
    }
  }
}
