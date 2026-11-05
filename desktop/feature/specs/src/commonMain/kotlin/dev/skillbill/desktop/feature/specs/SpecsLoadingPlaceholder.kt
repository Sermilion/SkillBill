package dev.skillbill.desktop.feature.specs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.sbPulse
import kotlinx.coroutines.delay

internal const val SPECS_LOADING_REVEAL_DELAY_MS = 150L

/** Title lengths for the placeholder rows, as a fraction of the space left after the id tag. */
private val PLACEHOLDER_TITLE_FRACTIONS = listOf(.78f, .56f, .68f, .46f, .72f, .6f, .5f, .66f)

/**
 * Pulsing rows shaped like [SpecsTree] bundle rows. They appear after [SPECS_LOADING_REVEAL_DELAY_MS], so a fast load
 * goes straight to the tree, and hold still under reduced motion.
 */
@Composable
internal fun SpecsLoadingPlaceholder(modifier: Modifier = Modifier) {
  val revealed by produceState(false) {
    delay(SPECS_LOADING_REVEAL_DELAY_MS)
    value = true
  }
  if (!revealed) return
  Column(
    modifier.fillMaxSize().clearAndSetSemantics {
      contentDescription = LOADING_SPECS
      liveRegion = LiveRegionMode.Polite
    }.sbPulse(),
  ) {
    PLACEHOLDER_TITLE_FRACTIONS.forEach { titleFraction ->
      Row(
        // TreeRow's 12 px base padding, 14 px chevron slot and 8 px gap put the status icon at 34 px.
        Modifier.fillMaxWidth().height(SkillBillSize.compactRow).padding(start = 34.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PlaceholderBlock(Modifier.size(14.dp), CircleShape)
        PlaceholderBlock(Modifier.width(62.dp).height(14.dp), RoundedCornerShape(SkillBillRadius.xs))
        Box(Modifier.weight(1f)) {
          PlaceholderBlock(Modifier.fillMaxWidth(titleFraction).height(10.dp), RoundedCornerShape(SkillBillRadius.xs))
        }
      }
    }
  }
}

@Composable
private fun PlaceholderBlock(modifier: Modifier, shape: Shape) {
  Box(modifier.background(SkillBillTheme.colors.bgActive, shape))
}
