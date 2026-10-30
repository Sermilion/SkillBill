package dev.skillbill.desktop.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
