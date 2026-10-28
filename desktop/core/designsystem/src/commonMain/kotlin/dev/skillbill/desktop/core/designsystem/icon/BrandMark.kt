package dev.skillbill.desktop.core.designsystem.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.generated.resources.Res
import dev.skillbill.desktop.core.designsystem.generated.resources.jetbrains_mono
import org.jetbrains.compose.resources.Font

@Composable
fun BrandMark(size: Dp = 20.dp) {
  require(size.value in 16f..32f)
  val font = Font(
    Res.font.jetbrains_mono,
    weight = FontWeight(800),
    variationSettings = FontVariation.Settings(FontVariation.weight(800)),
  )
  val family = remember(font) { FontFamily(font) }
  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()
  Canvas(Modifier.size(size)) {
    val scale = this.size.width / 64f
    val textStyle = TextStyle(
      fontFamily = family,
      fontWeight = FontWeight(800),
      fontSize = density.pixelsToSp(27f * scale),
      letterSpacing = density.pixelsToSp(-scale),
      color = Color(0xFF0B0B0D),
    )
    val text = textMeasurer.measure("SB", textStyle)
    drawRoundRect(
      brush = Brush.linearGradient(
        colors = listOf(Color(0xFFF4C430), Color(0xFF8F6D05)),
        start = Offset(scale, scale),
        end = Offset(63f * scale, 63f * scale),
      ),
      topLeft = Offset(scale, scale),
      size = Size(62f * scale, 62f * scale),
      cornerRadius = CornerRadius(15f * scale),
    )
    drawText(text, topLeft = Offset(58f * scale - text.size.width, 56f * scale - text.firstBaseline))
    drawCircle(Color(0xFF60D394), 5f * scale, Offset(51f * scale, 14f * scale))
    drawCircle(Color(0xFF0B0B0D).copy(alpha = 0.35f), 5f * scale, Offset(51f * scale, 14f * scale), style = Stroke(1f * scale))
  }
}

private fun Density.pixelsToSp(pixels: Float) = with(this) { pixels.toDp().toSp() }
