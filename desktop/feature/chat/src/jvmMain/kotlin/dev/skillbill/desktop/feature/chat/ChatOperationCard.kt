package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.skillbill.desktop.core.domain.model.OperationCardDetailsView
import dev.skillbill.desktop.core.domain.model.OperationCardState
import dev.skillbill.desktop.core.domain.model.OperationCardThreadView
import dev.skillbill.desktop.core.domain.model.OperationCardView

private val SUMMARY_MAX_HEIGHT = 220.dp

/**
 * What an operation card can do. Each callback is a click the operator made on that card: [onConfirm] is the only way to
 * start the parked continuation, and [onAdjust] asks for a replacement proposal instead of confirming the old one.
 */
internal data class ChatOperationCardActions(
  val onChooseOption: (token: String, ordinal: String, optionId: String) -> Unit,
  val onChooseRecommended: (token: String) -> Unit,
  val onConfirm: (token: String) -> Unit,
  val onDismiss: (token: String) -> Unit,
  val onAdjust: (token: String) -> Unit,
  val canAdjust: (token: String) -> Boolean,
)

/** The `select:` value a PR-review-fix confirm sends, or null when no offered option was explicitly chosen. Verify has none. */
internal fun ChatMessage.OperationCard.selectionWire(): String? {
  val details = card.details as? OperationCardDetailsView.PrReviewFix ?: return null
  val submitted = card.selection
  if (
    !choicesSaved && submitted != null &&
    submitted in setOf("all-recommended", "fix-all-unresolved", "select:all-recommended", "select:fix-all-unresolved")
  ) {
    return submitted.removePrefix("select:")
  }
  val selected = selectedOfferedChoices(details)
  return selected.takeIf { it.isNotEmpty() }?.joinToString(",") { (ordinal, option) -> "$ordinal=$option" }
}

/** Names the missing choice when no offered option is selected, or null when the card has an explicit selection. */
internal fun ChatMessage.OperationCard.missingChoiceText(): String? {
  val details = card.details as? OperationCardDetailsView.PrReviewFix ?: return null
  if (details.threads.isEmpty()) return "This proposal offers no thread to fix, so it cannot be confirmed."
  if (
    !choicesSaved &&
    card.selection in setOf("all-recommended", "fix-all-unresolved", "select:all-recommended", "select:fix-all-unresolved")
  ) return null
  return selectedOfferedChoices(details).takeIf { it.isEmpty() }?.let { "Choose an option for at least one thread to confirm." }
}

private fun ChatMessage.OperationCard.selectedOfferedChoices(
  details: OperationCardDetailsView.PrReviewFix,
): List<Pair<String, String>> {
  val effective = if (choicesSaved) choices else choices + card.selectionChoices(details)
  return details.threads.mapNotNull { thread ->
    val option = effective[thread.ordinal] ?: return@mapNotNull null
    (thread.ordinal to option).takeIf { (_, optionId) -> thread.options.any { it.optionId == optionId } }
  }
}

/** Why a decidable card's controls are off, or null when they are on. A settled card has none. */
internal fun ChatMessage.OperationCard.controlsOffReason(): String? = when {
  !card.state.decidable -> null
  !card.canConfirm -> card.disabledReason ?: "The original proposal is unavailable; start a new proposal from chat."
  restoredCopy -> "Restored from history. This copy cannot be answered."
  submitting -> "Sending your answer."
  else -> null
}

private fun ChatMessage.OperationCard.sharedActionOffReason(): String? = when {
  !card.state.decidable -> "This proposal is no longer awaiting a decision."
  restoredCopy -> "Restored from history. This copy cannot be answered."
  submitting -> "Sending your answer."
  else -> null
}

internal fun OperationCardState.outcomeText(): String = when (this) {
  OperationCardState.AwaitingConfirmation -> "Waiting for your confirmation"
  OperationCardState.Confirming -> "Confirmation submitted"
  OperationCardState.Completed -> "Completed"
  OperationCardState.Refused -> "Refused"
  OperationCardState.Failed -> "Failed"
  OperationCardState.Cancelled -> "Dismissed"
  OperationCardState.Superseded -> "Replaced by a newer proposal"
  OperationCardState.ConsumedUncertain -> "Confirmed, but the outcome is not certain"
}

