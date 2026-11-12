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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.menuSpec
import dev.skillbill.desktop.core.ui.input.ShellLayer
import dev.skillbill.desktop.core.ui.generated.resources.Res
import dev.skillbill.desktop.core.ui.generated.resources.pane_resize_separator
import dev.skillbill.desktop.core.ui.generated.resources.pane_set_width
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun FloatingPane(
  open: Boolean,
  title: String,
  requestedWidthDp: Int?,
  defaultWidth: Dp,
  onResize: (Int) -> Unit,
  onReset: () -> Unit,
  onDismiss: () -> Unit,
  modal: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colors = SkillBillTheme.colors
  val resizeDescription = stringResource(Res.string.pane_resize_separator, title)
  val resizeAction = stringResource(Res.string.pane_set_width, title)
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 760.dp
    val maximum = if (compact) maxWidth else maxWidth - 64.dp
    val minimum = 272.dp
    val preferred = requestedWidthDp?.dp ?: minOf(defaultWidth, maxWidth * 0.70f)
    val width = if (compact) maxWidth else preferred.coerceIn(minimum, maximum)
    var dragWidth by remember(requestedWidthDp, maxWidth) { mutableStateOf<Dp?>(null) }
    val displayedWidth = dragWidth ?: width
    fun resizeTo(requested: Dp) {
      val next = requested.coerceIn(minimum, maximum)
      dragWidth = next
      onResize(next.value.roundToInt())
    }

    AnimatedVisibility(
      visible = open,
      modifier = Modifier.align(Alignment.CenterEnd).width(displayedWidth).fillMaxHeight(),
      enter = slideInHorizontally(animationSpec = menuSpec()) { it },
      exit = slideOutHorizontally(animationSpec = menuSpec()) { it },
    ) {
      if (open && modal) ShellLayer(onDismiss)
      Row(
        Modifier.fillMaxSize().shadow(16.dp).background(colors.bgPanel)
          .pointerInput(Unit) { detectTapGestures { } }
          .semantics { paneTitle = title },
      ) {
        if (!compact) PaneResizeGrip(
          title = title,
          enabled = true,
          gripWidth = 8.dp,
          keyStep = 24.dp,
          additionalSemantics = {
            contentDescription = resizeDescription
            progressBarRangeInfo = ProgressBarRangeInfo(displayedWidth.value, minimum.value..maximum.value)
            setProgress(label = resizeAction) { requested ->
              if (!requested.isFinite()) false else {
                resizeTo(requested.dp)
                true
              }
            }
          },
          onDrag = { delta ->
            resizeTo((dragWidth ?: width) - delta)
          },
          onStep = { step ->
            resizeTo((dragWidth ?: width) + step)
          },
          onDoubleClick = { dragWidth = null; onReset() },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
      }
    }
  }
}
