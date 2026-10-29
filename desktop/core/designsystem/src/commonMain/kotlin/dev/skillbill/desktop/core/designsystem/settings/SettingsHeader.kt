package dev.skillbill.desktop.core.designsystem.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun SettingsHeader(title: String, modifier: Modifier = Modifier, body: String? = null) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier) {
    BasicText(
      title, Modifier.padding(bottom = 6.dp).semantics { heading() },
      style = type.tXl.copy(color = colors.fg, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
    )
    body?.let { BasicText(it, Modifier.widthIn(max = 560.dp), style = type.tBase.copy(color = colors.fgMuted, lineHeight = 1.55.em)) }
  }
}
