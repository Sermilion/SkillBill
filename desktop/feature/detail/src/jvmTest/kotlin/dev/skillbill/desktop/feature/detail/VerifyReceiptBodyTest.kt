package dev.skillbill.desktop.feature.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.skillbill.desktop.core.designsystem.SkillBillTheme
import dev.skillbill.desktop.core.domain.model.VerifyCompletenessAudit
import dev.skillbill.desktop.core.domain.model.VerifyCriterionGrade
import dev.skillbill.desktop.core.domain.model.VerifyFlagAudit
import dev.skillbill.desktop.core.domain.model.VerifyFlagCheck
import dev.skillbill.desktop.core.domain.model.VerifyReviewFinding
import dev.skillbill.desktop.core.domain.model.VerifyReviewSection
import dev.skillbill.desktop.core.domain.model.VerifyTestValueRow
import dev.skillbill.desktop.core.domain.model.VerifyUnitTestValue
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class VerifyReceiptBodyTest {
  @Test
  fun `narrow receipt fields render report structure instead of raw markdown`() = runComposeUiTest {
    val report = "## Recovery\n\n1. Preserve local edits.\n2. Retry the write.\n\n" +
      "| Criterion | Status |\n| --- | --- |\n| Local edits | PARTIAL |\n\n" +
      "```kotlin\nwrite(localEdits)\n```"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        Column(Modifier.width(330.dp)) { VerifyReceiptField("Evidence", report) }
      }
    }

    onNodeWithText(report).assertDoesNotExist()
    listOf("Recovery", "1.", "Preserve local edits.", "2.", "Retry the write.", "Criterion", "Local edits", "Status", "PARTIAL", "write(localEdits)").forEach {
      onNodeWithText(it).assertExists()
    }
  }

  @Test
  fun `completeness receipt shows unparsed findings with a verdict`() = runComposeUiTest {
    var target = ""
    val unparsed = "Audit note without a grade line"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyCompletenessReceipt(VerifyCompletenessAudit("passed", listOf(unparsed), emptyList())) { target = it }
      }
    }

    onNodeWithText(unparsed).assertDoesNotExist()
    onNodeWithText("Original report").performClick()
    onNodeWithText(unparsed).assertExists()
    onNodeWithText("Show the graded criteria").performClick()
    assertEquals(VERIFY_CRITERIA_KEY, target)
  }

  @Test
  fun `completeness receipt shows unparsed lines beside parsed grades`() = runComposeUiTest {
    val gradeLine = "[PASS] #1: Required behavior works"
    val extraLine = "Additional operator detail"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyCompletenessReceipt(
          VerifyCompletenessAudit("passed", listOf(gradeLine, extraLine), listOf(VerifyCriterionGrade(1, "PASS", "Required behavior works", emptyList(), null, null, emptyList()))),
        ) {}
      }
    }

    onNodeWithText("Show the graded criteria").assertExists()
    onNodeWithText(gradeLine).assertDoesNotExist()
    onNodeWithText("Original report").performClick()
    onNodeWithText(gradeLine).assertExists()
    onNodeWithText(extraLine).assertExists()
  }

  @Test
  fun `partially parsed flag audit shows original findings and keeps checks`() = runComposeUiTest {
    val raw = "Additional flag audit details"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyFlagAuditReceipt(VerifyFlagAudit("passed", listOf("Flag: feature_x", raw), null, "feature_x", null, null, listOf(VerifyFlagCheck(true, "Flag is declared"))))
      }
    }

    onNodeWithText("Flag is declared").assertExists()
    onNodeWithText(raw).assertDoesNotExist()
    onNodeWithText("Original report").performClick()
    onNodeWithText(raw).assertExists()
  }

  @Test
  fun `verify finding description toggle tracks finding identity`() = runComposeUiTest {
    var finding by mutableStateOf(
      VerifyReviewFinding("F-001", "minor", "high", "First.kt:1", "First expanded description text", emptyList(), emptyList()),
    )
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyFindingRow(finding)
      }
    }

    onNodeWithText("First expanded description text").assertDoesNotExist()
    onNodeWithText("First expanded description text", substring = true).performClick()
    onNodeWithText("First expanded description text").assertExists()
    runOnIdle {
      finding = VerifyReviewFinding("F-002", "minor", "high", "Second.kt:2", "Second expanded description text", emptyList(), emptyList())
    }
    onNodeWithText("First expanded description text").assertDoesNotExist()
    onNodeWithText("Second expanded description text", substring = true).performClick()
    onNodeWithText("Second expanded description text").assertExists()
  }

  @Test
  fun `partially parsed review shows original findings and keeps finding row`() = runComposeUiTest {
    val raw = "More review context that was not parsed"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyReviewReceipt(
          VerifyReviewSection("changes_requested", listOf("GA-1: parsed finding", raw), listOf(VerifyReviewFinding("GA-1", "minor", "high", "Verify.kt:1", "Parsed finding description", emptyList(), emptyList()))),
        )
      }
    }

    onNodeWithText("Parsed finding description").assertExists()
    onNodeWithText(raw).assertDoesNotExist()
    onNodeWithText("Original report").performClick()
    onNodeWithText(raw).assertExists()
  }

  @Test
  fun `partially parsed unit test value shows original findings and keeps rows`() = runComposeUiTest {
    val raw = "## Missing cases\n\n- Populate the store before cleanup.\n\n| Test or area | Verdict | Why |\n| --- | --- | --- |\n| Populated cleanup | Weak | Retains an old row |"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyTestValueReceipt(
          VerifyUnitTestValue("mixed", listOf("Overall: mixed", raw), null, "mixed", null,
            listOf(VerifyTestValueRow("Parser", "valuable", "high", listOf("ParserTest.kt:12"), "Catches malformed input", "Keep", listOf("`ParserTest.kt:12`"))),
            emptyList(), emptyList(), emptyList(), emptyList()),
        )
      }
    }

    onNodeWithText("Catches malformed input").assertExists()
    onAllNodesWithText("ParserTest.kt:12").assertCountEquals(1)
    onNodeWithText(raw).assertDoesNotExist()
    onNodeWithText("Original report").performClick()
    onNodeWithText("Missing cases").assertExists()
    onNodeWithText("Populate the store before cleanup.").assertExists()
    onNodeWithText("Populated cleanup").assertExists()
    onNodeWithText("Retains an old row").assertExists()
  }
}
