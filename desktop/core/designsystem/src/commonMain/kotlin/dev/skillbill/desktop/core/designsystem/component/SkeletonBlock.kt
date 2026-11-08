package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SkeletonBlock(modifier: Modifier, shape: Shape = RoundedCornerShape(SkillBillRadius.xs)) {
  Box(modifier.background(SkillBillTheme.colors.bgActive, shape))
}
