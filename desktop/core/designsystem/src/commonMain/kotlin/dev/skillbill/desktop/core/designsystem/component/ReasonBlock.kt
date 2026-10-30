package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons

@Composable
fun ReasonBlock(label: String, text: String, onCopy: (String) -> Unit, color: Color = SkillBillTheme.colors.fgFaint) {
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Column(Modifier.fillMaxWidth().border(1.dp, SkillBillTheme.colors.borderSubtle, shape)
    .clip(shape).drawBehind { drawRect(color, Offset.Zero, Size(2.dp.toPx(), size.height)) }
    // padding 8px 8px 10px 12px inside a 2px left rule and 1px borders elsewhere.
    .padding(start = 14.dp, top = 9.dp, end = 9.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      BasicText(label, style = SkillBillTheme.type.tXs.copy(color = SkillBillTheme.colors.fgFaint, lineHeight = 1.em))
      SbIconButton(LucideIcons.copy, "Copy reason", { onCopy(text) }, size = SbControlSize.Small)
    }
    SelectionContainer { BasicText(text, style = SkillBillTheme.type.monoSm.copy(color = SkillBillTheme.colors.fg2, lineHeight = 1.55.em)) }
  }
}
