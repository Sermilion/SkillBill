package skillbill.infrastructure.skills.scaffold.rendering

import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.skills.nativeagent.rendering.YAML_DOUBLE_QUOTE_ESCAPES
import skillbill.infrastructure.skills.scaffold.runtime.service.areaFocus
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.ScaffoldAuthoringFailureCode
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext

private val FRONTMATTER_BLOCK_LEADING = Regex("""(?s)\A---\n.*?\n---\n""")

private val AREA_DESCRIPTION_PHRASES: Map<String, String> =
  mapOf(
    "architecture" to "architecture, boundaries, and dependency direction",
    "performance" to "performance risks on hot paths, blocking I/O, and resource usage",
    "platform-correctness" to "lifecycle, concurrency, threading, and logic correctness",
    "security" to "secrets handling, auth, and sensitive-data exposure",
    "testing" to "test coverage quality and regression protection",
    "api-contracts" to "API contracts, request validation, and serialization",
    "persistence" to "persistence, transactions, migrations, and data consistency",
    "reliability" to "timeouts, retries, background work, and observability",
    "ui" to "UI correctness and framework usage",
    "ux-accessibility" to "UX correctness and accessibility",
  )

internal fun defaultAreaFocus(area: String): String =
  AREA_DESCRIPTION_PHRASES[area] ?: "${area.replace('-', ' ')} risks"

internal fun inferSkillDescription(
  context: TemplateContext,
  areaFocus: String = "",
): String {
  val label = context.displayName.ifBlank { context.platform }
  return when (context.family) {
    "code-review" -> codeReviewDescription(context, label, areaFocus)
    "quality-check" ->
      if (label.isNotBlank()) {
        "Use when validating $label changes with the shared quality-check contract."
      } else {
        "Use when validating changes with the shared quality-check contract."
      }
    "feature-task" ->
      if (label.isNotBlank()) {
        "Use when implementing a feature end-to-end in $label codebases, from design doc to verified code."
      } else {
        "Use when implementing a feature end-to-end from design doc to verified code."
      }
    "feature-verify" ->
      if (label.isNotBlank()) {
        "Use when verifying a $label PR against its task spec."
      } else {
        "Use when verifying a PR against its task spec."
      }
    "add-on" ->
      if (label.isNotBlank()) {
        "Pack-owned supporting asset for the $label platform pack."
      } else {
        "Pack-owned supporting asset."
      }
    else -> "Use for ${context.skillName.removePrefix("bill-").replace("-", " ")} work."
  }
}

internal fun renderFrontmatter(
  skillName: String,
  description: String,
  internalFor: String? = null,
): String =
  buildString {
    fun scalar(value: String): String {
      if (value.isEmpty()) {
        return "\"\""
      }
      val edgeWhitespace = value.first().isWhitespace() || value.last().isWhitespace()
      val leadingReserved = value.first() in YAML_RESERVED_LEADING_CHARS
      val inlineSeparators = value.contains(": ") || value.contains(" #")
      val inlineReserved = value.any { char -> char in YAML_RESERVED_INLINE_CHARS }
      val needsQuoting = edgeWhitespace || leadingReserved || inlineSeparators || inlineReserved
      return if (needsQuoting) {
        "\"" + value.map { char -> YAML_DOUBLE_QUOTE_ESCAPES[char] ?: char.toString() }.joinToString("") + "\""
      } else {
        value
      }
    }

    appendLine("---")
    appendLine("name: ${scalar(skillName)}")
    appendLine("description: ${scalar(description)}")
    internalFor?.let { parent -> appendLine("internal-for: ${scalar(parent)}") }
    appendLine("---")
  }

internal fun renderDescriptorSection(
  context: TemplateContext,
  areaFocus: String,
): String =
  buildString {
    appendLine("## Descriptor")
    appendLine()
    appendLine("Governed skill: `${context.skillName}`")
    appendLine("Family: `${context.family}`")
    if (context.platform.isNotBlank()) {
      appendLine("Platform pack: `${context.platform}` (${context.displayName.ifBlank { context.platform }})")
    }
    if (context.area.isNotBlank()) {
      appendLine("Area: `${context.area}`")
    }
    appendLine("Description: ${inferSkillDescription(context, areaFocus)}")
  }

internal fun renderContentBody(
  context: TemplateContext,
  description: String,
  contentBody: String? = null,
  internalFor: String? = null,
): String {
  val resolvedDescription = description.ifBlank { inferSkillDescription(context) }

  contentBody?.let { raw ->
    if (FRONTMATTER_BLOCK_LEADING.containsMatchIn(raw.trimStart())) {
      throw SkillBillRuntimeException(
        ScaffoldAuthoringFailureCode.AUTHORING_FAILURE,
        "skill-bill new payload content_body for '${context.skillName}' must not start with a " +
          "YAML frontmatter block; the canonical block is rendered from the payload's name/" +
          "description.",
      )
    }
  }
  val body = contentBody?.trimEnd()?.plus("\n") ?: renderGovernedContentStarter(context, resolvedDescription)
  return buildString {
    append(renderFrontmatter(context.skillName, resolvedDescription, internalFor))
    appendLine()
    appendLine("# ${contentTitle(context)}")
    appendLine()
    appendLine(body.trimEnd())
  }
}

internal fun renderAddonBody(
  skillName: String,
  description: String,
  explicitBody: String?,
): String {
  val body =
    explicitBody ?: buildString {
      appendLine("# $skillName")
      appendLine()
      appendLine(description)
      appendLine()
      appendLine("TODO: replace this placeholder with the add-on guidance body.")
    }
  return if (body.endsWith('\n')) body else "$body\n"
}

private fun codeReviewDescription(
  context: TemplateContext,
  label: String,
  areaFocus: String,
): String {
  if (context.area.isNotBlank()) {
    val phrase = areaFocus.ifBlank { defaultAreaFocus(context.area) }
    return if (label.isNotBlank()) {
      "Use when reviewing $label changes for $phrase."
    } else {
      "Use when reviewing changes for $phrase."
    }
  }
  return if (label.isNotBlank()) {
    "Use when reviewing $label changes across code-review specialists."
  } else {
    "Use when reviewing code changes across code-review specialists."
  }
}

private val YAML_RESERVED_LEADING_CHARS: Set<Char> =
  setOf('-', '?', ':', ',', '[', ']', '{', '}', '#', '&', '*', '!', '|', '>', '\'', '"', '%', '@', '`')

private val YAML_RESERVED_INLINE_CHARS: Set<Char> = setOf('\n', '\r', '\t')

private val YAML_DOUBLE_QUOTE_ESCAPES: Map<Char, String> =
  mapOf(
    '\\' to "\\\\",
    '"' to "\\\"",
    '\n' to "\\n",
    '\r' to "\\r",
    '\t' to "\\t",
  )

private fun renderGovernedContentStarter(
  context: TemplateContext,
  description: String,
): String {
  val summary = description.ifBlank { inferSkillDescription(context) }
  return when {
    context.family == "quality-check" -> qualityCheckContent(summary)
    context.family == "code-review" && context.area.isNotBlank() ->
      areaReviewContent(summary, context.area, context.displayName.ifBlank { context.platform })
    else -> baselineReviewContent(summary)
  }
}

private fun contentTitle(context: TemplateContext): String =
  when {
    context.family == "quality-check" -> "Quality-Check Content"
    context.family == "code-review" && context.area.isNotBlank() ->
      "${context.area.replace('-', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }} Content"
    context.family == "code-review" -> "Review Content"
    else -> "Content"
  }
