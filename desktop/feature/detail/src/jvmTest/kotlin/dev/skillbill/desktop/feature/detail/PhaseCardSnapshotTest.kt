package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class PhaseCardSnapshotTest {
  private lateinit var previousLocale: Locale
  private lateinit var previousTimeZone: TimeZone

  @BeforeEach
  fun fixPresentationEnvironment() {
    previousLocale = Locale.getDefault()
    previousTimeZone = TimeZone.getDefault()
    Locale.setDefault(Locale.US)
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
  }

  @AfterEach
  fun restorePresentationEnvironment() {
    Locale.setDefault(previousLocale)
    TimeZone.setDefault(previousTimeZone)
  }

  @Test fun `wide phase pills and every disclosure match approved images`() = snapshots(760)
  @Test fun `narrow phase pills and every disclosure match approved images`() = snapshots(360)

  private fun snapshots(width: Int) {
    assumeTrue(System.getProperty("os.name") == "Linux", "Compose pixel baselines use Linux with the bundled fonts")
    runDesktopComposeUiTest(width = width, height = 2400) {
      val detail = phaseSnapshotDetail()
      val item = workItem("phase-snapshots").copy(branch = "feat/standalone-phase-records")
      val selectedSubject = mutableStateOf(false)
      val selected = mutableStateOf("")
      var copied: String? = null
      setContent {
        SkillBillTheme(reducedMotion = true) {
          CompositionLocalProvider(LocalShellInput provides ShellInput()) {
            val subject = if (selectedSubject.value) SubtaskKey.GoalFinish else SubtaskKey.Subtask(1)
            val choice = mapOf(PhaseChoiceKey(item.id, subject) to PhaseDisclosure.Open(selected.value))
            val state = DetailUiState(item, SectionsUi.Loaded(detail, null, emptyMap(), emptySet()), false, epoch, phaseChoice = choice)
            val action: (DetailAction) -> Unit = {
              if (it is DetailAction.SelectPhase) selected.value = it.phaseId
              if (it is DetailAction.Copy) copied = it.text
            }
            Column(Modifier.width(width.dp).background(SkillBillTheme.colors.bgPanel).padding(20.dp).testTag("phase-snapshot")) {
              if (selectedSubject.value) GoalFinishPhases(item, detail, state, remember { mutableStateOf(AssessmentOrder.Default) }, action)
              else SubtaskPhasesSection(item, detail, detail.subtasks.single(), choice, state.activity, epoch, false, state.changes, action)
            }
          }
        }
      }
      for (finish in listOf(false, true)) {
        runOnIdle { selectedSubject.value = finish; selected.value = "" }
        for (phase in if (finish) GOAL_FINISH_PHASES else snapshotSubtaskPhases) {
          onNode(isSelectable() and hasText(phase)).performClick()
          val name = "${if (finish) "finish" else "subtask"}-$phase-$width"
          assertPhaseSnapshot("$name-collapsed", onNodeWithTag("phase-snapshot").captureToImage().toAwtImage())
          val collapsed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed") and hasClickAction() and !isSelectable()
          while (onAllNodes(collapsed).fetchSemanticsNodes().isNotEmpty()) onAllNodes(collapsed)[0].performClick()
          if (finish && phase == "qa") {
            onNodeWithText("The saved duration stays fixed.").assertExists()
            onNodeWithText("Open a completed phase on the board.").assertExists()
            onNodeWithText("Copy steps").assertExists()
            onNodeWithText("Copy steps").performClick()
            assertEquals("Open a completed phase on the board.", copied)
          }
          onAllNodes(hasText("Show 2 more checks") and hasClickAction()).fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
            onNodeWithText("Show 2 more checks").performClick()
            onAllNodesWithText("Migration tests").assertCountEquals(1)
          }
          assertPhaseSnapshot("$name-expanded", onNodeWithTag("phase-snapshot").captureToImage().toAwtImage())
        }
      }
    }
  }
}
