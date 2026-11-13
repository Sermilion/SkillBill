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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.component.Avatar
import dev.skillbill.desktop.core.designsystem.component.agentDisplayName
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbTooltip
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbIconButton
import dev.skillbill.desktop.core.designsystem.component.SbPopover
import dev.skillbill.desktop.core.designsystem.component.SbPopoverPlacement
import dev.skillbill.desktop.core.designsystem.component.sbFocusRing
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.usage.UsageCard
import dev.skillbill.desktop.core.designsystem.usage.UsagePanel
import dev.skillbill.desktop.core.designsystem.usage.usageAgentColor
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshAvailability
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.ui.input.ShellLayer

@Composable
fun UsageSidebar(model: UsageSidebarModel, modifier: Modifier = Modifier) {
  val state by model.view.collectAsState()
  val now by model.presentationTime.collectAsState()
  val focusRequester = remember { FocusRequester() }
  var expanded by remember { mutableStateOf(false) }
  var openedForToken by remember { mutableStateOf<Long?>(null) }
  val uriHandler = LocalUriHandler.current
  val signInRequired = state.result is UsageResult.SignInRequired ||
    (state.result as? UsageResult.Snapshot)?.value?.refreshFailure?.code == UsageUnavailableCode.ProfileUnverified
  val availabilityFlow = remember(model, state.requestVersion, signInRequired) {
    if (signInRequired) model.recheckSignInAvailability() else model.refreshAvailability()
  }
  val refreshAvailability by availabilityFlow.collectAsState(initial = null)
  val ui = state.toUsageUiState(now)
  val defaultAgent by model.defaultAgent.collectAsState()
  val refreshAction = { if (signInRequired) model.recheckSignIn() else model.refresh() }
  val refreshAllowed = state.demandEligible && refreshAvailability == UsageRefreshAvailability.Ready

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
  }, modifier = modifier.padding(top = 8.dp).focusRequester(focusRequester).semantics { contentDescription = "${ui.provider} usage limits. View details" },
    onRefresh = refreshAction.takeIf { state.nameOnlyAgent == null && state.provider != null },
    refreshEnabled = refreshAllowed,
    refreshLabel = if (refreshAvailability == UsageRefreshAvailability.Checking) "Checking" else "Refresh",
  )
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
      providerControls = { UsageProviderSelector(state.provider.takeIf { state.nameOnlyAgent == null }) { model.chooseManualProvider(it.agentId()) } },
      settingsContent = { if (model.agents.isNotEmpty()) DefaultAgentSelector(model.agents, defaultAgent, model::toggleDefaultAgent) },
      footerContent = {
        // An agent without a usage source has nothing to refresh and no usage page.
        if (state.nameOnlyAgent == null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          SbButton(
            if (signInRequired) "Recheck sign-in" else "Refresh",
            refreshAction,
            enabled = refreshAllowed,
            size = SbControlSize.Small,
            variant = SbButtonVariant.Ghost,
            icon = LucideIcons.refresh,
            tooltip = if (refreshAvailability == UsageRefreshAvailability.Checking) "Checking usage" else "Refresh usage",
          )
          state.provider?.usageUrl()?.let { url ->
            SbButton("Dashboard", {
              try {
                uriHandler.openUri(url)
              } catch (_: Exception) {
                model.recordDashboardOpenFailure()
              }
            }, size = SbControlSize.Small, variant = SbButtonVariant.Ghost)
          }
        }
      },
    )
  }
}

/** Agent chips for the default agent; the active chip clears the default when clicked again. */
@Composable
private fun DefaultAgentSelector(agents: List<String>, selected: String?, onToggle: (String) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    BasicText("Default agent", style = SkillBillTheme.type.tXs.copy(color = colors.fgMuted, fontWeight = FontWeight.Normal))
    Row(
      Modifier.fillMaxWidth().background(colors.bgInset, shape).border(1.dp, colors.border, shape).padding(3.dp)
        .selectableGroup().semantics { contentDescription = "Default agent for new chats, goals and phases" },
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      agents.forEach { agent ->
        val active = agent.equals(selected, ignoreCase = true)
        val interaction = remember(agent) { MutableInteractionSource() }
        val hovered by interaction.collectIsHoveredAsState()
        val foreground = if (active || hovered) usageAgentColor(agent) else colors.fgMuted
        Box(Modifier.weight(1f)) {
          SbTooltip(if (active) "Default for new work. Click to clear" else "Use ${agent.agentLabel()} for new chats, goals and phases") {
            Row(
              Modifier.fillMaxWidth().height(26.dp).background(if (active) colors.bgOverlay else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
                .hoverable(interaction).clickable(interaction, null) { onToggle(agent) }
                .pointerHoverIcon(PointerIcon.Hand)
                .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
                .semantics {
                  role = Role.RadioButton
                  this.selected = active
                  contentDescription = agent.agentLabel()
                },
              horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Avatar(agent, small = true, tooltip = null)
              BasicText(agent.agentLabel(), style = SkillBillTheme.type.tXs.copy(color = foreground, fontWeight = FontWeight.Normal), maxLines = 1)
            }
          }
        }
      }
    }
  }
}

private fun String.agentLabel() = agentDisplayName(this)

@Composable
private fun UsageProviderSelector(selected: UsageProvider?, onSelect: (UsageProvider) -> Unit) {
  val colors = SkillBillTheme.colors
  val shape = RoundedCornerShape(SkillBillRadius.sm)
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(colors.bgInset, shape).border(1.dp, colors.border, shape).padding(3.dp)
      .selectableGroup().semantics { contentDescription = "Provider" },
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    UsageProvider.entries.forEach { provider ->
      val active = provider == selected
      val interaction = remember(provider) { MutableInteractionSource() }
      val hovered by interaction.collectIsHoveredAsState()
      val agentColor = usageAgentColor(provider.agentId())
      val foreground = if (active || hovered) agentColor else colors.fgMuted
      Box(
        Modifier.weight(1f).height(26.dp).background(if (active) colors.bgOverlay else Color.Transparent, RoundedCornerShape(SkillBillRadius.xs))
          .hoverable(interaction).clickable(interaction, null, enabled = !active) { onSelect(provider) }
          .then(if (!active) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
          .sbFocusRing(interaction, RoundedCornerShape(SkillBillRadius.xs))
          .semantics {
            role = Role.Tab
            this.selected = active
            contentDescription = provider.uiName()
          },
        contentAlignment = Alignment.Center,
      ) {
        CompositionLocalProvider(dev.skillbill.desktop.core.designsystem.LocalContentColor provides foreground) {
          BasicText(provider.uiName(), style = SkillBillTheme.type.tXs.copy(color = foreground, fontWeight = FontWeight.Normal), maxLines = 1)
        }
      }
    }
  }
}

/** The provider's own usage page. */
private fun UsageProvider.usageUrl(): String = when (this) {
  UsageProvider.Codex -> "https://chatgpt.com/codex/settings/usage"
  UsageProvider.Claude -> "https://claude.ai/settings/usage"
}
