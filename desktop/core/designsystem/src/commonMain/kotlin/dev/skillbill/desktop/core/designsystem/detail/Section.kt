package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.tabular

@Composable
private fun SectionHeader(title: String, count: Int?, lead: (@Composable () -> Unit)?, actions: (@Composable RowScope.() -> Unit)?, modifier: Modifier = Modifier) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
    lead?.invoke()
    BasicText(title, Modifier.semantics { heading() }, style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1)
    count?.let { BasicText(it.toString(), style = type.tBase.tabular().copy(color = colors.fgFaint, fontWeight = FontWeight.Normal)) }
    if (actions != null) {
      Spacer(Modifier.weight(1f))
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
  }
}

@Composable
fun Section(
  title: String,
  modifier: Modifier = Modifier,
  count: Int? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  body: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    SectionHeader(title, count, lead, actions)
    body()
  }
}

/** Lazy form of [Section]: the header pins while the rows that [content] adds scroll beneath it. [headerBackground] must be opaque. */
@OptIn(ExperimentalFoundationApi::class)
fun LazyListScope.section(
  key: Any,
  title: String,
  count: Int? = null,
  lead: (@Composable () -> Unit)? = null,
  actions: (@Composable RowScope.() -> Unit)? = null,
  headerBackground: @Composable () -> Color = { SkillBillTheme.colors.bgPanel },
  content: LazyListScope.() -> Unit,
) {
  stickyHeader(key) {
    SectionHeader(title, count, lead, actions, Modifier.background(headerBackground()).padding(bottom = 10.dp))
  }
  content()
}

@Composable
fun SbBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(SkillBillRadius.md)
  Column(modifier.fillMaxWidth().clip(shape).border(1.dp, SkillBillTheme.colors.borderSubtle, shape), content = content)
}
