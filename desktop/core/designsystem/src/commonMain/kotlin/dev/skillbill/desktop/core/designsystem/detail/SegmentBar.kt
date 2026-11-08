package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbPulse

@Immutable
class BarSegment(val weight: Float, val color: Color, val pulse: Boolean = false)

@Composable
fun SegmentBar(segments: List<BarSegment>, description: String, modifier: Modifier = Modifier, gap: Dp = 0.dp) {
  val colors = SkillBillTheme.colors
  val visibleSegments = segments.filter { it.weight > 0 }
  Row(
    modifier.fillMaxWidth()
      .height(5.dp)
      .clip(RoundedCornerShape(SkillBillRadius.xs))
      .background(colors.borderSubtle)
      .semantics { contentDescription = description },
    horizontalArrangement = Arrangement.spacedBy(gap),
  ) {
    visibleSegments.forEach { segment ->
      Box(
        Modifier.weight(segment.weight)
          .fillMaxHeight()
          .then(if (segment.pulse) Modifier.sbPulse() else Modifier)
          .background(segment.color),
      )
    }
  }
}
