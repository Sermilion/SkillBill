package dev.skillbill.tray.core.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.skillbill.tray.core.designsystem.DesignButton
import dev.skillbill.tray.core.designsystem.blue
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.designsystem.strongBorder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant
import kotlin.time.toKotlinInstant

actual fun formatInstant(value: Instant, pattern: String): String =
  DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(value.toJavaInstant())

actual fun startOfLocalDay(value: Instant): Instant =
  value.toJavaInstant().atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toKotlinInstant()

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

@Composable
actual fun ExpandableReason(reason: String, title: String, modifier: Modifier, color: Color, fontSize: TextUnit, lineHeight: TextUnit) {
  var open by remember(reason) { mutableStateOf(false) }
  var cutOff by remember(reason) { mutableStateOf(false) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    SelectionContainer {
      Text(reason, color = color, fontFamily = body, fontSize = fontSize, lineHeight = lineHeight, maxLines = 3, overflow = TextOverflow.Ellipsis, onTextLayout = { cutOff = it.hasVisualOverflow })
    }
    if (cutOff) {
      Text("Show more", Modifier.clip(RoundedCornerShape(4.dp)).clickable { open = true }.pointerHoverIcon(PointerIcon.Hand).padding(vertical = 2.dp), color = blue, fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
  }
  if (open) Dialog(onDismissRequest = { open = false }) {
    FullReasonCard(reason, title, Modifier.widthIn(min = 320.dp, max = 640.dp), onClose = { open = false })
  }
}

/** The full text of a reason in a raised card with a Copy button, with scrolling for long reports. */
@Composable
private fun FullReasonCard(reason: String, title: String, modifier: Modifier, onClose: () -> Unit) {
  val scroll = rememberScrollState()
  val copy = rememberCopyToClipboard()
  Column(modifier.background(raised, RoundedCornerShape(10.dp)).border(1.dp, strongBorder, RoundedCornerShape(10.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(title, Modifier.weight(1f), color = ink, fontSize = 12.sp)
      DesignButton("Copy") { copy(reason) }
      DesignButton("Close", onClick = onClose)
    }
    Box(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
      SelectionContainer(Modifier.fillMaxWidth().padding(end = 12.dp).verticalScroll(scroll)) {
        Text(reason, color = ink, fontFamily = body, fontSize = 13.sp, lineHeight = 19.sp)
      }
      Box(Modifier.matchParentSize()) {
        VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
      }
    }
  }
}
