package skillbill.engine.featuretask.phase.core

import skillbill.workflow.taskruntime.model.handoff.task.MAX_BOUNDED_POINTER_LENGTH

object FeatureTaskRuntimeSchemaFailureCorrections {
  fun lengthViolation(priorSchemaFailure: String): String {
    val cap = statedCap(priorSchemaFailure) ?: return ""

    return when {
      priorSchemaFailure.contains("artifact_ref") -> boundedPointerAdvice("artifact_ref", cap)
      priorSchemaFailure.contains("check_ref") -> boundedPointerAdvice("check_ref", cap)
      else -> compressionAdvice(offendingFieldName(priorSchemaFailure), cap)
    }
  }

  private fun statedCap(priorSchemaFailure: String): Int? {
    val stated = LENGTH_VIOLATION_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
    if (stated != null) {
      return stated.replace(",", "").toIntOrNull() ?: UNSTATED_CAP
    }
    return if (priorSchemaFailure.contains("maxLength")) UNSTATED_CAP else null
  }

  private fun offendingFieldName(priorSchemaFailure: String): String? {
    val pointer =
      DOLLAR_POINTER_PATTERN.find(priorSchemaFailure)?.value?.removePrefix("$")
        ?: SLASH_POINTER_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
        ?: BARE_PATH_PATTERN.find(priorSchemaFailure)?.groupValues?.get(1)
        ?: return null
    return pointer.split('.', '/')
      .map { it.substringBefore('[') }
      .lastOrNull { it.isNotBlank() }
  }

  private fun boundedPointerAdvice(
    field: String,
    cap: Int,
  ): String {
    val replacement =
      if (field == "artifact_ref") {
        "one repository-relative path, optionally followed by one :symbol, such as " +
          "runtime-kotlin/runtime-mcp/src/test/kotlin/skillbill/mcp/McpStdioServerTest.kt"
      } else {
        "one acceptance-criterion, finding, test, or check identifier, such as AC-005 or McpStdioServerTest"
      }
    val statedCap = if (cap == UNSTATED_CAP) MAX_BOUNDED_POINTER_LENGTH else cap
    return """

      The rejected $field is a bounded pointer, not an evidence container. Replace it with $replacement.
      It MUST be at most $statedCap characters. Do not concatenate multiple paths,
      symbols, findings, commands, or explanations into this field. Put necessary detail in the issue,
      fix, or other schema-authorized descriptive fields.
      """.trimIndent()
  }

  private fun compressionAdvice(
    field: String?,
    cap: Int,
  ): String {
    val subject = field?.let { "The rejected $it" } ?: "The rejected field"
    val limit = if (cap == UNSTATED_CAP) "its declared limit" else "$cap characters"
    return """

      $subject exceeded $limit. It is a bounded SUMMARY, not a verification transcript.
      Your previous attempt was rejected for length alone — its content was not disputed, so restating the
      same case at the same length will be rejected again. Shorten what you already claimed: keep the
      conclusion and one concrete anchor (a checkpoint fingerprint, a count of changed paths, a single
      representative path), and drop per-file walkthroughs, reasoning narration, and quoted output. If a
      segment applied no edits, say that it applied none and why it was already satisfied — the absence of
      work is shorter to report than to prove. Move any remaining detail into the schema-authorized
      descriptive fields for this projection, not into this one.
      """.trimIndent()
  }

  private const val UNSTATED_CAP: Int = -1
  private val LENGTH_VIOLATION_PATTERN =
    Regex("""(?:must be|allows) at most ([0-9][0-9,]*) characters""", RegexOption.IGNORE_CASE)
  private val DOLLAR_POINTER_PATTERN = Regex("""\$(?:\.[A-Za-z0-9_-]+|\[[0-9]+])+""")
  private val SLASH_POINTER_PATTERN = Regex("""at '(/[^\s']*)'""")
  private val BARE_PATH_PATTERN =
    Regex("""([A-Za-z_][A-Za-z0-9_\[\].-]*)\s*:\s*(?:must be|allows) at most""", RegexOption.IGNORE_CASE)
}
