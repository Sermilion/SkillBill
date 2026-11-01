package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ControlOutcome
import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.PlanRunReport
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.domain.repository.WorkControlRepository
import dev.skillbill.desktop.core.testing.FakeWorkControlRepository
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class PlanActionsTest {
  @Test
  fun `Implement is visible but disabled during planning and enables after its report arrives`() = runComposeUiTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val item = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = PlanRunReport("OAC-86", "/specs/OAC-86/spec.md", emptyList(), emptyList())
    val run = ExecutionInstance("plan-invocation", "execution", "terminal", epoch, epoch, epoch,
      null, null, null, null, report)
    val detail = emptyDetail.copy(executionHistory = listOf(run))
    val current = mutableStateOf(DetailUiState(item, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch))
    val actions = mutableListOf<DetailAction>()
    setContent {
      SkillBillTheme(reducedMotion = true) {
        val focus = remember { FocusRequester() }
        RunActions(runActionsView(current.value), emptyList(), false, focus, actions::add)
      }
    }
    onNodeWithText("Implement").assertIsNotEnabled()
    runOnIdle {
      current.value = current.value.copy(
        header = item.copy(running = false, lifecycle = Lifecycle.Terminal, finishedAt = epoch),
        sections = SectionsUi.Loaded(detail, null, emptyMap(), emptySet()),
      )
    }
    onNodeWithText("Implement").assertIsEnabled().performClick()
    runOnIdle { assertEquals(listOf<DetailAction>(ControlsAction.ImplementPlan), actions) }
  }

  @Test
  fun `Implement waits for this plan's completed report then starts its goal once`() = runTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val plan = base.copy(issueKey = "OAC-86", standaloneExecution = true,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = PlanRunReport("OAC-86", "/specs/OAC-86/spec.md", emptyList(), emptyList())
    val run = ExecutionInstance("plan-invocation", "execution", "terminal", epoch, epoch, epoch,
      null, null, null, null, report)
    val detail = emptyDetail.copy(executionHistory = listOf(run))
    var current = DetailUiState(plan, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch)
    val requests = mutableListOf<Pair<String, String>>()
    val finish = CompletableDeferred<Unit>()
    val repository = object : WorkControlRepository by FakeWorkControlRepository() {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        requests += repoRoot to issueKey
        finish.await()
        return ControlOutcome.RequestAccepted
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    current = current.copy(header = plan.copy(running = false, lifecycle = Lifecycle.Terminal, finishedAt = epoch))
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertEquals(listOf(plan.repositoryRoot to "OAC-86"), requests)
    finish.complete(Unit)
    runCurrent()
    assertFalse(runActionsView(current.copy(controls = controls.state.value)).implement!!.enabled)
    assertFalse(controls.onAction(ControlsAction.ImplementPlan))
    assertEquals(1, requests.size)
    assertFalse(planImplementationEnabled(current.header, detail.copy(executionHistory = emptyList())))
  }

  @Test
  fun `Implement can retry when another launch reservation clears`() = runTest {
    val base = workItem("plan-invocation", kind = WorkKind.Phase)
    val plan = base.copy(issueKey = "OAC-86", standaloneExecution = true, running = false,
      lifecycle = Lifecycle.Terminal, finishedAt = epoch,
      control = base.control.copy(phaseId = "plan", invocationId = "plan-invocation"))
    val report = PlanRunReport("OAC-86", "/specs/OAC-86/spec.md", emptyList(), emptyList())
    val run = ExecutionInstance("plan-invocation", "execution", "terminal", epoch, epoch, epoch,
      null, null, null, null, report)
    val current = DetailUiState(plan, SectionsUi.Loaded(emptyDetail.copy(executionHistory = listOf(run)), null, emptyMap(), emptySet()), false, epoch)
    val fake = FakeWorkControlRepository()
    var requests = 0
    val repository = object : WorkControlRepository by fake {
      override suspend fun startGoal(repoRoot: String, issueKey: String, agentId: String?): ControlOutcome {
        requests++
        fake.reservations.value = setOf(issueKey)
        return ControlOutcome.AlreadyLaunching
      }
    }
    val controls = DetailControls(backgroundScope, repository, ToastQueue(), {}, {}) { current }
    runCurrent()
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertFalse(runActionsView(current.copy(controls = controls.state.value)).implement!!.enabled)
    fake.reservations.value = emptySet()
    runCurrent()
    assertTrue(controls.onAction(ControlsAction.ImplementPlan))
    runCurrent()
    assertEquals(2, requests)
  }

}
