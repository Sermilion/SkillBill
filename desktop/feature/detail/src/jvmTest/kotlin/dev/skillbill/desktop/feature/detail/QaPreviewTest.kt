package dev.skillbill.desktop.feature.detail

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.QaPreviewFrame
import dev.skillbill.desktop.core.domain.model.QaPreviewState
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class QaPreviewTest {
  @Test
  fun `preview announces waiting reasons and removes the image when the run ends`() = runComposeUiTest {
    val state = mutableStateOf<QaPreviewState>(QaPreviewState.Waiting(QaPreviewState.Reason.SourceUnavailable))
    setContent {
      SkillBillTheme(reducedMotion = true) {
        QaPreview("run-1", state.value)
      }
    }

    onNodeWithText("Desktop preview").assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
    onNodeWithText("The private framebuffer is not available yet.")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

    runOnIdle { state.value = QaPreviewState.Waiting(QaPreviewState.Reason.InvalidSource) }
    onNodeWithText("The framebuffer source was rejected.")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

    runOnIdle { state.value = QaPreviewState.Live(QaPreviewFrame(1, 1, intArrayOf(0xff112233.toInt()))) }
    onNodeWithContentDescription("Live desktop QA preview for run run-1").assertIsDisplayed()
    onNodeWithText("Live").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))

    runOnIdle { state.value = QaPreviewState.Ended }
    onNodeWithContentDescription("Live desktop QA preview for run run-1").assertDoesNotExist()
    onNodeWithText("Ended").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
  }
}
