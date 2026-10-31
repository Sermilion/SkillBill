package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

/** How many lines long runtime text shows before it folds. */
const val EXPANDABLE_TEXT_LINES = 3

/**
 * `.sb-fold`: long runtime text folded to [maxLines] with an ellipsis. "Show more" (a small ghost button with chevronDown) shows
 * only when the folded text overflows, 2 px under it; open, it reads "Show less" with chevronUp. Short text adds no toggle and no
 * gap. The open state resets when [text] changes. [selectable] lets the reader select and copy the text.
 */
@Composable
fun ExpandableText(
  text: AnnotatedString,
  style: TextStyle,
  modifier: Modifier = Modifier,
  maxLines: Int = EXPANDABLE_TEXT_LINES,
  selectable: Boolean = false,
) {
  var expanded by remember(text) { mutableStateOf(false) }
  var overflows by remember(text) { mutableStateOf(false) }
  Column(modifier) {
    val body: @Composable () -> Unit = {
      BasicText(
        text,
        style = style,
        maxLines = if (expanded) Int.MAX_VALUE else maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
      )
    }
    if (selectable) SelectionContainer { body() } else body()
    if (overflows || expanded) {
      SbButton(
        if (expanded) "Show less" else "Show more",
        { expanded = !expanded },
        modifier = Modifier.padding(top = 2.dp),
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        icon = if (expanded) LucideIcons.chevronUp else LucideIcons.chevronDown,
      )
    }
  }
}

@Composable
fun ExpandableText(
  text: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
  maxLines: Int = EXPANDABLE_TEXT_LINES,
  selectable: Boolean = false,
) = ExpandableText(rememberRuntimeText(text), style, modifier, maxLines, selectable)
