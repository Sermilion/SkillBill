package dev.skillbill.desktop.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

data class SkillBillShadows(
  val popover: List<Shadow>,
  val modal: List<Shadow>,
)

internal val DarkSkillBillShadows = SkillBillShadows(
  popover = listOf(
    Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.4f)),
    Shadow(radius = 24.dp, offset = DpOffset(0.dp, 8.dp), color = Color.Black.copy(alpha = 0.5f)),
    Shadow(radius = 6.dp, offset = DpOffset(0.dp, 2.dp), color = Color.Black.copy(alpha = 0.3f)),
  ),
  modal = listOf(
    Shadow(radius = 0.dp, spread = 0.5.dp, color = Color.Black.copy(alpha = 0.5f)),
    Shadow(radius = 64.dp, offset = DpOffset(0.dp, 24.dp), color = Color.Black.copy(alpha = 0.6f)),
  ),
)

fun Modifier.layeredShadow(shadows: List<Shadow>, shape: Shape): Modifier =
  shadows.fold(this) { modifier, shadow -> modifier.dropShadow(shape, shadow) }

/** Draw behind an opaque surface with the same shape to avoid painting the covered interior of its shadows. */
@Composable
fun ShadowBackdrop(shadows: List<Shadow>, shape: Shape, modifier: Modifier = Modifier) {
  Box(modifier.drawWithCache {
    val interior = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    onDrawWithContent { clipPath(interior, ClipOp.Difference) { this@onDrawWithContent.drawContent() } }
  }.layeredShadow(shadows, shape))
}
