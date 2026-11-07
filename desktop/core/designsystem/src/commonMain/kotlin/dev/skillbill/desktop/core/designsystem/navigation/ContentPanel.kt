package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** `.sb-main` content sits inside its 1px border because `Modifier.border` draws over content. */
@Composable
fun ContentPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
  val shape = RoundedCornerShape(SkillBillRadius.lg)
  val colors = SkillBillTheme.colors
  Box(
    modifier.clip(shape).background(colors.bgPanel, shape).border(1.dp, colors.borderSubtle, shape).padding(1.dp),
    content = content,
  )
}
