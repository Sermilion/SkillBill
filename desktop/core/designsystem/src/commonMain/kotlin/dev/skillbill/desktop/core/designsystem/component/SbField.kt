package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

interface SbFieldScope { val fieldModifier: Modifier; val isError: Boolean }

@Composable
fun SbField(label: String, help: String? = null, error: String? = null, content: @Composable SbFieldScope.() -> Unit) {
  val errorMessage = error
  val scope = object : SbFieldScope {
    override val fieldModifier = Modifier.semantics {
      contentDescription = if (help == null) label else "$label. $help"
      if (errorMessage != null) semanticsError(errorMessage)
    }
    override val isError = errorMessage != null
  }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText(label, style = SkillBillTheme.type.tSmMedium.copy(color = SkillBillTheme.colors.fg2, lineHeight = 1.2.em))
    content(scope)
    (error ?: help)?.let { text ->
      BasicText(text, Modifier.semantics { if (error != null) liveRegion = LiveRegionMode.Polite }, style = SkillBillTheme.type.tXs.copy(color = if (error != null) SkillBillTheme.colors.stFailed else SkillBillTheme.colors.fgFaint, fontWeight = FontWeight.Normal, lineHeight = 1.45.em))
    }
  }
}
