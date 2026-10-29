package dev.skillbill.desktop.core.designsystem.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme

@Composable
fun Sidebar(
  top: @Composable () -> Unit,
  footer: @Composable ColumnScope.() -> Unit,
  modifier: Modifier = Modifier,
  items: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier.width(SkillBillSize.sidebar).fillMaxHeight().background(SkillBillTheme.colors.bgFrame)
      .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 10.dp),
    verticalArrangement = Arrangement.spacedBy(1.dp),
  ) {
    top()
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(1.dp), content = items)
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp), content = footer)
  }
}
