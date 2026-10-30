package dev.skillbill.desktop.core.designsystem.icon

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor

/** Stroke widths are in the 24-unit icon viewBox: `.sb-icon` draws 1.75, `.sb-icon--lg` draws 1.5. */
const val SB_ICON_STROKE = 1.75f
const val SB_ICON_STROKE_LG = 1.5f

enum class SbIconSize(val dp: Dp, val strokeWidth: Float = SB_ICON_STROKE) {
  Xs(12.dp),
  Sm(14.dp),
  Md(16.dp),
  Lg(20.dp, SB_ICON_STROKE_LG),
}

@Composable
fun SbIcon(
  icon: ImageVector,
  size: SbIconSize = SbIconSize.Md,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
) = SbIcon(icon, size.dp, size.strokeWidth, contentDescription, modifier)

/** For icons the design sizes off the scale, such as the 10 px checks in phase steps (stroke 2.5) and checkboxes (stroke 3). */
@Composable
fun SbIcon(
  icon: ImageVector,
  size: Dp,
  strokeWidth: Float = SB_ICON_STROKE,
  contentDescription: String? = null,
  modifier: Modifier = Modifier,
) {
  val vector = if (strokeWidth == SB_ICON_STROKE) icon else remember(icon, strokeWidth) { icon.withStrokeWidth(strokeWidth) }
  Image(
    painter = rememberVectorPainter(vector),
    contentDescription = contentDescription,
    modifier = modifier.size(size),
    colorFilter = ColorFilter.tint(LocalContentColor.current),
  )
}

private fun ImageVector.withStrokeWidth(width: Float): ImageVector {
  val builder = ImageVector.Builder(name, defaultWidth, defaultHeight, viewportWidth, viewportHeight)
  fun copyGroup(group: VectorGroup) {
    group.forEach { node ->
      when (node) {
        is VectorPath -> builder.addPath(
          pathData = node.pathData,
          pathFillType = node.pathFillType,
          name = node.name,
          fill = node.fill,
          fillAlpha = node.fillAlpha,
          stroke = node.stroke,
          strokeAlpha = node.strokeAlpha,
          strokeLineWidth = width,
          strokeLineCap = node.strokeLineCap,
          strokeLineJoin = node.strokeLineJoin,
          strokeLineMiter = node.strokeLineMiter,
          trimPathStart = node.trimPathStart,
          trimPathEnd = node.trimPathEnd,
          trimPathOffset = node.trimPathOffset,
        )
        is VectorGroup -> {
          builder.addGroup(node.name, node.rotation, node.pivotX, node.pivotY, node.scaleX, node.scaleY, node.translationX, node.translationY, node.clipPathData)
          copyGroup(node)
          builder.clearGroup()
        }
      }
    }
  }
  copyGroup(root)
  return builder.build()
}
