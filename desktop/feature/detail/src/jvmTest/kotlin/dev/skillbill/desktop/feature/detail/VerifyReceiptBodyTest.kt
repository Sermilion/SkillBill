package dev.skillbill.desktop.feature.detail

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
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
  fun `completeness receipt shows unparsed findings with a verdict`() = runComposeUiTest {
    var target = ""
    val unparsed = "Audit note without a grade line"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyCompletenessReceipt(VerifyCompletenessAudit("passed", listOf(unparsed), emptyList())) { target = it }
      }
    }

    onNodeWithText("Verdict:").assertExists()
    onNodeWithText(unparsed).assertExists()
    onNodeWithText("View criteria").performClick()
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

    onNodeWithText("Verdict:").assertExists()
    onNodeWithText("View criteria").assertExists()
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
    onNodeWithText(raw).assertExists()
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
    onNodeWithText(raw).assertExists()
  }

  @Test
  fun `partially parsed unit test value shows original findings and keeps rows`() = runComposeUiTest {
    val raw = "Additional test value rationale"
    setContent {
      SkillBillTheme(reducedMotion = true) {
        VerifyTestValueReceipt(
          VerifyUnitTestValue("mixed", listOf("Overall: mixed", raw), null, "mixed", null,
            listOf(VerifyTestValueRow("Parser", "valuable", "high", emptyList(), "Catches malformed input", "Keep", emptyList())),
            emptyList(), emptyList(), emptyList(), emptyList()),
        )
      }
    }

    onNodeWithText("Catches malformed input").assertExists()
    onNodeWithText(raw).assertExists()
  }
}
