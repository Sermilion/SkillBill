package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.DecisionOutcome
import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.OperatorDecision
import dev.skillbill.desktop.core.domain.model.PurgeOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryOutcome
import dev.skillbill.desktop.core.domain.model.RecoveryPreview
import dev.skillbill.desktop.core.domain.model.RecoveryPreviewOutcome
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailControlsTest {
  @Test
  fun `a pending pause keeps immediate pause available and disables another pause request`() {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Active).copy(
      pauseRequested = true,
      controlEligible = true,
      pauseEligible = false,
    )

    val actions = runActionsView(loadedState(item, incident = null))

    assertTrue(actions.pauseNow?.enabled == true)
    assertFalse(actions.pauseAfter?.enabled == true)
  }

  @Test
  fun `Recover enables only once every listed prerequisite is confirmed on a fresh detail`() {
    val incident = incident(IncidentProgress.NeedsPrerequisites, "credentials_configured", "configuration_fixed")
    val base = loadedState(workItem("SKILL-1", lifecycle = Lifecycle.Blocked), incident)
    fun withConfirmed(vararg confirmed: IncidentPrerequisite, stale: Boolean = false) = base.copy(
      stale = stale,
      controls = ControlsUiState(
        prerequisites = PrerequisiteConfirmation("SKILL-1", incident.incidentToken, setOf("credentials_configured", "configuration_fixed"), confirmed.toSet()),
      ),
    )

    assertFalse(runActionsView(withConfirmed()).recover!!.enabled)
    assertFalse(runActionsView(withConfirmed(IncidentPrerequisite.CREDENTIALS_CONFIGURED)).recover!!.enabled)
    val both = arrayOf(IncidentPrerequisite.CREDENTIALS_CONFIGURED, IncidentPrerequisite.CONFIGURATION_FIXED)
    assertTrue(runActionsView(withConfirmed(*both)).recover!!.enabled)
    assertFalse(runActionsView(withConfirmed(*both, stale = true)).recover!!.enabled)
  }

  @Test
  fun `a blank Retry fix never reaches the runtime and a recorded decision names the item's subtask`() = runTest {
    val item = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(awaitsOperatorDecision = true, currentSubtaskId = "8")
    val state = loadedState(item, incident = null)
    val repository = DecisionRecordingControls()
    val controls = DetailControls(backgroundScope, repository, ToastQueue()) { state }

    controls.onAction(ControlsAction.EditInstructions("   "))
    controls.onAction(ControlsAction.RecordDecision)
    runCurrent()
    assertEquals(emptyList(), repository.decisions)

    controls.onAction(ControlsAction.EditInstructions("Fix the null check"))
    controls.onAction(ControlsAction.RecordDecision)
    runCurrent()
    assertEquals(listOf<Pair<OperatorDecision, String?>>(OperatorDecision.RETRY_FIX to "Fix the null check"), repository.decisions)

    val recorded = decisionDraft(state.copy(controls = controls.state.value)).recorded!!
    assertEquals("Decision recorded for subtask 8: retry fix. Resume the goal to apply it.", recordedText(recorded))
  }

  private fun loadedState(item: WorkItem, incident: Incident?) = DetailUiState(
    header = item,
    sections = SectionsUi.Loaded(emptyDetail.copy(incident = incident), null, emptyMap(), emptySet()),
    stale = false,
    renderedAt = epoch,
  )

  /** Answers a decision with the item's own current subtask, so a confirmation naming another number shows. */
  private class DecisionRecordingControls : WorkControlRepository {
    val decisions = mutableListOf<Pair<OperatorDecision, String?>>()
    override val reservations = MutableStateFlow<Set<String>>(emptySet())

    override suspend fun recordOperatorDecision(item: WorkItem, decision: OperatorDecision, instructions: String?): DecisionOutcome {
      decisions += decision to instructions
      return DecisionOutcome.Recorded(checkNotNull(item.currentSubtaskId).toInt(), decision)
    }

    override suspend fun pauseAfterSubtask(item: WorkItem): ControlOutcome = error("unused")
    override suspend fun pauseNow(item: WorkItem): ControlOutcome = error("unused")
    override suspend fun cancel(item: WorkItem): ControlOutcome = error("unused")
    override suspend fun launch(item: WorkItem, agentId: String?): ControlOutcome = error("unused")
    override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome = error("unused")
    override suspend fun recoveryPreview(item: WorkItem, confirmedPrerequisites: Set<IncidentPrerequisite>): RecoveryPreviewOutcome = error("unused")
    override suspend fun recover(item: WorkItem, preview: RecoveryPreview): RecoveryOutcome = error("unused")
    override suspend fun purge(issueKey: String, repoRoot: String?): PurgeOutcome = error("unused")
  }
}
