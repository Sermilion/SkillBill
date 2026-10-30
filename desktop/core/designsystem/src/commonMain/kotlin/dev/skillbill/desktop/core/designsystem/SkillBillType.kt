package dev.skillbill.desktop.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontWeight.Companion.Medium
import androidx.compose.ui.text.font.FontWeight.Companion.Normal
import androidx.compose.ui.text.font.FontWeight.Companion.SemiBold
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.generated.resources.Res
import dev.skillbill.desktop.core.designsystem.generated.resources.inter
import dev.skillbill.desktop.core.designsystem.generated.resources.jetbrains_mono
import org.jetbrains.compose.resources.Font

@Immutable
data class SkillBillType(
  val t2xl: TextStyle,
  val tXl: TextStyle,
  val tLg: TextStyle,
  val tLgStrong: TextStyle,
  val tMd: TextStyle,
  val tBase: TextStyle,
  val tBaseMedium: TextStyle,
  val tSm: TextStyle,
  val tSmMedium: TextStyle,
  val tXs: TextStyle,
  val t2xs: TextStyle,
  val mono: TextStyle,
  val monoSm: TextStyle,
)

@Composable
internal fun rememberSkillBillType(): SkillBillType {
  val interFonts = listOf(
    Font(Res.font.inter, weight = Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(Res.font.inter, weight = Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(Res.font.inter, weight = SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(Res.font.inter, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
  )
  val monoFonts = listOf(
    Font(Res.font.jetbrains_mono, weight = Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(Res.font.jetbrains_mono, weight = Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(Res.font.jetbrains_mono, weight = SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(Res.font.jetbrains_mono, weight = FontWeight(800), variationSettings = FontVariation.Settings(FontVariation.weight(800))),
  )
  val inter = remember(interFonts) { FontFamily(*interFonts.toTypedArray()) }
  val mono = remember(monoFonts) { FontFamily(*monoFonts.toTypedArray()) }
  // Sizes below t2xl/tXl inherit `body { line-height: 1.5 }`; components override where CSS sets its own.
  return remember(inter, mono) {
    SkillBillType(
      t2xl = interStyle(inter, 24f, SemiBold, 30f, (-0.012).em),
      tXl = interStyle(inter, 20f, SemiBold, 25f),
      tLg = interStyle(inter, 16f, Normal, 24f),
      tLgStrong = interStyle(inter, 16f, SemiBold, 24f),
      tMd = interStyle(inter, 14f, Medium, 21f),
      tBase = interStyle(inter, 13f, Normal, 19.5f),
      tBaseMedium = interStyle(inter, 13f, Medium, 19.5f),
      tSm = interStyle(inter, 12f, Normal, 18f),
      tSmMedium = interStyle(inter, 12f, Medium, 18f),
      tXs = interStyle(inter, 11.5f, Medium, 17.25f),
      t2xs = monoStyle(mono, 10.5f, Medium, 15.75f),
      mono = monoStyle(mono, 13f, Normal, 19.5f),
      monoSm = monoStyle(mono, 12f, Normal, 18f),
    )
  }
}

/** CSS splits line-height leading evenly above and below the glyphs; Compose defaults to proportional. */
private val CssLineHeight = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

fun TextStyle.tabular(): TextStyle =
  copy(fontFeatureSettings = listOfNotNull(fontFeatureSettings, "tnum").joinToString(", "))

private fun interStyle(
  family: FontFamily,
  size: Float,
  weight: FontWeight,
  lineHeight: Float,
  letterSpacing: TextUnit = TextUnit.Unspecified,
) = TextStyle(
  fontFamily = family,
  fontWeight = weight,
  fontSize = size.sp,
  lineHeight = lineHeight.sp,
  letterSpacing = letterSpacing,
  fontFeatureSettings = "cv11, ss01, ss03",
  lineHeightStyle = CssLineHeight,
)

private fun monoStyle(family: FontFamily, size: Float, weight: FontWeight, lineHeight: Float) = TextStyle(
  fontFamily = family,
  fontWeight = weight,
  fontSize = size.sp,
  lineHeight = lineHeight.sp,
  lineHeightStyle = CssLineHeight,
)
