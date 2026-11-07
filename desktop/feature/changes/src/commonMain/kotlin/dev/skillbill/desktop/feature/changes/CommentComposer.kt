package dev.skillbill.desktop.feature.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import dev.skillbill.desktop.core.designsystem.component.FeedbackTone
import dev.skillbill.desktop.core.designsystem.component.Hint
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.ui.changes.changesSans

@Composable
internal fun CommentComposer(
  text: String,
  problem: String?,
  saving: Boolean,
  placeholder: String,
  hint: String,
  primaryLabel: String,
  onText: (String) -> Unit,
  onSubmit: () -> Unit,
  onCancel: () -> Unit,
  onFocusChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
) {
  val focus = remember { FocusRequester() }
  val currentFocusChanged by rememberUpdatedState(onFocusChanged)
  DisposableEffect(Unit) { onDispose { currentFocusChanged(false) } }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val colors = SkillBillTheme.colors
  SbBox(modifier, fill = colors.bgPanel) {
    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      BasicTextField(
        value = text,
        onValueChange = onText,
        readOnly = saving,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).background(colors.bgInset, RoundedCornerShape(5.dp)).padding(8.dp)
          .focusRequester(focus).onFocusChanged { onFocusChanged(it.isFocused) }
          .semantics {
            contentDescription = placeholder.ifEmpty { "Edit pending comment" }
            problem?.let { error(it); liveRegion = LiveRegionMode.Polite }
          }
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && (event.isMetaPressed || event.isCtrlPressed)) {
              if (text.isNotBlank() && !saving) onSubmit()
              true
            } else false
          },
        textStyle = changesSans(11.5f, FontWeight.Normal, colors.fg),
        cursorBrush = SolidColor(colors.fg),
        decorationBox = { inner ->
          if (text.isEmpty() && placeholder.isNotEmpty()) BasicText(placeholder, style = changesSans(11.5f, FontWeight.Normal, colors.fgMuted))
          inner()
        },
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(hint, Modifier.weight(1f), style = changesSans(10.5f, FontWeight.Normal, colors.fgFaint))
        SbButton("Cancel", onCancel, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
        SbButton(primaryLabel, onSubmit, variant = SbButtonVariant.Primary, size = SbControlSize.Small,
          shortcut = "⌘/Ctrl+↵", enabled = text.isNotBlank() && !saving)
      }
      problem?.let { Hint(it, tone = FeedbackTone.Error) }
    }
  }
}
