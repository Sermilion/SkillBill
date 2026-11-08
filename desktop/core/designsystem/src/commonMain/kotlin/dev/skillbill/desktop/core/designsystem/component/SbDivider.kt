package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SbDivider(modifier: Modifier = Modifier, vertical: Boolean = false, color: Color = SkillBillTheme.colors.borderSubtle) {
  val line = if (vertical) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp)
  Box(modifier.then(line).background(color))
}