private fun ChatMessage.OperationCard.title(): String = "Confirm ${card.operationId}"

/** One parked operation: its complete stored proposal and outcome, and while it awaits, the operator's own controls. */
@Composable
internal fun ChatOperationCard(message: ChatMessage.OperationCard, actions: ChatOperationCardActions?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val card = message.card
  val focusRequester = remember(card.token) { FocusRequester() }
  var focused by remember(card.token) { mutableStateOf(false) }
  LaunchedEffect(message.submitting, card.state) {
    if (message.submitting && card.state.terminal && focused) focusRequester.requestFocus()
  }
  val awaiting = card.state.decidable
  val shape = RoundedCornerShape(SkillBillRadius.md)
  val outcome = card.state.outcomeText()
  Column(
    Modifier.fillMaxWidth()
      .focusRequester(focusRequester)
      .focusGroup()
      .focusable()
      .onFocusChanged { focused = it.hasFocus }
      .border(1.dp, if (awaiting) colors.borderStrong else colors.border, shape)
      .background(colors.bgRaised, shape)
      .padding(12.dp)
      .semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = "${message.title()}. $outcome."
      },
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      CompositionLocalProvider(LocalContentColor provides if (awaiting) colors.accentText else colors.fgMuted) {
        SbIcon(LucideIcons.shield, SbIconSize.Md)
      }
      BasicText(message.title(), Modifier.weight(1f), style = type.tBaseMedium.copy(color = colors.fg))
      BasicText(outcome, style = type.tSm.copy(color = if (awaiting) colors.accentText else colors.fgMuted))
    }
    SelectionContainer {
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val details = card.details) {
          is OperationCardDetailsView.Verify -> VerifyFacts(details)
          is OperationCardDetailsView.PrReviewFix -> PrReviewFixFacts(message, details, actions)
        }
        card.evidence?.let { BasicText(it, style = type.tSm.copy(color = colors.fg2)) }
        card.selection?.let { BasicText("Selected: $it", style = type.tSm.copy(color = colors.fg2)) }
        BasicText(
          card.summary,
          Modifier.fillMaxWidth().heightIn(max = SUMMARY_MAX_HEIGHT).background(colors.bgOverlay, RoundedCornerShape(SkillBillRadius.sm))
            .verticalScroll(rememberScrollState()).padding(8.dp),
          style = type.tSm.copy(color = colors.fg2, fontFamily = FontFamily.Monospace),
        )
      }
    }
    if (awaiting) OperationCardControls(message, actions)
    message.refusal?.let {
      BasicText(it, style = type.tSm.copy(color = colors.stFailed), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
    }
  }
}

@Composable
private fun Fact(label: String, value: String) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  BasicText("$label: $value", style = type.tSm.copy(color = colors.fg))
}

@Composable
private fun VerifyFacts(details: OperationCardDetailsView.Verify) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Fact("Intake", details.intake)
  details.spec?.let { Fact("Spec", it) }
  Fact("Target", details.target)
  Fact("Mode", details.mode)
  Fact("QA", details.qa)
  BasicText("Acceptance criteria", style = type.tSmMedium.copy(color = colors.fg))
  details.criteria.forEach { BasicText(it, style = type.tSm.copy(color = colors.fg2)) }
}

@Composable
private fun PrReviewFixFacts(
  message: ChatMessage.OperationCard,
  details: OperationCardDetailsView.PrReviewFix,
  actions: ChatOperationCardActions?,
) {
  Fact("Mode", details.mode)
  details.pullRequest?.let { Fact("Pull request", it) }
  details.push?.let { Fact("Push", it) }
  details.replies?.let { Fact("Replies", it) }
  val enabled = message.card.state.decidable && message.controlsOffReason() == null && actions != null
  details.threads.forEach { thread -> ThreadOptions(message, details, thread, enabled, actions) }
}

