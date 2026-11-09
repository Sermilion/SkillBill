package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize

/** Keeps phase activity compact while preserving selectable diagnostic text. */
@Composable
internal fun PeekActivityText(
  text: String,
  modifier: Modifier = Modifier,
  color: Color = SkillBillTheme.colors.fgMuted,
) {
  var expanded by rememberSaveable(text) { mutableStateOf(false) }
  var clipped by remember(text) { mutableStateOf(false) }
  Column(modifier) {
    SelectionContainer {
      BasicText(
        text,
        style = SkillBillTheme.type.tXs.copy(color = color),
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
      )
    }
    if (expanded || clipped) {
      SbButton(
        if (expanded) "Show less" else "Show details",
        { expanded = !expanded },
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
      )
    }
  }
}
