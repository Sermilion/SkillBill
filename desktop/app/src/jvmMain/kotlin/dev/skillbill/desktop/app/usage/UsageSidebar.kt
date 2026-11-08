package dev.skillbill.desktop.app.usage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.usage.UsageCard
import dev.skillbill.desktop.core.designsystem.usage.UsagePanel
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
fun UsageSidebar(model: UsageSidebarModel, modifier: Modifier = Modifier) {
  val state by model.view.collectAsState()
  val provider = state.provider ?: return
  val now by model.presentationTime.collectAsState()
  val focusRequester = remember { FocusRequester() }
  var expanded by remember { mutableStateOf(false) }
  var openedForToken by remember { mutableStateOf<Long?>(null) }
  var openFailure by remember(state.popupDismissalToken) { mutableStateOf<String?>(null) }
  val uriHandler = LocalUriHandler.current
  val signInRequired = state.result is UsageResult.SignInRequired
  val availabilityFlow = remember(model, state.popupDismissalToken, signInRequired) {
    if (signInRequired) model.recheckSignInAvailability() else model.refreshAvailability()
  }
  val refreshAvailability by availabilityFlow.collectAsState(initial = null)
  val ui = state.toUsageUiState(now, refreshAvailability)
  val refreshAllowed = state.demandEligible && refreshAvailability == UsageRefreshAvailability.Ready
  val refreshReason = when (val availability = refreshAvailability) {
    UsageRefreshAvailability.Ready -> null
    UsageRefreshAvailability.Checking -> "Checking usage"
    is UsageRefreshAvailability.Deferred -> "Refresh is deferred until ${availability.eligibleAt}"
    is UsageRefreshAvailability.Disabled -> availability.reason.refreshActionExplanation(provider, signInRequired)
    null -> "Refresh availability is loading"
  } ?: if (!state.demandEligible) "Usage is inactive while this sidebar is hidden" else null

  DisposableEffect(model) {
    model.setCardHostAttached(true)
    onDispose { model.setCardHostAttached(false) }
  }
  LaunchedEffect(state.popupDismissalToken) {
    if (openedForToken != null && openedForToken != state.popupDismissalToken) expanded = false
  }

  UsageCard(ui, onOpen = {
    openedForToken = state.popupDismissalToken
    expanded = true
  }, modifier = modifier.padding(bottom = 10.dp).focusRequester(focusRequester).semantics { contentDescription = "${ui.provider} usage limits. View details" })
  if (expanded) ShellLayer { expanded = false }
  SbPopover(
    expanded = expanded,
    onDismissRequest = { expanded = false },
    width = 316.dp,
    maxWidth = 420.dp,
    placement = SbPopoverPlacement.Above,
    returnFocusTo = focusRequester.takeIf { openedForToken == state.popupDismissalToken },
    title = "Usage limits",
  ) {
    UsagePanel(
      ui,
      Modifier.fillMaxWidth(),
      headerAction = { SbIconButton(LucideIcons.x, "Close usage details", { expanded = false }, size = SbControlSize.Small) },
      providerControls = { UsageProviderSelector(provider, state.choicesEnabled) { model.chooseManualProvider(it.agentId()) } },
      footerContent = {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SbButton(if (signInRequired) "Recheck sign-in" else "Refresh", { if (signInRequired) model.recheckSignIn() else model.refresh() }, enabled = refreshAllowed, size = SbControlSize.Small, variant = SbButtonVariant.Ghost, icon = LucideIcons.refresh, tooltip = refreshReason ?: "Refresh usage")
            SbButton("Dashboard", {
              openFailure = null
              try {
                uriHandler.openUri(provider.usageUrl())
              } catch (_: Exception) {
                model.recordDashboardOpenFailure()
                openFailure = "Could not open the provider usage page"
              }
            }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
          }
          if (!refreshAllowed) BasicText(refreshReason ?: "Refresh is unavailable for this source", style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgMuted))
          if (!state.choicesEnabled) BasicText("Provider is set by the current context", style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgMuted))
          if (provider == UsageProvider.Cursor) {
            BasicText("Cursor's dashboard can show current or legacy usage views. Check its usage guidance to identify the view for this account.", style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.fgMuted))
            SbButton("Cursor usage guidance", {
              openFailure = null
              try {
                uriHandler.openUri("https://cursor.com/help/models-and-usage/usage-limits")
              } catch (_: Exception) {
                model.recordDashboardOpenFailure()
                openFailure = "Could not open the provider usage guidance"
              }
            }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost, tooltip = "Official Cursor usage guidance")
          }
          openFailure?.let { BasicText(it, style = SkillBillTheme.type.t2xs.copy(color = SkillBillTheme.colors.stFailed)) }
        }
      },
    )
  }
}

@Composable
private fun UsageProviderSelector(selected: UsageProvider, enabled: Boolean, onSelect: (UsageProvider) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(colors.bgInset, shape).border(1.dp, colors.border, shape).padding(3.dp)
      .selectableGroup().semantics { contentDescription = "Provider" },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    listOf(UsageProvider.Codex, UsageProvider.Claude, UsageProvider.Cursor).forEach { provider ->
      val active = provider == selected
      val canSelect = enabled && !active
      val interaction = remember(provider) { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      val foreground = if (!canSelect && !active) colors.fgFaint else if (active || hovered) colors.fg else colors.fgMuted
      Box(
        Modifier.weight(1f).height(26.dp).background(if (active) colors.bgOverlay else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
          .hoverable(interaction).clickable(interaction, null, enabled = canSelect) { onSelect(provider) }
          .then(if (canSelect) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
          .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
          .semantics {
            role = Role.Tab
            this.selected = active
            contentDescription = provider.uiName()
            if (!canSelect && !active) stateDescription = "Provider is set by the current context"
          },
        contentAlignment = Alignment.Center,
      ) {
        CompositionLocalProvider(dev.skillbill.desktop.core.designsystem.LocalContentColor provides foreground) {
          BasicText(provider.uiName(), style = SkillBillTheme.type.tXs.copy(color = foreground), maxLines = 1)
        }
      }
    }
  }
}

private fun UsageProvider.agentId() = when (this) {
  UsageProvider.Codex -> "codex"
  UsageProvider.Claude -> "claude"
  UsageProvider.Cursor -> "cursor"
}

private fun UsageProvider.usageUrl() = when (this) {
  UsageProvider.Codex -> "https://chatgpt.com/codex/settings/usage"
  UsageProvider.Claude -> "https://claude.ai/settings/usage"
  UsageProvider.Cursor -> "https://cursor.com/dashboard"
}
