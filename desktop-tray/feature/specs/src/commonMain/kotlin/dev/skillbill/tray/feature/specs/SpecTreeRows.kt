package dev.skillbill.tray.feature.specs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.tray.core.designsystem.body
import dev.skillbill.tray.core.designsystem.dim
import dev.skillbill.tray.core.designsystem.ink
import dev.skillbill.tray.core.designsystem.mono
import dev.skillbill.tray.core.designsystem.muted
import dev.skillbill.tray.core.designsystem.orange
import dev.skillbill.tray.core.designsystem.raised
import dev.skillbill.tray.core.domain.model.SpecFileKind
import dev.skillbill.tray.core.domain.model.SpecNode
import dev.skillbill.tray.core.domain.model.SpecStatus

internal val SPEC_ROW_HEIGHT = 30.dp
internal val SPEC_INDENT = 16.dp

/**
 * One tree row. [modifier] carries the list's focus requester and focus tracking. Clicking the row selects it, and a file also
 * opens; the arrow of a folder or bundle expands or collapses it. An open failure shows under the row in orange.
 */
@Composable
internal fun SpecRowItem(
  row: SpecRow,
  selected: Boolean,
  onClick: () -> Unit,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val node = row.node
  Column(Modifier.fillMaxWidth()) {
    Row(
      modifier
        .fillMaxWidth()
        .height(SPEC_ROW_HEIGHT)
        .background(if (selected) raised else Color.Transparent)
        .selectable(selected = selected, onClick = onClick)
        .semantics {
          when (node) {
            is SpecNode.File -> onClick(label = "Open ${node.name}") {
              onClick()
              true
            }
            else -> stateDescription = if (row.expanded) "Expanded" else "Collapsed"
          }
        }
        .padding(start = SPEC_INDENT * row.depth, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Chevron(row, onToggle)
      when (node) {
        is SpecNode.Folder -> FolderRow(node)
        is SpecNode.Bundle -> BundleRow(node)
        is SpecNode.File -> FileRow(node)
      }
    }
  }
}

@Composable
private fun Chevron(row: SpecRow, onToggle: () -> Unit) {
  val toggle =
    if (row.hasChildren) {
      Modifier.clickable(onClickLabel = if (row.expanded) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
    } else {
      Modifier
    }
  Box(Modifier.size(20.dp).then(toggle), contentAlignment = Alignment.Center) {
    if (row.hasChildren) Text("▸", Modifier.rotate(if (row.expanded) 90f else 0f), color = dim, fontSize = 10.sp)
  }
}

@Composable
private fun RowScope.FolderRow(folder: SpecNode.Folder) {
  FolderGlyph()
  RowText(folder.name, Modifier.weight(1f), ink)
  Row(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))) {
    folder.statusCounts.forEach { (status, count) ->
      if (count > 0) Box(Modifier.weight(count.toFloat()).fillMaxHeight().background(statusColor(status)))
    }
  }
  Text(folder.bundleCount.toString(), color = dim, fontFamily = mono, fontSize = 10.sp)
}

@Composable
private fun RowScope.BundleRow(bundle: SpecNode.Bundle) {
  SpecStatusDot(bundle.status)
  bundle.issueKey?.let { Text(it, color = muted, fontFamily = mono, fontSize = 11.sp, maxLines = 1) }
  val canceled = bundle.status == SpecStatus.CANCELED
  RowText(
    bundle.title,
    Modifier.weight(1f),
    if (canceled) dim else ink,
    if (canceled) TextDecoration.LineThrough else null,
  )
  val done = bundle.subtasksDone
  val total = bundle.subtasksTotal
  if (done != null && total != null) Text("$done/$total", color = statusColor(bundle.status), fontFamily = mono, fontSize = 10.sp)
}

@Composable
private fun RowScope.FileRow(file: SpecNode.File) {
  val status = file.status
  if (file.kind == SpecFileKind.SUBTASK_SPEC) {
    if (status != null) SpecStatusDot(status) else Spacer(Modifier.size(10.dp))
    file.subtaskId?.let { Text(it.toString().padStart(2, '0'), color = muted, fontFamily = mono, fontSize = 11.sp) }
    RowText(file.subtaskName ?: file.name, Modifier.weight(1f), ink)
  } else {
    FileGlyph()
    RowText(file.name, Modifier.weight(1f), muted)
  }
}

@Composable
private fun RowText(text: String, modifier: Modifier, color: Color, decoration: TextDecoration? = null) {
  Text(text, modifier, color = color, fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textDecoration = decoration)
}

@Composable
private fun FolderGlyph() {
  Canvas(Modifier.size(12.dp, 10.dp)) {
    val stroke = 1.2.dp.toPx()
    drawRoundRect(dim, Offset(0f, size.height * 0.2f), Size(size.width, size.height * 0.8f), CornerRadius(1.5.dp.toPx()), Stroke(stroke))
    drawLine(dim, Offset(1f, size.height * 0.2f), Offset(size.width * 0.4f, size.height * 0.2f), strokeWidth = stroke * 2)
  }
}

@Composable
private fun FileGlyph() {
  Canvas(Modifier.size(9.dp, 11.dp)) {
    drawRoundRect(dim, Offset.Zero, size, CornerRadius(1.5.dp.toPx()), Stroke(1.2.dp.toPx()))
  }
}
