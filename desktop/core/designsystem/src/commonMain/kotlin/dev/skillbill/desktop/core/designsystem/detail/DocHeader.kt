package dev.skillbill.desktop.core.designsystem.detail

import dev.skillbill.desktop.core.designsystem.component.rememberRuntimeText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

/**
 * `.sb-doc-hd`: eyebrow chips, the 24/600 title, the muted sub line, then the actions, 10 apart. [sub] replaces [subtitle] when
 * the sub line holds controls, such as the repository location buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocHeader(
  title: String,
  modifier: Modifier = Modifier,
  eyebrow: (@Composable FlowRowScope.() -> Unit)? = null,
  subtitle: String? = null,
  sub: (@Composable () -> Unit)? = null,
  actions: (@Composable FlowRowScope.() -> Unit)? = null,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    eyebrow?.let {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, content = it)
    }
    BasicText(rememberRuntimeText(title), Modifier.semantics { heading() }, style = type.t2xl.copy(color = colors.fg))
    when {
      sub != null -> CompositionLocalProvider(LocalContentColor provides colors.fgMuted) { sub() }
      subtitle != null -> BasicText(subtitle, style = type.tBase.copy(color = colors.fgMuted, lineHeight = 1.55.em))
    }
    actions?.let {
      FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically, content = it)
    }
  }
}
