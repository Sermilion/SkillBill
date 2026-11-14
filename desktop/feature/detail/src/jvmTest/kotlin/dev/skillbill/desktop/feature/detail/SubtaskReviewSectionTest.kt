package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.ReviewPass
import dev.skillbill.desktop.core.domain.model.RunningReviewLane
import dev.skillbill.desktop.core.domain.model.RunningReviewPass
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SubtaskReviewSectionTest {
  @Test
  fun `running pass shows progress and accessible lane completion while completed passes stay summaries`() =
    runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          SubtaskReviewSection(
            passes = listOf(ReviewPass(1, "approved", 0, emptyList(), listOf("completed-only-lane"))),
            runningReviewPass = RunningReviewPass(
              2,
              listOf(RunningReviewLane("lane-finished", true), RunningReviewLane("lane-running", false)),
            ),
          )
        }
      }

      onNodeWithText("pass 2 running").assertExists()
      onNodeWithText("Pass 1").assertExists()
      onNodeWithText("Pass 2").assertExists()
      onNodeWithText("· Reviewing").assertExists()
      onNodeWithText("· 1 of 2 lanes done").assertExists()
      onNodeWithText("completed-only-lane").assertExists()
      onNode(
        hasText("lane-finished") and
          SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Finished"),
      ).assertExists()
      onNode(
        hasText("lane-running") and
          SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not finished"),
      ).assertExists()
    }

  @Test
  fun `running pass without stored lanes omits lane details`() = runComposeUiTest {
      setContent {
        SkillBillTheme(reducedMotion = true) {
          SubtaskReviewSection(emptyList(), RunningReviewPass(1, emptyList()))
        }
      }

      onNodeWithText("pass 1 running").assertExists()
      onNodeWithText("· Reviewing").assertExists()
      onNodeWithText("Lanes").assertDoesNotExist()
      onNode(hasText("lanes done", substring = true)).assertDoesNotExist()
    }
}
