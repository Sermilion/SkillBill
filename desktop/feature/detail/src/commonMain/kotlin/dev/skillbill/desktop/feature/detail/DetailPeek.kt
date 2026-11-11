package dev.skillbill.desktop.feature.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.menuSpec
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
internal fun DetailPeek(open: Boolean, wide: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val paneWidth = minOf(if (wide) SkillBillSize.detailsPaneWide else SkillBillSize.detailsPane, maxWidth)
    AnimatedVisibility(
      visible = open,
      modifier = Modifier.align(Alignment.CenterEnd).width(paneWidth).fillMaxHeight(),
      enter = slideInHorizontally(animationSpec = menuSpec()) { it },
      exit = slideOutHorizontally(animationSpec = menuSpec()) { it },
    ) {
      if (open) ShellLayer(onDismiss)
      Box(
        Modifier.fillMaxSize().shadow(16.dp).background(colors.bgPanel)
          .pointerInput(Unit) { detectTapGestures { } }
          .semantics { paneTitle = "Details" },
      ) {
        Column(Modifier.fillMaxHeight()) {
          Row(Modifier.height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Details", Modifier.weight(1f), style = SkillBillTheme.type.tBaseMedium.copy(color = colors.fg))
            SbIconButton(LucideIcons.x, "Close details", onDismiss)
          }
          Box(Modifier.weight(1f)) { content() }
        }
      }
    }
  }
}

internal fun detailsPaneWide(item: dev.skillbill.desktop.core.domain.model.WorkItem): Boolean =
  item.kind == dev.skillbill.desktop.core.domain.model.WorkKind.Goal
