package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownDivider
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.detail.SbBox
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

@Composable
internal fun ChatMarkdownTable(content: String, node: ASTNode, style: TextStyle) {
  val columns = node.findChildOfType(HEADER)?.children?.count { it.type == CELL } ?: 0
  val rows = node.children.count { it.type == ROW } + 1
  val cellWidth = LocalMarkdownDimens.current.tableCellWidth
  val scroll = rememberScrollState()
  val colors = SkillBillTheme.colors
  SbBox(Modifier.padding(vertical = 4.dp)) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val tableWidth = maxOf(maxWidth, cellWidth * columns)
      val scrollable = tableWidth > maxWidth
      Column(Modifier.fillMaxWidth()) {
        Column(
          Modifier.horizontalScroll(scroll).width(tableWidth).semantics {
            collectionInfo = CollectionInfo(rowCount = rows, columnCount = columns)
          },
        ) {
          var rowIndex = 1
          node.children.forEach { child ->
            when (child.type) {
              HEADER -> MarkdownTableHeader(content, child, tableWidth, style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
              ROW -> MarkdownTableRow(content, child, tableWidth, style, rowIndex = rowIndex++, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
              TABLE_SEPARATOR -> MarkdownDivider()
            }
          }
        }
        if (scrollable) {
          DisableSelection {
            HorizontalScrollbar(
              adapter = rememberScrollbarAdapter(scroll),
              modifier = Modifier.fillMaxWidth().padding(6.dp).semantics { contentDescription = "Scroll table horizontally" },
              style = defaultScrollbarStyle().copy(
                thickness = 10.dp,
                unhoverColor = colors.fgMuted,
                hoverColor = colors.fg,
              ),
            )
          }
        }
      }
    }
  }
}
