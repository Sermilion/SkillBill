package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

/** How many leading characters a [ShortId] keeps before the ellipsis. */
const val SHORT_ID_LENGTH = 8

/** [id] cut to its first [keep] characters plus an ellipsis, or whole when that would not save anything. */
fun shortId(id: String, keep: Int = SHORT_ID_LENGTH): String = if (id.length > keep + 1) id.take(keep) + "…" else id

/**
 * A machine id (workflow, invocation, commit, evidence reference) as an operator needs it: the first few characters in faint mono,
 * the full value in the tooltip, and a copy button that hands the full [value] to [onCopy]. [shown] is the text to shorten when
 * part of the value repeats what the row already says, such as an evidence reference's kind prefix.
 */
@Composable
fun ShortId(value: String, onCopy: (String) -> Unit, modifier: Modifier = Modifier, label: String = "id", shown: String = value) {
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
    SbTooltip(value) {
      BasicText(
        shortId(shown),
        style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal),
        maxLines = 1,
        softWrap = false,
      )
    }
    SbIconButton(LucideIcons.copy, "Copy $label", { onCopy(value) }, size = SbControlSize.Small)
  }
}
