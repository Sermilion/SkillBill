package skillbill.engine.work.verify

import skillbill.engine.operation.verify.VerifyPromptSections
import skillbill.engine.operation.verify.VerifyWorkflow
import skillbill.ports.idestatus.model.VerifyFlagAudit
import skillbill.ports.idestatus.model.VerifyFlagCheck

internal object VerifyFlagAuditParser {
  private const val HEADING = "FEATURE FLAG AUDIT"
  private val CHECK = Regex("""\[\s*(PASS|FAIL)\s*]""")
  private val LABEL_PREFIX = Regex("""^\s*[-*]?\s*(?:\*\*)?""")

  fun parse(receipt: Any?): VerifyFlagAudit? {
    if (receipt == null) return null
    val verdict = VerifyWorkflow.string(receipt, VerifyWorkflow.VERDICT_FIELD)
    val findings = VerifyWorkflow.strings(receipt, VerifyWorkflow.FINDINGS)
    if (verdict == VerifyWorkflow.SKIPPED) {
      val reason = findings.firstOrNull()?.trim()?.removePrefix(VerifyPromptSections.SKIPPED_PREFIX)?.trim()
      return VerifyFlagAudit(verdict, findings, reason, null, null, null, emptyList())
    }
    val lines = audited(findings.flatMap(String::lines))
    return VerifyFlagAudit(
      verdict = verdict,
      findings = findings,
      skipReason = null,
      flagName = label(lines, "Flag name:"),
      pattern = label(lines, "Pattern:"),
      issues = label(lines, "Issues:"),
      checks =
        lines.mapNotNull { line ->
          CHECK.find(line)?.let { VerifyFlagCheck(it.groupValues[1] == "PASS", line.replace(it.value, "").trim()) }
        },
    )
  }

  private fun audited(lines: List<String>): List<String> {
    val start = lines.indexOfFirst { HEADING in it }
    if (start < 0) return lines
    return listOf(lines[start].substringAfter(HEADING)) + lines.drop(start + 1)
  }

  private fun label(
    lines: List<String>,
    label: String,
  ): String? =
    lines.firstNotNullOfOrNull { line ->
      val text = line.replace(LABEL_PREFIX, "")
      text.takeIf { it.startsWith(label) }?.removePrefix(label)?.trim()?.removePrefix("**")?.trim()
    }
}
