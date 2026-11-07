package dev.skillbill.desktop.core.designsystem.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MetricStripTest {
  @Test
  fun `lead draws before the label`() = runComposeUiTest {
    setContent {
      SkillBillTheme(reducedMotion = true) {
        MetricStrip(listOf(MetricCell("Stage", lead = { Box(Modifier.testTag("lead").size(12.dp)) }, tone = Color.Red)))
      }
    }

    onNodeWithTag("lead").assertExists()
    assertTrue(onNodeWithTag("lead").getUnclippedBoundsInRoot().right <= onNodeWithText("Stage").getUnclippedBoundsInRoot().left)
  }

  @Test
  fun `lead and tone keep the plain strip height`() = runComposeUiTest {
    val plain = listOf("one", "two", "three").map { MetricCell(it, value = "42", note = "phase") }
    val rich = listOf("one", "two", "three").map { label ->
      MetricCell(label, value = "42", note = "phase", lead = { Box(Modifier.size(14.dp)) }, tone = Color.Red)
    }
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column(Modifier.width(600.dp)) {
          MetricStrip(plain, Modifier.testTag("plain"))
          MetricStrip(rich, Modifier.testTag("rich"))
        }
      }
    }

    assertEquals(onNodeWithTag("plain").getUnclippedBoundsInRoot().height, onNodeWithTag("rich").getUnclippedBoundsInRoot().height)
  }
}
