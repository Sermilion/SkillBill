package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.designsystem.status.statusColor

/** A [PaneSection] title's status: the 14 px icon before the title and [word] in the state color at the end. */
class PaneStatus(val kind: StatusKind, val word: String, val progress: Float? = null)

/**
 * `.sb-pane-sec`: a 24 px title row (11.5/500 `fg-faint`, 6 between items) 2 px above the body. With [status] the title
 * leads with its StatusIcon and ends with the state's word in its color; [end] is a free end slot otherwise. The pane
 * spaces sections 24 apart.
 */
@Composable
fun PaneSection(
  modifier: Modifier = Modifier,
  title: String? = null,
  status: PaneStatus? = null,
  end: (@Composable () -> Unit)? = null,
  body: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth()) {
    if (title != null) {
      GroupLabel(
        label = title,
        lead = status?.let { value -> { StatusIcon(value.kind, value.progress) } },
        end = {
          if (status != null) {
            BasicText(
              status.word,
              style = SkillBillTheme.type.tXs.copy(
                color = statusColor(status.kind),
                fontWeight = FontWeight.Medium,
                lineHeight = 1.em,
              ),
              maxLines = 1,
              softWrap = false,
            )
          } else {
            end?.invoke()
          }
        },
      )
      Spacer(Modifier.height(2.dp))
    }
    body()
  }
}
