package dev.skillbill.desktop.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalSkillBillColors = staticCompositionLocalOf { DarkSkillBillColors }
val LocalSkillBillType = staticCompositionLocalOf<SkillBillType> { error("SkillBillTheme is not provided") }
val LocalSkillBillShadows = staticCompositionLocalOf { DarkSkillBillShadows }
val LocalContentColor = staticCompositionLocalOf { DarkSkillBillColors.fg }

@Composable
fun SkillBillTheme(reducedMotion: Boolean, content: @Composable () -> Unit) {
  val type = rememberSkillBillType()
  CompositionLocalProvider(
    LocalSkillBillColors provides DarkSkillBillColors,
    LocalSkillBillType provides type,
    LocalSkillBillShadows provides DarkSkillBillShadows,
    LocalReducedMotion provides reducedMotion,
    LocalContentColor provides DarkSkillBillColors.fg,
    content = content,
  )
}

object SkillBillTheme {
  val colors: SkillBillColors
    @Composable @ReadOnlyComposable get() = LocalSkillBillColors.current
  val type: SkillBillType
    @Composable @ReadOnlyComposable get() = LocalSkillBillType.current
  val shadows: SkillBillShadows
    @Composable @ReadOnlyComposable get() = LocalSkillBillShadows.current
}
