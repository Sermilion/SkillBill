package dev.skillbill.desktop.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalReducedMotion = staticCompositionLocalOf { false }

object SkillBillMotion {
  const val HOVER_MS = 90
  const val MENU_MS = 160
  const val TOAST_IN_MS = 220
  val easing = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)
}

@Composable
fun <T> hoverSpec(): FiniteAnimationSpec<T> =
  if (LocalReducedMotion.current) snap() else tween(SkillBillMotion.HOVER_MS, easing = SkillBillMotion.easing)

@Composable
fun <T> menuSpec(): FiniteAnimationSpec<T> =
  if (LocalReducedMotion.current) snap() else tween(SkillBillMotion.MENU_MS, easing = SkillBillMotion.easing)

@Composable
fun <T> toastInSpec(): FiniteAnimationSpec<T> =
  if (LocalReducedMotion.current) snap() else tween(SkillBillMotion.TOAST_IN_MS, easing = SkillBillMotion.easing)
