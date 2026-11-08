package dev.skillbill.desktop.feature.work

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.WorkBuild
import dev.skillbill.desktop.core.domain.model.WorkBuildState
import dev.skillbill.desktop.core.testing.workItem
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class WorkRowBuildChipTest {
  @Test
  fun `running and failed builds show chips while passed builds do not`() = runComposeUiTest {
    mainClock.autoAdvance = false
    val startedAt = Instant.fromEpochMilliseconds(0)
    fun build(state: WorkBuildState, shortCommand: String) = WorkBuild(
      state, shortCommand, shortCommand, "gradle", "kotlin", 42L, startedAt, 1_000L, startedAt,
      null, if (state == WorkBuildState.Failed) 1 else null, null, null,
    )

    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column {
          WorkRow(workItem("running").copy(build = build(WorkBuildState.Running, "running-command")), false, null, false, {})
          WorkRow(workItem("failed").copy(build = build(WorkBuildState.Failed, "failed-command")), false, null, false, {})
          WorkRow(workItem("passed").copy(build = build(WorkBuildState.Passed, "passed-command")), false, null, false, {})
        }
      }
    }

    onAllNodesWithText("running-command").assertCountEquals(1)
    onAllNodesWithText("failed-command").assertCountEquals(1)
    onAllNodesWithText("passed-command").assertCountEquals(0)
  }
}
