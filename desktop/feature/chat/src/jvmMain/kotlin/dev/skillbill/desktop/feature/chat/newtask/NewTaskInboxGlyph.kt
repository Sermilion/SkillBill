package dev.skillbill.desktop.feature.chat.newtask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal enum class InboxGlyphKind { Chat, Image, Blocked, Close, Arrow }

private val CHAT_PATH = PathParser().parsePathString("M3 3.5h10a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H7l-3 2.5V11.5H3a1 1 0 0 1-1-1v-6a1 1 0 0 1 1-1z").toPath()
private val IMAGE_PATH = PathParser().parsePathString("M14 11l-3.5-3.5L4 13").toPath()
private val BLOCKED_PATH = PathParser().parsePathString("M8 5v3.5M8 11h.01").toPath()
private val CLOSE_PATH = PathParser().parsePathString("M4.5 4.5l7 7M11.5 4.5l-7 7").toPath()
private val ARROW_PATH = PathParser().parsePathString("M3 8h10M9 4l4 4-4 4").toPath()
private val GLYPH_STROKE = Stroke(width = 1.75f, cap = StrokeCap.Round, join = StrokeJoin.Round)

@Composable
internal fun InboxGlyph(kind: InboxGlyphKind, color: Color) {
  Canvas(Modifier.size(12.dp)) {
    withTransform({ scale(size.width / 16f, size.height / 16f, pivot = Offset.Zero) }) {
      when (kind) {
        InboxGlyphKind.Chat -> drawPath(CHAT_PATH, color, style = GLYPH_STROKE)
        InboxGlyphKind.Image -> {
          drawRoundRect(color, Offset(2f, 3f), Size(12f, 10f), CornerRadius(1.5f), style = GLYPH_STROKE)
          drawCircle(color, 1.2f, Offset(6f, 6.5f), style = GLYPH_STROKE)
          drawPath(IMAGE_PATH, color, style = GLYPH_STROKE)
        }
        InboxGlyphKind.Blocked -> {
          drawCircle(color, 6.2f, Offset(8f, 8f), style = GLYPH_STROKE)
          drawPath(BLOCKED_PATH, color, style = GLYPH_STROKE)
        }
        InboxGlyphKind.Close -> drawPath(CLOSE_PATH, color, style = GLYPH_STROKE)
        InboxGlyphKind.Arrow -> drawPath(ARROW_PATH, color, style = GLYPH_STROKE)
      }
    }
  }
}
