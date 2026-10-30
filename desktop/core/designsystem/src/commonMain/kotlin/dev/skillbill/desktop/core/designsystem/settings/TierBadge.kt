package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbTooltip

enum class Tier(val letter: String, val description: String) {
  Reasoning("R", "Reasoning tier"),
  Implementation("I", "Implementation tier"),
}

@Composable
fun TierBadge(tier: Tier, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.xs)
  val (text, border) = when (tier) {
    Tier.Reasoning -> colors.stPlanning to colors.stPlanning.copy(alpha = 0.40f)
    Tier.Implementation -> colors.accentText to colors.accent.copy(alpha = 0.40f)
  }
  SbTooltip(tier.description) {
    Box(
      modifier.size(18.dp).border(1.dp, border, shape).semantics { contentDescription = tier.description },
      contentAlignment = Alignment.Center,
    ) {
      BasicText(tier.letter, style = SkillBillTheme.type.tXs.copy(color = text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
    }
  }
}
