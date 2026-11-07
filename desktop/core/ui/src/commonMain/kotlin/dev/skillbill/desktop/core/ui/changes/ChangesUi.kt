package dev.skillbill.desktop.core.ui.changes

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/** The Changes surfaces' monospace text: [size] sp at line height 1 unless [lineHeight] says otherwise. */
@Composable
fun changesMono(size: Float, weight: FontWeight, color: Color, lineHeight: Float = size): TextStyle =
  SkillBillTheme.type.monoSm.copy(color = color, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)

/** The Changes surfaces' sans text: [size] sp at line height 1 unless [lineHeight] says otherwise. */
@Composable
fun changesSans(size: Float, weight: FontWeight, color: Color, lineHeight: Float = size): TextStyle =
  SkillBillTheme.type.tSm.copy(color = color, fontSize = size.sp, fontWeight = weight, lineHeight = lineHeight.sp)

fun filesLabel(count: Int): String = if (count == 1) "1 file" else "$count files"

@Composable
fun AddCount(count: Int, modifier: Modifier = Modifier) {
  BasicText("+$count", modifier, style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.stDone), maxLines = 1, softWrap = false)
}

@Composable
fun DelCount(count: Int, modifier: Modifier = Modifier) {
  BasicText("−$count", modifier, style = changesMono(11f, FontWeight.Medium, SkillBillTheme.colors.stFailed), maxLines = 1, softWrap = false)
}
