package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import kotlinx.coroutines.launch

internal enum class VerifyRunState { Pending, Running, Parked, Superseded, Stopped, Finished }

internal const val VERIFY_CRITERIA_KEY = "verify-criteria"
internal const val VERIFY_STEPS_KEY = "verify-steps"
internal const val VERIFY_QA_KEY = "verify-qa"
internal const val VERIFY_NON_GOALS_KEY = "verify-non-goals"
internal const val VERIFY_TECHNICAL_CONSTRAINTS_KEY = "verify-technical-constraints"
internal const val VERIFY_ROLLOUT_EXPECTATION_KEY = "verify-rollout-expectation"
internal const val VERIFY_STEP_COLLAPSE_PREFIX = "verify-step:"
internal fun verifyStepKey(stepId: String) = "verify-step-$stepId"
internal fun verifyStepCollapseKey(workflowId: String, stepId: String) = "$VERIFY_STEP_COLLAPSE_PREFIX$workflowId:$stepId"

internal val VERIFY_STEP_NAMES = mapOf(
  "collect_inputs" to "Collect inputs", "extract_criteria" to "Extract criteria", "gather_diff" to "Gather diff",
  "feature_flag_audit" to "Feature flag audit", "code_review" to "Code review", "unit_test_value_check" to "Unit test value",
  "completeness_audit" to "Completeness audit", "qa" to "QA", "verdict" to "Verdict",
)
internal fun verifyStepName(id: String) = VERIFY_STEP_NAMES[id] ?: id
internal fun verifyRunState(run: VerifyRunDetails) = when {
  run.supersededBy != null -> VerifyRunState.Superseded
  run.workflowStatus == "running" -> VerifyRunState.Running
  run.workflowStatus == "pending" && run.currentStepId == "extract_criteria" -> VerifyRunState.Parked
  run.workflowStatus == "pending" -> VerifyRunState.Pending
  run.workflowStatus == "abandoned" || run.workflowStatus == "failed" -> VerifyRunState.Stopped
  else -> VerifyRunState.Finished
}
internal fun auditedSteps(run: VerifyRunDetails) = run.steps.filterNot { it.stepId == "finish" }
internal fun stoppedStepIndex(run: VerifyRunDetails): Int = when (verifyRunState(run)) {
  VerifyRunState.Superseded -> auditedSteps(run).indexOfFirst { it.stepId == "extract_criteria" }.coerceAtLeast(0)
  else -> auditedSteps(run).indexOfFirst { it.status in setOf("running", "failed") || it.status == "pending" && it.attemptCount > 0 }.coerceAtLeast(0)
}
internal fun verifyStatusLabel(run: VerifyRunDetails) = when (verifyRunState(run)) {
  VerifyRunState.Pending -> "Pending"
  VerifyRunState.Running -> "Running"
  VerifyRunState.Parked -> "Awaiting you"
  VerifyRunState.Superseded -> "Superseded"
  VerifyRunState.Stopped -> "Stopped"
  VerifyRunState.Finished -> "Finished"
}
internal fun verifyIntakeLabel(intake: String?): String {
  if (intake == null) return "Free-text intake"
  if (intake.startsWith("spec:")) return intake.removePrefix("spec:")
  val url = intake.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
  if (url != null) {
    val schemeEnd = url.indexOf("://") + 3
    val authority = url.substring(schemeEnd).substringBefore('/').substringBefore('?').substringBefore('#')
    val hostPart = authority.substringAfterLast('@')
    val host = if (hostPart.startsWith("[")) hostPart.substringBefore(']') + "]" else hostPart.substringBefore(':')
    val path = url.substring(schemeEnd + authority.length).substringBefore('?').substringBefore('#')
    val issue = path.split('/').mapNotNull { segment ->
      Regex("^([A-Z][A-Z0-9]+-\\d+)(?=$|[-_])").find(segment)?.groupValues?.get(1)
    }.lastOrNull()
    return listOfNotNull(host.takeIf { it.isNotEmpty() }, issue).joinToString(" · ")
  }
  return "Free-text intake"
}
internal fun isFreeTextIntake(intake: String?) = intake == null || !(intake.startsWith("spec:") || intake.startsWith("http://", ignoreCase = true) || intake.startsWith("https://", ignoreCase = true))

internal fun canVerifyAgain(run: VerifyRunDetails, agentChoices: List<String>) =
  verifyRunState(run) in setOf(VerifyRunState.Finished, VerifyRunState.Stopped, VerifyRunState.Superseded) &&
    !run.intake.isNullOrBlank() && !run.repoRoot.isNullOrBlank() && !run.target.isNullOrBlank() && agentChoices.isNotEmpty()

@androidx.compose.runtime.Composable
internal fun rememberVerifySectionScroller(
  listState: androidx.compose.foundation.lazy.LazyListState,
  collapse: dev.skillbill.desktop.core.designsystem.detail.SectionCollapse,
  keys: LazyKeyIndex,
  workflowId: String?,
): (String) -> Unit {
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  return androidx.compose.runtime.remember(listState, collapse, keys, workflowId, scope) {
    { key ->
      scope.launch {
        if (key == VERIFY_CRITERIA_KEY && collapse.isCollapsed(VERIFY_CRITERIA_KEY)) collapse.toggle(VERIFY_CRITERIA_KEY)
        if (key.startsWith("verify-step-")) {
          if (collapse.isCollapsed(VERIFY_STEPS_KEY)) collapse.toggle(VERIFY_STEPS_KEY)
          workflowId?.let { id ->
            val row = verifyStepCollapseKey(id, key.removePrefix("verify-step-"))
            if (collapse.isCollapsed(row, default = true)) collapse.toggle(row, default = true)
          }
        }
        if (key == VERIFY_QA_KEY) {
          if (collapse.isCollapsed(VERIFY_STEPS_KEY)) collapse.toggle(VERIFY_STEPS_KEY)
          workflowId?.let { id ->
            val qaRow = verifyStepCollapseKey(id, "qa")
            if (collapse.isCollapsed(qaRow, default = true)) collapse.toggle(qaRow, default = true)
          }
        }
        repeat(5) {
          val index = keys.indexOf(key)
          if (index != null) {
            listState.animateScrollToItem(index)
            return@launch
          }
          androidx.compose.runtime.withFrameNanos { }
        }
      }
    }
  }
}

internal fun verifyRunActionsView(view: RunActionsView, run: VerifyRunDetails): RunActionsView {
  val state = verifyRunState(run)
  val showLaunch = state in setOf(VerifyRunState.Pending, VerifyRunState.Stopped)
  return view.copy(
    launch = view.launch?.takeIf { showLaunch }?.let { button ->
      if (state == VerifyRunState.Stopped && button.label != WORKING) button.copy(label = "Resume from ${auditedSteps(run).getOrNull(stoppedStepIndex(run))?.let { verifyStepName(it.stepId) } ?: "last step"}") else button
    },
    agent = view.agent?.takeIf { showLaunch },
    cancel = view.cancel?.takeUnless { state in setOf(VerifyRunState.Finished, VerifyRunState.Superseded, VerifyRunState.Stopped) }?.let { button ->
      button.copy(label = if (button.label == WORKING) WORKING else when (state) { VerifyRunState.Running -> "Stop"; VerifyRunState.Parked -> "Discard"; else -> button.label })
    },
    launchNote = false,
  )
}
