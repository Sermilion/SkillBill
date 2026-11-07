package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
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
private const val VISIBLE_TEXT_LINES = 3

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
  var expanded by rememberSaveable(card.key) { mutableStateOf(false) }
  Column(
    Modifier.fillMaxWidth()
      .border(1.dp, if (card.isPending) colors.borderStrong else colors.border, shape)
      .background(colors.bgRaised, shape)
      .padding(10.dp)
      .semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = listOf("Permission request ${card.title}", outcome).plus(decision).joinToString(". ") + "."
      },
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides if (card.isPending) colors.accentText else colors.fgMuted) {
        SbIcon(LucideIcons.shield, SbIconSize.Md)
      }
      BasicText(card.title, Modifier.weight(1f), style = type.tBaseMedium.copy(color = colors.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    BasicText(outcome, style = type.tSm.copy(color = if (card.isPending) colors.accentText else colors.fgMuted))
    ApprovalScrollText(card.reason?.takeIf { it.isNotBlank() } ?: card.subject, type.tSm.copy(color = colors.fg2))
    if (card.isPending) ApprovalControls(message, actions, offReason)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      SbButton(
        label = if (expanded) "Hide details" else "Show details",
        onClick = { expanded = !expanded },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        icon = if (expanded) LucideIcons.chevronUp else LucideIcons.chevronDown,
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
      )
      actions?.onOpenRules?.let { open ->
        SbButton(label = "Repository rules", onClick = open, variant = SbButtonVariant.Ghost, size = SbControlSize.Small)
      }
    }
    if (expanded) {
      ApprovalScrollText(card.detailsText(decision), type.tSm.copy(color = colors.fgMuted, fontFamily = FontFamily.Monospace))
    }
    message.refusal?.let { ApprovalScrollText(it, type.tSm.copy(color = colors.stFailed), Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
  }
}

private fun ApprovalCard.metaText(): String = listOfNotNull(
  repositoryName.takeIf { it.isNotBlank() }?.let { "Repository $it" },
  "In $checkoutRoot",
  "Requested $createdAt",
  "Expires $deadlineAt",
).joinToString("  ·  ")

@Composable
private fun ApprovalScrollText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
  val scroll = rememberScrollState()
  val maxHeight = with(LocalDensity.current) { (style.lineHeight.toPx() * VISIBLE_TEXT_LINES).toDp() }
  Box(modifier.fillMaxWidth().heightIn(max = maxHeight)) {
    SelectionContainer {
      BasicText(text, Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = 12.dp), style = style)
    }
    Box(Modifier.matchParentSize()) {
      VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
  }
}

private fun ApprovalCard.detailsText(decision: List<String>): String = buildList {
  add(subject)
  if (inputText.isNotBlank() && inputText != subject) add(inputText)
  add(metaText())
  addAll(decision)
  deliveryText()?.let(::add)
  if (providerDisposition != "none") add("The provider ${providerDisposition.replace('_', ' ')}.")
  options.forEach { option ->
    val selected = option.optionId == selectedOptionId && !decidedByRule
    add(option.label + " (${option.kind})" + if (selected) " — selected" else "")
    if (option.kind == PROVIDER_REMEMBER_OPTION_KIND) add(REMEMBER_UNAVAILABLE)
    else if (option.kind != ALLOW_KIND && option.kind != DENY_KIND) add("Unavailable: this choice is not supported from this card.")
  }
  if (isPending && options.any { it.kind == ALLOW_KIND }) {
    add(grants?.alwaysAllowUnavailable ?: "Always allow saves this exact action for this repository; revoke it under Repository rules.")
  }
}.joinToString("\n")

@Composable
private fun ApprovalControls(message: ChatMessage.Approval, actions: ChatApprovalActions?, offReason: String?) {
  val colors = SkillBillTheme.colors
  val card = message.card
  val enabled = offReason == null && actions != null
  val grants = card.grants
  val allowOption = card.options.firstOrNull { it.kind == ALLOW_KIND }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      card.options.filter { it.kind == ALLOW_KIND || it.kind == DENY_KIND }.forEach { option ->
        val primary = option == allowOption
        val label = if (card.options.count { it.kind == option.kind } > 1) option.label
          else if (option.kind == ALLOW_KIND) "Allow once" else "Deny"
        SbButton(
          label = label,
          onClick = { actions?.onDecide(card.key, option.optionId, false) },
          tooltip = option.label,
          modifier = Modifier.semantics { contentDescription = option.label },
          variant = if (primary) SbButtonVariant.Primary else if (option.kind == DENY_KIND) SbButtonVariant.Danger else SbButtonVariant.Secondary,
          size = SbControlSize.Small,
          enabled = enabled && option.isSelectable(grants?.oneTimeUnavailable),
        )
      }
      if (allowOption != null) {
        SbButton(
          label = "Always allow",
          tooltip = alwaysAllowLabel(card) + ": this exact action only",
          onClick = { actions?.onDecide(card.key, allowOption.optionId, true) },
          size = SbControlSize.Small,
          enabled = enabled && grants?.alwaysAllowUnavailable == null,
        )
      }
      SbButton(label = "Dismiss", onClick = { actions?.onDismiss(card.key) }, variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small, enabled = enabled)
    }
    val note = offReason ?: grants?.oneTimeUnavailable?.let { "Allowing is unavailable: $it" }
    note?.let { ApprovalScrollText(it, SkillBillTheme.type.tSm.copy(color = colors.fgMuted)) }
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
  selectedLabel()?.let { "Selected: $it" },
)

// Always allow answers with the one-time allow option plus a rule, so a rule decision names the rule, not that option.
private fun ApprovalCard.selectedLabel(): String? = when (decisionSource) {
  "operator_rule" -> alwaysAllowLabel(this)
  "stored_rule" -> null
  else -> selectedOptionId?.let { id -> options.firstOrNull { it.optionId == id }?.label ?: id }
}

private val ApprovalCard.decidedByRule: Boolean
  get() = decisionSource == "operator_rule" || decisionSource == "stored_rule"

// A deny needs no grant; an allow needs the runtime's one-time answer to be available. A remembering option is never one.
private fun ApprovalCardOption.isSelectable(oneTimeUnavailable: String?): Boolean = when (kind) {
  ALLOW_KIND -> oneTimeUnavailable == null
  DENY_KIND -> true
  else -> false
}
