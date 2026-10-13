package dev.skillbill.tray.ui

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.application.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.use
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.assertTrue
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class MainWindowTest {
  @Test
  fun `detail titles ignore lifecycle failure reasons and structured phase output`() {
    for (lifecycle in Lifecycle.entries) {
      val phase = item("phase", lifecycle, WorkFamily.FEATURE_VERIFY).copy(
        scope = ExecutionScope.STANDALONE_PHASE, phaseId = "validation",
        summary = "{\"status\":\"failed\",\"summary\":\"Build failed because a command exited\"}",
        pauseReason = "Blocked because credentials are missing",
      )
      val detail = Detail(phase, emptyList(), emptyList(), null, null, 0, 0, 0, null, null, emptyMap())
      assertEquals("Validation · SKILL-phase", detailTitle(detail))
      assertEquals("Named feature title", detailTitle(detail.copy(featureTitle = "Named feature title")))
      assertEquals("Goal · SKILL-phase", detailTitle(detail.copy(item = phase.copy(scope = ExecutionScope.WORKFLOW, family = WorkFamily.FEATURE_GOAL))))
    }
  }

  @Test
  fun `history entries beyond the first four remain reachable through the sidebar scrollbar`() = runBlocking {
    val history = (0 until 20).map { item("entry-$it", Lifecycle.TERMINAL, WorkFamily.FEATURE_GOAL) }
    val empty = Board(Instant.EPOCH, dev.skillbill.tray.domain.Retention.WEEK, emptyList())
    val model = object : DashboardModel {
      override val state = MutableStateFlow(DashboardState(AppSettings(), board = empty, boardLoading = false,
        sections = WorkSection.entries.associateWith { if (it == WorkSection.HISTORY) empty.copy(items = history) else empty },
        sectionLoading = emptySet(), sectionLoadStates = WorkSection.entries.associateWith { ListLoadState.EXHAUSTED }))
      override fun refresh() = Unit
      override fun loadMoreBoard() = Unit
      override fun loadMore(section: WorkSection) = Unit
      override fun filter(value: String) = Unit
      override fun loadMoreDetail(list: DetailList) = Unit
      override fun select(identity: WorkIdentity) = Unit
      override fun updateSettings(settings: AppSettings) = Unit
      override fun chooseTab(tab: String) = Unit
      override fun trayAvailable() = Unit
      override fun trayUnavailable() = Unit
      override fun perform(action: String, identity: WorkIdentity) = Unit
      override fun close() = Unit
    }
    ImageComposeScene(1060, 698, coroutineContext = coroutineContext) {
      SkillBillTheme { MainWindow(model) {} }
    }.use { scene ->
      scene.render(System.nanoTime()).close()
      repeat(15) {
        scene.sendPointerEvent(PointerEventType.Scroll, Offset(120f, 580f), scrollDelta = Offset(0f, 120f))
        delay(25)
        scene.render(System.nanoTime()).close()
      }
      val visibleTexts = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }.flatMap {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { text -> text.text }
      }
      assertTrue("entry-19" in visibleTexts, visibleTexts.joinToString())
    }
  }

  private fun item(id: String, lifecycle: Lifecycle, family: WorkFamily) = WorkItem(
    identity = WorkIdentity(id, "repo-$id"),
    workflowId = id,
    invocationId = null,
    repositoryRoot = "/repo/$id",
    branch = "main",
    issueKey = "SKILL-$id",
    family = family,
    scope = ExecutionScope.WORKFLOW,
    phaseId = null,
    activity = null,
    lifecycle = lifecycle,
    freshness = Freshness.FRESH,
    summary = "summary",
    currentStepId = "implement",
    currentStepLabel = "Implement",
    progressCompleted = null,
    progressTotal = null,
    activeDurationMs = 40_000,
    activeDurationAsOf = Instant.parse("2026-10-11T10:00:00Z"),
    subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null,
    pauseRequested = false,
    pauseReasonCode = null,
    pauseReason = null,
    planningState = null,
    plannedSubtasks = null,
    totalSubtasks = null,
    planningWaveSubtaskIds = emptyList(),
    executionCount = null,
    executionTotal = null,
    updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null,
    finishedAtSource = null,
    liveSection = when (lifecycle) {
      Lifecycle.BLOCKED, Lifecycle.FAILED -> WorkSection.NEEDS_YOU
      Lifecycle.ACTIVE -> if (family == WorkFamily.FEATURE_GOAL) WorkSection.GOALS else WorkSection.PHASES
      else -> null
    },
    requiresAttention = lifecycle in setOf(Lifecycle.BLOCKED, Lifecycle.FAILED),
    displayGroup = if (lifecycle in setOf(Lifecycle.ACTIVE, Lifecycle.BLOCKED, Lifecycle.FAILED)) WorkSection.GOALS else null,
    displayPriority = when (lifecycle) {
      Lifecycle.BLOCKED -> 0
      Lifecycle.FAILED -> 1
      Lifecycle.ACTIVE -> 2
      else -> 5
    },
  )
}
