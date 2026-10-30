package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.toastInSpec
import dev.skillbill.desktop.core.designsystem.icon.BrandMark
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.designsystem.status.StatusIcon
import dev.skillbill.desktop.core.designsystem.status.StatusKind

private val TOAST_WINDOW_INSET = 32.dp

sealed interface ToastLead { data class Icon(val icon: androidx.compose.ui.graphics.vector.ImageVector) : ToastLead; data class Status(val kind: StatusKind, val progress: Float? = null) : ToastLead; data object System : ToastLead }
data class ToastAction(val label: String, val onClick: () -> Unit)
data class ToastData(val title: String, val body: String? = null, val lead: ToastLead = ToastLead.Icon(dev.skillbill.desktop.core.designsystem.icon.LucideIcons.checkCircle), val appName: String? = null, val time: String? = null, val actions: List<ToastAction> = emptyList())
data class ShownToast(val id: Long, val data: ToastData)

@Stable
class ToastHostState {
  private val mutableToasts = mutableStateListOf<ShownToast>()
  val toasts: List<ShownToast> get() = mutableToasts
  private var nextId = 0L
  fun show(data: ToastData) { mutableToasts.add(ShownToast(++nextId, data)); while (mutableToasts.size > 3) mutableToasts.removeAt(0) }
  fun dismiss(id: Long) { mutableToasts.removeAll { it.id == id } }
}

@Composable
fun rememberToastHostState() = remember { ToastHostState() }

private const val TOAST_LIFETIME_MS = 5_000L

@Composable
fun Toast(data: ToastData, onDismiss: () -> Unit) {
  val currentOnDismiss = rememberUpdatedState(onDismiss)
  LaunchedEffect(Unit) {
    delay(TOAST_LIFETIME_MS)
    currentOnDismiss.value()
  }
  val density = LocalDensity.current
  val maxWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() - TOAST_WINDOW_INSET }.coerceAtLeast(0.dp)
  val toastWidth = SkillBillSize.toast.coerceAtMost(maxWidth)
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.widthIn(max = toastWidth).width(toastWidth).layeredShadow(SkillBillTheme.shadows.popover, shape)
      .background(colors.bgOverlay, shape).border(1.dp, colors.borderStrong, shape)
      // `.sb-toast` pads 12px 12px 12px 14px inside its 1px border.
      .semantics { liveRegion = LiveRegionMode.Polite }.padding(start = 15.dp, top = 13.dp, end = 13.dp, bottom = 13.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
  ) {
    // Only the status icon and brand mark get margin-top: 2px; a plain icon sits on the top edge.
    when (val lead = data.lead) {
      is ToastLead.Icon -> CompositionLocalProvider(LocalContentColor provides colors.fg) { SbIcon(lead.icon, SbIconSize.Md) }
      is ToastLead.Status -> StatusIcon(lead.kind, lead.progress, modifier = Modifier.padding(top = 2.dp))
      ToastLead.System -> Box(Modifier.padding(top = 2.dp)) { BrandMark(18.dp) }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      if (data.appName != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val appStyle = type.tXs.copy(color = colors.fgFaint, lineHeight = 1.3.em)
        BasicText(data.appName, Modifier.weight(1f), style = appStyle, maxLines = 1)
        BasicText(data.time ?: "now", style = appStyle, maxLines = 1)
      }
      BasicText(data.title, style = type.tBaseMedium.copy(color = colors.fg, lineHeight = 1.35.em))
      data.body?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted, lineHeight = 1.4.em)) }
    }
    // `.sb-toast-ac` is a sibling of the text column, centered on the cross axis.
    if (data.actions.isNotEmpty()) Row(Modifier.align(Alignment.CenterVertically), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      data.actions.forEach { SbButton(it.label, it.onClick, variant = SbButtonVariant.Ghost, size = SbControlSize.Small) }
    }
  }
}

@Composable
fun ToastStack(state: ToastHostState) {
  val density = LocalDensity.current
  val slideOffsetPx = with(density) { 8.dp.roundToPx() }
  Box(Modifier.fillMaxSize()) {
    Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
      state.toasts.forEach { shown ->
        key(state, shown.id) {
          // A visible = true AnimatedVisibility skips its enter transition, so start from a hidden state.
          val appear = remember { MutableTransitionState(false).apply { targetState = true } }
          AnimatedVisibility(appear, enter = fadeIn(toastInSpec()) + slideInVertically(animationSpec = toastInSpec(), initialOffsetY = { slideOffsetPx })) {
            Toast(shown.data) { state.dismiss(shown.id) }
          }
        }
      }
    }
  }
}
