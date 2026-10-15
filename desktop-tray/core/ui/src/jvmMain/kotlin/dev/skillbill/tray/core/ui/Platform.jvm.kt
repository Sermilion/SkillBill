package dev.skillbill.tray.core.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.designsystem.strongBorder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

actual fun formatInstant(value: Instant, pattern: String): String =
  DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(value.toJavaInstant())

actual fun abbreviateHomeDirectory(path: String): String =
  System.getProperty("user.home")?.takeIf { it.isNotEmpty() }?.let { path.replace(it, "~") } ?: path

@Composable
actual fun rememberCopyToClipboard(): (String) -> Unit {
  val clipboard = LocalClipboardManager.current
  return { text -> clipboard.setText(AnnotatedString(text)) }
}

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
  VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun ReasonTooltip(reason: String, modifier: Modifier, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
  var visible by remember(reason) { mutableStateOf(false) }
  var cutOff by remember(reason) { mutableStateOf(false) }
  var opening by remember { mutableStateOf<Job?>(null) }
  val scope = rememberCoroutineScope()
  var dismiss by remember { mutableStateOf<Job?>(null) }
  val copy = rememberCopyToClipboard()
  val hover = Modifier.onPointerEvent(PointerEventType.Enter) {
    dismiss?.cancel()
    opening?.cancel()
    if (cutOff && !visible) opening = scope.launch { delay(2000); if (cutOff) visible = true }
  }.onPointerEvent(PointerEventType.Exit) {
    opening?.cancel()
    dismiss?.cancel()
    dismiss = scope.launch { delay(250); visible = false }
  }
  Box(modifier.then(hover)) {
    SelectionContainer {
      Text(reason, color = color, fontFamily = body, fontSize = fontSize, lineHeight = lineHeight, maxLines = 3, overflow = TextOverflow.Ellipsis, onTextLayout = { cutOff = it.hasVisualOverflow })
    }
    if (visible && cutOff) Popup(alignment = Alignment.BottomStart, offset = IntOffset(0, 12), onDismissRequest = { visible = false }, properties = PopupProperties(focusable = false)) {
      val scroll = rememberScrollState()
      Column(hover.widthIn(min = 240.dp, max = 480.dp).background(raised, RoundedCornerShape(10.dp)).border(1.dp, strongBorder, RoundedCornerShape(10.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Full reason", Modifier.weight(1f), color = ink, fontSize = 12.sp)
          DesignButton("Copy") { copy(reason) }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
          SelectionContainer(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll)) {
            Text(reason, color = ink, fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp)
          }
          Box(Modifier.matchParentSize()) {
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
          }
        }
      }
    }
  }
}
