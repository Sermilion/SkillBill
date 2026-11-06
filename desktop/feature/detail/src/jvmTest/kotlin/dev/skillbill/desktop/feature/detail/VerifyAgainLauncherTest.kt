package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.NewTaskKind
import dev.skillbill.desktop.core.domain.model.NewTaskStartOutcome
import dev.skillbill.desktop.core.domain.model.RunOptions
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.session.DatabaseSessionController
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifyAgainLauncherTest {
  @Test
  fun `verify again starts the intake on its verified branch and reports refusal`() = runTest {
    val item = workItem("SKILL-506")
    val run = VerifyRunDetails(
      workflowId = "verify-506", issueKey = "SKILL-506", workflowStatus = "completed", currentStepId = null,
      startedAt = null, finishedAt = null, intake = "SKILL-506", target = "feature/verified", repoRoot = "/repo",
      baseRevision = "base", headRevision = "head", reviewMode = null, qaSelection = null, specPath = null,
      steps = emptyList(), criteria = emptyList(), criteriaRaw = null, nonGoals = emptyList(), technicalConstraints = emptyList(),
      rolloutExpectation = null, completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null,
      changedFiles = emptyList(), verdict = null, qa = null, qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(),
      supersededBy = null, staleReason = null,
    )
    val state = MutableStateFlow(
      DetailUiState(
        header = item,
        sections = SectionsUi.Loaded(emptyDetail.copy(verifyRun = run), RunOptions(true, true, "Resume", "codex", listOf("codex"), null), emptyMap(), emptySet()),
        stale = false,
        renderedAt = epoch,
      ),
    )
    val controls = FakeWorkControlRepository().apply { newTaskOutcome = NewTaskStartOutcome.Refused("stale session") }
    val toastQueue = ToastQueue()
    val session = object : DatabaseSessionController {
      override val state = MutableStateFlow<DatabaseSessionState>(DatabaseSessionState.Open("/db", 7))
      override fun reopen() = Unit
    }
    val launcher = VerifyAgainLauncher(this, controls, session, toastQueue, state)

    assertTrue(launcher.start())
    advanceUntilIdle()

    val request = controls.newTaskRequests.single()
    assertEquals(NewTaskKind.Verify, request.kind)
    assertEquals("SKILL-506", request.target)
    assertEquals("feature/verified", request.branch)
    assertEquals(7L, request.expectedDatabaseSessionId)
    assertEquals("stale session", toastQueue.host.toasts.single().data.title)
  }
}
