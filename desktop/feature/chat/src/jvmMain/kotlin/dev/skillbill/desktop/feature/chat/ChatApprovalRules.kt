package dev.skillbill.desktop.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillRadius
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.component.SbControlSize
import dev.skillbill.desktop.core.domain.model.ApprovalRevokeOutcome
import dev.skillbill.desktop.core.domain.model.ApprovalRule
import java.io.File

private val RULES_MAX_HEIGHT = 240.dp

/** What the repository's rule list shows: still reading, the rules, or why they cannot be read. */
internal sealed interface RepositoryRulesState {
  data object Loading : RepositoryRulesState

  data class Listed(val rules: List<ApprovalRule>) : RepositoryRulesState

  data class Unavailable(val reason: String) : RepositoryRulesState
}

/** A revoke result shown as visible status text; [failed] marks an outcome that did not remove the rule. */
internal data class RulesNote(val text: String, val failed: Boolean)

internal fun ApprovalRevokeOutcome.toNote(ruleId: String): RulesNote = when (this) {
  ApprovalRevokeOutcome.Revoked -> RulesNote(
    "Rule $ruleId revoked. Later matching requests will ask again; actions already allowed are not undone.",
    failed = false,
  )
  is ApprovalRevokeOutcome.NotFound -> RulesNote(reason, failed = false)
  is ApprovalRevokeOutcome.Failed -> RulesNote(reason, failed = true)
}

/**
 * The repository's durable always-allow rules, each with an explicit Revoke. Listing reads only and revoking removes
 * authority; nothing here selects or grants a request. Every outcome, including errors, is visible text.
 */
@Composable
internal fun RepositoryRulesPanel(tab: RepositoryApprovals, onRefresh: () -> Unit, onRevoke: (ruleId: String) -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  val rules by tab.rules.collectAsState()
  val revoking by tab.revoking.collectAsState()
  val note by tab.rulesNote.collectAsState()
  Column(
    Modifier.fillMaxWidth().heightIn(max = RULES_MAX_HEIGHT).verticalScroll(rememberScrollState()).padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      BasicText(
        "Always-allow rules for ${File(tab.repositoryRoot).name.ifBlank { tab.repositoryRoot }}",
        Modifier.weight(1f),
        style = type.tBaseMedium.copy(color = colors.fg),
      )
      SbButton(label = "Refresh", onClick = onRefresh, size = SbControlSize.Small)
    }
    when (val state = rules) {
      RepositoryRulesState.Loading -> BasicText("Reading rules…", style = type.tSm.copy(color = colors.fgMuted))
      is RepositoryRulesState.Unavailable -> BasicText(
        state.reason,
        style = type.tSm.copy(color = colors.stFailed),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
      )
      is RepositoryRulesState.Listed -> if (state.rules.isEmpty()) {
        BasicText(
          "No saved rules. Nothing in this repository is allowed automatically.",
          style = type.tSm.copy(color = colors.fgMuted),
        )
      } else {
        state.rules.forEach { rule -> RuleRow(rule, revoking = rule.ruleId in revoking, onRevoke = { onRevoke(rule.ruleId) }) }
      }
    }
    note?.let {
      BasicText(
        it.text,
        style = type.tSm.copy(color = if (it.failed) colors.stFailed else colors.fg2),
        modifier = Modifier.semantics { liveRegion = if (it.failed) LiveRegionMode.Assertive else LiveRegionMode.Polite },
      )
    }
  }
}

@Composable
private fun RuleRow(rule: ApprovalRule, revoking: Boolean, onRevoke: () -> Unit) {
  val colors = SkillBillTheme.colors
  val type = SkillBillTheme.type
  Row(
    Modifier.fillMaxWidth().background(colors.bgRaised, RoundedCornerShape(SkillBillRadius.sm)).padding(8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    SelectionContainer(Modifier.weight(1f)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText("Rule ${rule.ruleId}", style = type.tSmMedium.copy(color = colors.fg))
        BasicText(
          "${rule.provider} · ${rule.actionKind} · in ${rule.workingDirectory}",
          style = type.tSm.copy(color = colors.fg2),
        )
        // The whole fingerprint is shown: a rule is exactly one action, and the operator must be able to see which.
        BasicText("Action ${rule.actionFingerprint}", style = type.tSm.copy(color = colors.fg2))
        BasicText(
          "Profile ${rule.profileIdentity} · protocol ${rule.protocolRevision} · created ${rule.createdAt}",
          style = type.tSm.copy(color = colors.fgMuted),
        )
      }
    }
    SbButton(
      label = if (revoking) "Revoking…" else "Revoke",
      onClick = onRevoke,
      modifier = Modifier.semantics { contentDescription = "Revoke rule ${rule.ruleId}" },
      variant = SbButtonVariant.Danger,
      size = SbControlSize.Small,
      enabled = !revoking,
    )
  }
}
