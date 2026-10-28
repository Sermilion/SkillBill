package dev.skillbill.desktop.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor

enum class SbIconSize(val dp: Dp) {
  Xs(12.dp),
  Sm(14.dp),
  Md(16.dp),
  Lg(20.dp),
}

@Composable
fun SbIcon(
  icon: ImageVector,
  size: SbIconSize = SbIconSize.Md,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
) {
  Image(
    painter = rememberVectorPainter(icon),
    contentDescription = contentDescription,
    modifier = modifier.size(size.dp),
    colorFilter = ColorFilter.tint(LocalContentColor.current),
  )
}
