package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.VerifyRunDetails
import dev.skillbill.desktop.core.domain.model.WorkBuild
import dev.skillbill.desktop.core.domain.model.WorkBuildState
import dev.skillbill.desktop.core.domain.model.WorkKind
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.input.LocalShellInput
import dev.skillbill.desktop.core.ui.input.ShellInput
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class DetailBuildStripTest {
  @Test
  fun `build strip follows the phase header and skips validation and verify runs`() = runComposeUiTest {
    val build = WorkBuild(
      WorkBuildState.Passed, "./gradlew check", "gradlew check", "gradle", "kotlin", 42L,
      epoch, null, null, null, 0, null, null,
    )
    val phase = workItem("phase", WorkKind.Phase).copy(
      build = build,
      control = workItem("phase", WorkKind.Phase).control.copy(phaseId = "implement"),
    )
    val state = mutableStateOf(DetailUiState(phase, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch))
    val open = mutableStateOf(false)
    val input = ShellInput()

    setContent {
      SkillBillTheme(reducedMotion = true) {
        CompositionLocalProvider(LocalShellInput provides input) {
          DetailScreen(state.value, DetailScreenHost(open.value, "Ctrl I", { open.value = !open.value })) {}
        }
      }
    }

    onAllNodesWithText("Build passed").assertCountEquals(1)

    val validation = phase.copy(control = phase.control.copy(phaseId = "validation"))
    runOnIdle {
      state.value = DetailUiState(validation, SectionsUi.Loaded(emptyDetail, null, emptyMap(), emptySet()), false, epoch)
    }
    onAllNodesWithText("Build passed").assertCountEquals(0)

    val verify = VerifyRunDetails(
      workflowId = "verify-522", issueKey = "SKILL-522", workflowStatus = "completed", currentStepId = null,
      startedAt = null, finishedAt = null, intake = "spec:specs/SKILL-522", target = "feature/build-indicator", repoRoot = "/repo",
      baseRevision = "base", headRevision = "head", reviewMode = null, qaSelection = null, specPath = "specs/SKILL-522",
      steps = emptyList(), criteria = emptyList(), criteriaRaw = null, nonGoals = emptyList(), technicalConstraints = emptyList(),
      rolloutExpectation = null, completenessAudit = null, reviewFindings = null, flagAudit = null, unitTestValue = null,
      changedFiles = emptyList(), verdict = null, qa = null, qaVerdict = null, qaOutcome = null, qaFindingLocations = emptyMap(),
      supersededBy = null, staleReason = null,
    )
    runOnIdle {
      state.value = DetailUiState(
        phase,
        SectionsUi.Loaded(emptyDetail.copy(verifyRun = verify), null, emptyMap(), emptySet()),
        false,
        epoch,
      )
    }
    onAllNodesWithText("Build passed").assertCountEquals(0)
  }
}
