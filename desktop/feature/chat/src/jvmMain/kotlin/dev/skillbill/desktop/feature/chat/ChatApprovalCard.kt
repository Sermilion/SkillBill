package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.LocalContentColor
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.designsystem.icon.SbIcon
import dev.skillbill.desktop.core.designsystem.icon.SbIconSize
import dev.skillbill.desktop.core.domain.model.ApprovalCard
import dev.skillbill.desktop.core.domain.model.ApprovalCardKey
import dev.skillbill.desktop.core.domain.model.ApprovalCardOption
import dev.skillbill.desktop.core.domain.model.PROVIDER_REMEMBER_OPTION_KIND

private const val ALLOW_KIND = "allow"
private const val DENY_KIND = "deny"
private val INPUT_MAX_HEIGHT = 220.dp

/**
 * What a card surface can do. [live] is the feed's registration for the card's repository; the decision callbacks send an
 * explicit click, and the runtime still refuses it unless a live, matching registration owns the request.
 */
internal data class ChatApprovalActions(
  val live: Boolean,
  val onDecide: (key: ApprovalCardKey, optionId: String, alwaysAllow: Boolean) -> Unit,
  val onDismiss: (key: ApprovalCardKey) -> Unit,
  /** Opens the repository's durable-rule list. It lists only and never decides; null hides the entry. */
  val onOpenRules: (() -> Unit)? = null,
)

/** Why a pending card's controls are off, or null when they are on. A restored or stored card is never on. */
internal fun ChatMessage.Approval.controlsOffReason(live: Boolean): String? = when {
  !card.isPending -> null
  restoredCopy -> "Restored from history. This copy cannot be answered."
  !live -> "This window does not hold the operator registration for this repository, so nothing can be sent."
  card.grants == null -> "Waiting for the runtime to confirm what this request may be granted."
  submitting -> "Sending your answer."
  else -> null
}

/** The outcome in words, from the stored record alone, so a restored card reads the same as a live one. */
internal fun ApprovalCard.outcomeText(): String = when (state) {
  "pending" -> "Waiting for your answer"
  "allowed" -> when (decisionSource) {
    "operator_rule" -> "Allowed, and always allowed for this repository"
    "stored_rule" -> "Allowed by a saved rule"
    else -> "Allowed once"
  }
  "denied" -> "Denied"
  "cancelled" -> "Dismissed or cancelled, so it was refused"
  "expired" -> "Expired before it was answered"
  "unsupported" -> "Not supported, so it was refused"
  "process_ended" -> "The process ended before it was answered"
  else -> state
}

private fun ApprovalCard.deliveryText(): String? = when (delivery) {
  "claimed" -> "The answer is claimed and about to be written to the process."
  "written" -> "The answer was written to the process."
  "uncertain" -> "It is not certain the process received the answer."
  "not_required" -> "The provider cleared the request before a response was needed."
  else -> null
}

/** One permission request of the chat's process: the complete stored record, its outcome and, while live, its controls. */
@Composable
internal fun ChatApprovalCard(message: ChatMessage.Approval, actions: ChatApprovalActions?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val card = message.card
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val offReason = message.controlsOffReason(actions?.live == true)
  val outcome = card.outcomeText()
  val decision = if (card.isPending) emptyList() else card.decisionLines()
  Column(
    Modifier.fillMaxWidth()
      .border(1.dp, if (card.isPending) colors.borderStrong else colors.border, shape)
      .background(colors.bgRaised, shape)
      .padding(12.dp)
      .semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = listOf("Permission request ${card.title}", outcome).plus(decision).joinToString(". ") + "."
      },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides if (card.isPending) colors.accentText else colors.fgMuted) {
        SbIcon(LucideIcons.shield, SbIconSize.Md)
      }
      BasicText(card.title, Modifier.weight(1f), style = type.tBaseMedium.copy(color = colors.fg))
      BasicText(outcome, style = type.tSm.copy(color = if (card.isPending) colors.accentText else colors.fgMuted))
    }
    SelectionContainer {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.reason?.let { BasicText(it, style = type.tSm.copy(color = colors.fg2)) }
        BasicText(card.subject, style = type.tBase.copy(color = colors.fg))
        if (card.inputText.isNotBlank()) {
          // The whole input is kept; a long one scrolls inside its own bounded box instead of being cut.
          BasicText(
            card.inputText,
            Modifier.fillMaxWidth().heightIn(max = INPUT_MAX_HEIGHT).background(colors.bgOverlay, RoundedCornerShape(SkillBillRadius.sm))
              .verticalScroll(rememberScrollState()).padding(8.dp),
            style = type.tSm.copy(color = colors.fg2, fontFamily = FontFamily.Monospace),
          )
        }
        BasicText(card.metaText(), style = type.tSm.copy(color = colors.fgMuted))
        decision.forEach { BasicText(it, style = type.tSm.copy(color = colors.fg2)) }
        card.deliveryText()?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted)) }
        if (card.providerDisposition != "none") {
          BasicText("The provider ${card.providerDisposition.replace('_', ' ')}.", style = type.tSm.copy(color = colors.fgMuted))
        }
        // A settled or restored card keeps every provider option, in order, with the chosen one marked; no control is shown.
        if (!card.isPending) card.options.forEach { ReadOnlyOption(card, it) }
      }
    }
    if (card.isPending) ApprovalControls(message, actions, offReason)
    actions?.onOpenRules?.let { open ->
      SbButton(
        label = "Repository rules",
        onClick = open,
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        icon = LucideIcons.shield,
        modifier = Modifier.semantics { contentDescription = "Show and revoke always-allow rules for this repository" },
      )
    }
    message.refusal?.let { BasicText(it, style = type.tSm.copy(color = colors.stFailed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
  }
}

private fun ApprovalCard.metaText(): String = listOfNotNull(
  repositoryName.takeIf { it.isNotBlank() }?.let { "Repository $it" },
  "In $checkoutRoot",
  "Requested $createdAt",
  "Expires $deadlineAt",
).joinToString("  ·  ")

@Composable
private fun ApprovalControls(message: ChatMessage.Approval, actions: ChatApprovalActions?, offReason: String?) {
  val colors = SkillBillTheme.colors
  val card = message.card
  val enabled = offReason == null && actions != null
  val grants = card.grants
  val type = SkillBillTheme.type
  val allowOption = card.options.firstOrNull { it.kind == ALLOW_KIND }
  val alwaysUnavailable = grants?.alwaysAllowUnavailable
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    // One option per row: its full label wraps and stays selectable, so a long provider label is never clipped.
    var firstAllow = true
    card.options.forEach { option ->
      val primary = option.kind == ALLOW_KIND && firstAllow
      if (option.kind == ALLOW_KIND) firstAllow = false
      OptionRow(card, option, primary, enabled && option.isSelectable(grants?.oneTimeUnavailable), actions)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (allowOption != null) {
        SbButton(
          label = alwaysAllowLabel(card),
          onClick = { actions?.onDecide(card.key, allowOption.optionId, true) },
          size = SbControlSize.Small,
          enabled = enabled && alwaysUnavailable == null,
        )
      }
      SbButton(
        label = "Dismiss",
        onClick = { actions?.onDismiss(card.key) },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        enabled = enabled,
      )
    }
    if (allowOption != null) {
      BasicText(
        alwaysUnavailable ?: "Allows this exact action in this repository from now on; revoke it under Repository rules.",
        style = type.tSm.copy(color = colors.fgMuted),
      )
    }
    val note = offReason ?: grants?.oneTimeUnavailable?.let { "Allowing is unavailable: $it" }
    note?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted)) }
  }
}