@Composable
private fun ThreadOptions(
  message: ChatMessage.OperationCard,
  details: OperationCardDetailsView.PrReviewFix,
  thread: OperationCardThreadView,
  enabled: Boolean,
  actions: ChatOperationCardActions?,
) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val chosen = if (message.choicesSaved) message.choices[thread.ordinal] else message.card.selectionChoices(details)[thread.ordinal]
  BasicText("Thread ${thread.ordinal} (${thread.threadId})", style = type.tSmMedium.copy(color = colors.fg))
  thread.options.forEach { option ->
    val picked = option.optionId == chosen
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(
        "${option.optionId}. ${option.label}" + if (option.recommended) " (recommended)" else "",
        Modifier.weight(1f),
        style = (if (picked) type.tSmMedium else type.tSm).copy(color = if (picked) colors.fg else colors.fg2),
      )
      if (message.card.state.decidable) {
        SbButton(
          label = if (picked) "Chosen" else "Choose",
          onClick = { actions?.onChooseOption?.invoke(message.card.token, thread.ordinal, option.optionId) },
          modifier = Modifier.semantics {
            contentDescription = "Choose option ${option.optionId} for thread ${thread.ordinal}: ${option.label}"
          },
          variant = if (picked) SbButtonVariant.Primary else SbButtonVariant.Secondary,
          size = SbControlSize.Small,
          enabled = enabled,
        )
      }
    }
  }
}

private fun OperationCardView.selectionChoices(details: OperationCardDetailsView.PrReviewFix): Map<String, String> {
  val submitted = selection ?: return emptyMap()
  val body = submitted.removePrefix("select:")
  if (body == "all-recommended") {
    return details.threads.mapNotNull { thread ->
      thread.options.firstOrNull { it.recommended }?.let { thread.ordinal to it.optionId }
    }.toMap()
  }
  if (body == "fix-all-unresolved") return emptyMap()
  return body.split(',').mapNotNull { entry ->
    val ordinal = entry.substringBefore('=')
    val option = entry.substringAfter('=', "")
    (ordinal to option).takeIf {
      ordinal.isNotBlank() && option.isNotBlank() && details.threads.any { thread ->
        thread.ordinal == ordinal && thread.options.any { it.optionId == option }
      }
    }
  }.toMap()
}

@Composable
private fun OperationCardControls(message: ChatMessage.OperationCard, actions: ChatOperationCardActions?) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val card = message.card
  val offReason = message.controlsOffReason()
  val sharedActionOffReason = message.sharedActionOffReason()
  val enabled = offReason == null && actions != null
  val sharedActionsEnabled = sharedActionOffReason == null && actions != null
  val isPrReviewFix = card.details is OperationCardDetailsView.PrReviewFix
  val missing = message.missingChoiceText()
  val adjustable = actions?.canAdjust?.invoke(card.token) == true
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      if (isPrReviewFix) {
        SbButton(
          label = "Choose recommended",
          onClick = { actions?.onChooseRecommended?.invoke(card.token) },
          variant = SbButtonVariant.Secondary,
          size = SbControlSize.Small,
          enabled = enabled,
        )
      }
      SbButton(
        label = "Confirm",
        onClick = { actions?.onConfirm?.invoke(card.token) },
        size = SbControlSize.Small,
        enabled = enabled && missing == null,
      )
      SbButton(
        label = "Adjust",
        onClick = { actions?.onAdjust?.invoke(card.token) },
        variant = SbButtonVariant.Secondary,
        size = SbControlSize.Small,
        enabled = sharedActionsEnabled && adjustable,
        modifier = Modifier.semantics { contentDescription = "Adjust: ask for a replacement proposal" },
      )
      SbButton(
        label = "Dismiss",
        onClick = { actions?.onDismiss?.invoke(card.token) },
        variant = SbButtonVariant.Ghost,
        size = SbControlSize.Small,
        enabled = sharedActionsEnabled,
      )
    }
    val note = offReason ?: missing ?: ADJUST_UNAVAILABLE.takeUnless { adjustable }
    note?.let { BasicText(it, style = type.tSm.copy(color = colors.fgMuted)) }
  }
}

private const val ADJUST_UNAVAILABLE = "Adjust is unavailable on this window's copy; start the operation again from the chat."
