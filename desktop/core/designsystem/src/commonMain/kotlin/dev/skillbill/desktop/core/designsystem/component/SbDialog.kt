package dev.skillbill.desktop.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillSize
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.layeredShadow
import dev.skillbill.desktop.core.designsystem.menuSpec

private val LocalSbDialogTextStyle = staticCompositionLocalOf { TextStyle.Default }

/**
 * Provides the default body text style through [SbDialogText] and [SbDialogTextStyle].
 * An unmodified Foundation [BasicText] does not inherit this style. Pass [SbDialogTextStyle]
 * to it explicitly when it needs the dialog default. Explicitly styled body content remains valid.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SbDialog(title: String, onDismissRequest: () -> Unit, icon: (@Composable () -> Unit)? = null, returnFocusTo: FocusRequester? = null, actions: @Composable RowScope.() -> Unit, body: @Composable ColumnScope.() -> Unit) {
  Dialog(onDismissRequest, DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true, usePlatformDefaultWidth = false, scrimColor = SkillBillTheme.colors.scrim)) {
    DisposableEffect(Unit) { onDispose { returnFocusTo?.requestFocus() } }
    val dialogFocus = remember { FocusRequester() }
    var fallbackFocus by remember { mutableStateOf(false) }
    LaunchedEffect(fallbackFocus) {
      if (fallbackFocus) dialogFocus.requestFocus()
      else if (!dialogFocus.requestFocus()) fallbackFocus = true
    }
    BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = onDismissRequest)) {
      val shape = RoundedCornerShape(SkillBillRadius.lg)
      val dialogWidth = SkillBillSize.dialog.coerceAtMost((maxWidth - 32.dp).coerceAtLeast(0.dp))
      // A visible = true AnimatedVisibility skips its enter transition, so start from a hidden state.
      val appear = remember { MutableTransitionState(false).apply { targetState = true } }
      val liftPx = with(LocalDensity.current) { 2.dp.roundToPx() }
      // Layer padding-top 14vh plus the dialog's margin-top 4vh.
      AnimatedVisibility(appear, modifier = Modifier.align(Alignment.TopCenter).padding(top = maxHeight * .18f), enter = fadeIn(menuSpec()) + slideInVertically(menuSpec()) { -liftPx } + scaleIn(menuSpec(), initialScale = .98f)) {
        Column(
          Modifier.width(dialogWidth).layeredShadow(SkillBillTheme.shadows.modal, shape).background(SkillBillTheme.colors.bgOverlay, shape)
            .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
            .border(1.dp, SkillBillTheme.colors.borderStrong, shape)
            // Header, body and footer paddings sit inside this 1px border.
            .padding(1.dp)
            .verticalScroll(rememberScrollState())
            .focusRequester(dialogFocus).focusProperties { onExit = { cancelFocusChange() } }.focusGroup().focusable(fallbackFocus).semantics { paneTitle = title }
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { onDismissRequest(); true } else false },
        ) {
          Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.invoke()
            BasicText(title, style = SkillBillTheme.type.tMd.copy(color = SkillBillTheme.colors.fg, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, lineHeight = 1.3.em))
          }
          CompositionLocalProvider(
            LocalSbDialogTextStyle provides SkillBillTheme.type.tBase.copy(color = SkillBillTheme.colors.fgMuted, lineHeight = 1.55.em),
            LocalContentColor provides SkillBillTheme.colors.fgMuted,
          ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = body)
          }
          FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
      }
    }
  }
}

/**
 * Reads the default text style provided to the current dialog body.
 * Call this inside [SbDialog]'s body when passing the style to Foundation [BasicText].
 */
@Composable
fun SbDialogTextStyle(): TextStyle = LocalSbDialogTextStyle.current

/**
 * Renders dialog body text with the dialog default style.
 * An explicit [style] overrides that default. Foundation [BasicText] does not inherit the
 * default unless the caller passes [SbDialogTextStyle] as its style.
 */
@Composable
fun SbDialogText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalSbDialogTextStyle.current) {
  BasicText(text, modifier, style = style)
}

/**
 * Renders annotated dialog body text with the dialog default style while preserving its spans.
 * An explicit [style] overrides that default. Foundation [BasicText] does not inherit the
 * default unless the caller passes [SbDialogTextStyle] as its style.
 */
@Composable
fun SbDialogText(text: AnnotatedString, modifier: Modifier = Modifier, style: TextStyle = LocalSbDialogTextStyle.current) {
  BasicText(text, modifier, style = style)
}
