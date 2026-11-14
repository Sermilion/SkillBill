package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Incident
import dev.skillbill.desktop.core.domain.model.IncidentPrerequisite
import dev.skillbill.desktop.core.domain.model.IncidentProgress
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailControlsTest {
  @Test
  fun `an idle goal keeps Resume visible when run options have not loaded`() {
    val state = loadedState(workItem("SKILL-447", lifecycle = Lifecycle.Idle), incident = null)

    val actions = runActionsView(state)

    assertEquals("Resume", assertNotNull(actions.launch).label)
    assertFalse(actions.launch.enabled)
    assertTrue(actions.refreshNeeded)
  }

  @Test
  fun `an idle goal can resume after fresh options arrive and stays disabled on a failed refresh`() {
    val state = loadedState(workItem("SKILL-447", lifecycle = Lifecycle.Idle).copy(fresh = false), incident = null)
    val sections = state.sections as SectionsUi.Loaded
    val loaded = state.copy(sections = sections.copy(runOptions = RunOptions(true, true, "Resume", "codex", listOf("codex"), null)))

    assertTrue(assertNotNull(runActionsView(loaded).launch).enabled)
    assertFalse(assertNotNull(runActionsView(loaded.copy(stale = true)).launch).enabled)
    assertNull(runActionsView(loaded.copy(header = loaded.header.copy(lifecycle = Lifecycle.Active))).launch)
    assertNull(
      runActionsView(
        loaded.copy(
          header = loaded.header.copy(lifecycle = Lifecycle.Terminal),
          sections = sections.copy(runOptions = RunOptions(false, false, "Resume", "codex", listOf("codex"), null)),
        ),
      ).launch,
    )
  }

  @Test
  fun `terminal run controls follow runtime options not lifecycle`() {
    fun state(item: WorkItem, options: RunOptions) = loadedState(item, null).let { base ->
      val sections = base.sections as SectionsUi.Loaded
      base.copy(header = item, sections = sections.copy(runOptions = options))
    }
    val review = workItem("rev", kind = WorkKind.Phase, issueKey = null, lifecycle = Lifecycle.Terminal)
    val unfinalized = RunOptions(false, true, "Run again", "claude", listOf("claude"), null, canFinalize = true)
    val unfinalizedView = runActionsView(state(review, unfinalized))
    assertNotNull(unfinalizedView.launch)
    assertNotNull(unfinalizedView.finalize)

    val failed = runActionsView(state(review, RunOptions(false, false, "Run again", "claude", listOf("claude"), null)))
    assertNull(failed.launch)
    assertNull(failed.finalize)

    val finalizedReview = runActionsView(state(review, RunOptions(false, false, "Run again", "claude", listOf("claude"), null)))
    assertNull(finalizedReview.launch)
    assertNull(finalizedReview.finalize)

    val validation = workItem("val", kind = WorkKind.Phase, issueKey = null, lifecycle = Lifecycle.Terminal)
      .copy(control = workItem("val").control.copy(phaseId = "validation"))
    val finalizedValidation = runActionsView(state(validation, RunOptions(false, true, "Run again", "claude", listOf("claude"), null)))
    assertNotNull(finalizedValidation.launch)
    assertNull(finalizedValidation.finalize)

    val verify = workItem("verify", kind = WorkKind.Operation, issueKey = "SKILL-1", lifecycle = Lifecycle.Terminal)
    val verifyView = runActionsView(state(verify, RunOptions(false, false, "Run again", null, emptyList(), null)))
    assertNull(verifyView.launch)
    assertNull(verifyView.finalize)
  }

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
  fun `a goal awaiting a decision shows the command for its subtask and nothing else does`() {
    val waiting = workItem("SKILL-1", lifecycle = Lifecycle.Paused).copy(issueKey = "SKILL-1", awaitsOperatorDecision = true, currentSubtaskId = "8")

    val notice = assertNotNull(runActionsView(loadedState(waiting, incident = null)).decision)
    assertEquals(
      "sona goal operator-decision SKILL-1 --subtask 8 --decision retry_fix|accept_and_advance|abandon_subtask",
      notice.command,
    )
    assertTrue(notice.text.startsWith("Subtask 8 "))
    assertNull(runActionsView(loadedState(waiting.copy(awaitsOperatorDecision = false), incident = null)).decision)
  }

  private fun loadedState(item: WorkItem, incident: Incident?) = DetailUiState(
    header = item,
    sections = SectionsUi.Loaded(emptyDetail.copy(incident = incident), null, emptyMap(), emptySet()),
    stale = false,
    renderedAt = epoch,
  )
}
