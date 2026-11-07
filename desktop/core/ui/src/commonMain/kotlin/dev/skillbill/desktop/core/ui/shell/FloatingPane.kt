package dev.skillbill.desktop.core.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.menuSpec
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
fun FloatingPane(open: Boolean, title: String, wide: Boolean = false, onDismiss: () -> Unit, content: @Composable () -> Unit) {
  val colors = SkillBillTheme.colors
  var requestedWidth by remember(wide) { mutableStateOf<Dp?>(null) }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 760.dp
    val maximum = if (compact) maxWidth else maxWidth - 64.dp
    val minimum = minOf(360.dp, maximum)
    val preferred = requestedWidth ?: minOf(if (wide) 800.dp else 560.dp, maxWidth * 0.70f)
    val width = if (compact) maximum else preferred.coerceIn(minimum, maximum)
    AnimatedVisibility(
      visible = open,
      modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight(),
      enter = slideInHorizontally(animationSpec = menuSpec()) { it },
      exit = slideOutHorizontally(animationSpec = menuSpec()) { it },
    ) {
      if (open) ShellLayer(onDismiss)
      Row(
        Modifier.fillMaxSize().shadow(16.dp).background(colors.bgPanel)
          .pointerInput(Unit) { detectTapGestures { } }
          .semantics { paneTitle = title },
      ) {
        PaneResizeGrip(
          title = title,
          enabled = !compact,
          onDrag = { delta -> requestedWidth = ((requestedWidth ?: width).coerceIn(minimum, maximum) - delta).coerceIn(minimum, maximum) },
          onStep = { step -> requestedWidth = (width + step).coerceIn(minimum, maximum) },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
      }
    }
  }
}