private fun alwaysAllowLabel(card: ApprovalCard): String =
  "Always allow in ${card.repositoryName.takeIf { it.isNotBlank() } ?: "this repository"}"

private const val REMEMBER_UNAVAILABLE =
  "Unavailable: the provider's remembering choices are global or session-wide and cannot be selected as consent from this card."

/** The decision source, rule and chosen option of a settled card, from the stored record alone. */
internal fun ApprovalCard.decisionLines(): List<String> = listOfNotNull(
  when (decisionSource) {
    "operator_once" -> "Decided by the operator, once"
    "operator_rule" -> "Decided by the operator, with a new always-allow rule for this repository"
    "stored_rule" -> "Decided by a saved always-allow rule"
    else -> null
  },
  ruleId?.let { "Rule $it" },
  selectedOptionId?.let { id -> "Selected: ${options.firstOrNull { it.optionId == id }?.label ?: id}" },
)

/** One provider option of a card that can no longer be answered: full wrapping label, kind and whether it was chosen. */
@Composable
private fun ReadOnlyOption(card: ApprovalCard, option: ApprovalCardOption) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val selected = option.optionId == card.selectedOptionId
  val suffix = buildString {
    append(" (${option.kind})")
    if (selected) append(" — selected")
    if (option.kind == PROVIDER_REMEMBER_OPTION_KIND) append(" — not available as app consent")
  }
  BasicText(
    option.label + suffix,
    style = (if (selected) type.tSmMedium else type.tSm).copy(color = if (selected) colors.fg else colors.fgMuted),
  )
}

// A deny needs no grant; an allow needs the runtime's one-time answer to be available. A remembering option is never one.
private fun ApprovalCardOption.isSelectable(oneTimeUnavailable: String?): Boolean = when (kind) {
  ALLOW_KIND -> oneTimeUnavailable == null
  DENY_KIND -> true
  else -> false
}

@Composable
private fun OptionRow(card: ApprovalCard, option: ApprovalCardOption, primary: Boolean, enabled: Boolean, actions: ChatApprovalActions?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val button = when (option.kind) {
    ALLOW_KIND -> if (primary) SbButtonVariant.Primary else SbButtonVariant.Secondary
    DENY_KIND -> SbButtonVariant.Danger
    else -> null
  }
  Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
    SelectionContainer(Modifier.weight(1f)) {
      Column {
        BasicText(option.label, style = type.tSm.copy(color = colors.fg))
        // A remembering choice has no button, so the reason it cannot be chosen is text, not only a hover.
        if (button == null) {
          val why = if (option.kind == PROVIDER_REMEMBER_OPTION_KIND) REMEMBER_UNAVAILABLE else "Unavailable: this choice is not supported from this card."
          BasicText(why, style = type.tSm.copy(color = colors.fgMuted))
        }
      }
    }
    if (button != null) {
      SbButton(
        label = if (option.kind == ALLOW_KIND) "Allow" else "Deny",
        onClick = { actions?.onDecide(card.key, option.optionId, false) },
        // The visible label is short; the accessible name is the provider's full option label.
        modifier = Modifier.semantics { contentDescription = option.label },
        variant = button,
        size = SbControlSize.Small,
        enabled = enabled,
      )
    }
  }
}
