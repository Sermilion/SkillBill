package skillbill.workflow.taskruntime.model.blockincident.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductReportRedactionTest {
  @Test
  fun redactsAndDetectsForeignFramesWithJvmMethodNamesAndSentencePunctuation() {
    listOf(
      "other.Helper",
      "other+skillbill.Helper",
      "other skillbill.Helper",
      "other\nskillbill.Helper",
      "other!skillbill.Helper",
      "other(skillbill.Helper",
      "other'skillbill.Helper",
      "other:skillbill.Helper",
      "other\\skillbill.Helper",
      "other.😀Helper",
    ).flatMap(::frames).forEach { frame ->
      val (redacted, changed) = ProductReportRedaction.redact(frame)

      assertTrue(ProductReportRedaction.hasResidualSecret(frame), frame)
      assertTrue(changed, frame)
      assertFalse(redacted.contains("Helper"), frame)
      assertTrue(redacted.contains("[redacted]"), frame)
      assertFalse(ProductReportRedaction.hasResidualSecret(redacted), frame)
    }
  }

  @Test
  fun preservesSkillBillDeclaringClassesAcrossFrameFormats() {
    listOf(
      "skillbill.Helper",
      "app/skillbill.Helper",
      "runtime@1.0/skillbill.Helper",
      "app/runtime@1.0/skillbill.Helper",
      "skillbill.Helper+Generated",
      "skillbill.Helper name",
      "skillbill.Helper\nname",
      "skillbill.H(e)lper",
      "skillbill.😀Helper",
    )
      .flatMap(::frames)
      .forEach { frame ->
        assertEquals(frame to false, ProductReportRedaction.redact(frame), frame)
        assertFalse(ProductReportRedaction.hasResidualSecret(frame), frame)
      }
  }

  @Test
  fun doesNotKeepForeignDeclaringClassesInSkillBillNamedModulesOrLoaders() {
    listOf("skillbill.loader/other.Helper", "skillbill.module@1.0/other.Helper")
      .flatMap(::frames)
      .forEach { frame ->
        assertTrue(ProductReportRedaction.hasResidualSecret(frame), frame)
        assertFalse(ProductReportRedaction.redact(frame).first.contains("other.Helper"), frame)
      }
  }

  @Test
  fun redactsEveryForeignFrameInMixedInlineAndMultilineTraces() {
    listOf(" ", "\n", "; ").forEach { separator ->
      val value =
        listOf(
          "skillbill.Helper.call:42",
          "other+skillbill.Helper.call:43",
          "skillbill.Other.call:44",
          "other.Last.call:45",
        )
          .joinToString(separator)
      val (redacted, changed) = ProductReportRedaction.redact(value)

      assertTrue(changed, value)
      assertFalse(redacted.contains("other"), redacted)
      assertTrue(redacted.contains("skillbill.Helper.call:42"), redacted)
      assertTrue(redacted.contains("skillbill.Other.call:44"), redacted)
      assertFalse(ProductReportRedaction.hasResidualSecret(redacted), redacted)
    }
  }

  private fun frames(className: String): List<String> =
    listOf(
      "call",
      "call+impl",
      "load value",
      "call!#%&*=?:value",
      "call(impl)",
      "call]impl",
      "call\\impl",
      "call'quoted`value\"",
      "load\tvalue",
      "load\nvalue",
      "load\rvalue",
      "call-impl",
      "読み込む",
      "<init>",
      "<clinit>",
    )
      .flatMap { method ->
        listOf(":42", ":-1", ":+42", "(Unknown Source)", "(Helper.kt:42)", "(Native Method)")
          .flatMap { location ->
            listOf("", ".", ",", ";", "!", "?").flatMap { ending ->
              val frame = "$className.$method$location$ending"
              listOf(frame, "Suspected: $frame", "at $frame", "\"$frame\"", "'$frame'", "`$frame`")
            }
          }
      }
}
