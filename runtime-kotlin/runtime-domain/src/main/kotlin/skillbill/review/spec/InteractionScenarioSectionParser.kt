package skillbill.review.spec

import skillbill.review.spec.model.InteractionScenario
import skillbill.review.spec.model.InteractionScenarioCatalog
import skillbill.review.spec.model.InteractionScenarioIssue
import skillbill.review.spec.model.InteractionScenarioParseResult
import skillbill.review.spec.model.InteractionScenarioSpecContext
import skillbill.review.spec.model.TransitionalRegression
import skillbill.workflow.taskruntime.model.audit.acceptanceCriterionIdentity
import skillbill.contracts.workflow.featuretask.InteractionScenarioCatalogPayloadKeys as Keys

object InteractionScenarioSectionParser {
  const val SCENARIOS_HEADING_PREFIX: String = "interaction scenarios"
  const val REGRESSIONS_HEADING_PREFIX: String = "transitional regressions"

  fun parse(
    specText: String,
    specDigest: String,
    context: InteractionScenarioSpecContext,
  ): InteractionScenarioParseResult = InteractionScenarioParseRun(specText, context).run(specDigest)
}

private enum class ScenarioField(val label: String, val key: String, val ofRegression: Boolean = false) {
  CRITERIA("Criteria", Keys.CRITERION_REFS),
  SUBTASKS("Subtasks", Keys.SUBTASK_REFS),
  ENTRY_POINT("Entry point", Keys.ENTRY_POINT),
  PRECONDITIONS("Preconditions", Keys.PRECONDITIONS),
  STEPS("Steps", Keys.STEPS),
  OUTCOME("Outcome", Keys.OUTCOME),
  BEHAVIOR("Behavior", Keys.BEHAVIOR, ofRegression = true),
  DISABLED_BY("Disabled by", Keys.DISABLED_BY_SUBTASK, ofRegression = true),
  RESTORED_BY("Restored by", Keys.RESTORED_BY_SUBTASK, ofRegression = true),
}

private class InteractionScenarioParseRun(
  private val specText: String,
  private val context: InteractionScenarioSpecContext,
) {
  private val issues = mutableListOf<InteractionScenarioIssue>()
  private val knownCriteria: Set<String> =
    GovernedSpecSectionParser.parseListSection(specText) {
      it.startsWith(GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX)
    }.mapIndexedNotNull { index, text -> acceptanceCriterionIdentity(text, index + 1)?.canonicalRef }.toSet()

  fun run(specDigest: String): InteractionScenarioParseResult {
    val scenarioBody =
      GovernedSpecSectionParser.sectionBody(specText) {
        it.startsWith(InteractionScenarioSectionParser.SCENARIOS_HEADING_PREFIX)
      }
    val regressionBody =
      GovernedSpecSectionParser.sectionBody(specText) {
        it.startsWith(InteractionScenarioSectionParser.REGRESSIONS_HEADING_PREFIX)
      }
    val scenarios = scenarioBody?.let(::scenarios).orEmpty()
    val regressions = regressionBody?.let(::regressions).orEmpty()
    val catalog = InteractionScenarioCatalog(specDigest, scenarios, regressions)
    if (issues.isEmpty()) issues += InteractionScenarioCatalogShape.issues(catalog)
    return if (issues.isEmpty()) {
      InteractionScenarioParseResult.Parsed(catalog, scenarioBody != null, regressionBody != null)
    } else {
      InteractionScenarioParseResult.Rejected(issues.toList())
    }
  }

  private fun scenarios(body: String): List<InteractionScenario> {
    val items = items(body, Keys.SCENARIOS)
    if (body.isBlank()) report(Keys.SCENARIOS, "section must list at least one scenario.")
    if (items.size > InteractionScenarioCatalog.MAX_SCENARIOS) {
      report(Keys.SCENARIOS, "at most ${InteractionScenarioCatalog.MAX_SCENARIOS} scenarios are allowed.")
    }
    val seen = mutableSetOf<String>()
    return items.mapIndexedNotNull { index, text -> scenario("${Keys.SCENARIOS}[$index]", text, seen) }
  }

  private fun regressions(body: String): List<TransitionalRegression> {
    if (body.trim().equals(NONE, ignoreCase = true)) return emptyList()
    val items = items(body, Keys.TRANSITIONAL_REGRESSIONS)
    if (body.isBlank()) report(Keys.TRANSITIONAL_REGRESSIONS, "section must list regressions or read `None.`.")
    val seen = mutableSetOf<String>()
    return items.mapIndexedNotNull { index, text ->
      regression("${Keys.TRANSITIONAL_REGRESSIONS}[$index]", text, seen)
    }
  }

  private fun items(
    body: String,
    sectionPath: String,
  ): List<String> {
    val items = mutableListOf<StringBuilder>()
    var baseIndent: Int? = null
    body.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { line ->
      val item = LIST_ITEM.find(line)
      val indent = item?.groupValues?.get(1)?.length
      val itemText = item?.groupValues?.get(2)?.trim()
      if (itemText != null && indent != null && indent <= (baseIndent ?: indent)) {
        baseIndent = baseIndent ?: indent
        items += StringBuilder(itemText)
      } else if (items.isEmpty()) {
        report(sectionPath, "section content must be list items; found `${line.trim()}`.")
      } else {
        items.last().append('\n').append(itemText ?: line.trim())
      }
    }
    return items.map { it.toString() }
  }

  private fun scenario(
    path: String,
    text: String,
    seen: MutableSet<String>,
  ): InteractionScenario? {
    val head = SCENARIO_LABEL.find(text) ?: return reject(path, "item must start with an IS-NNN. label.")
    val ref = head.groupValues[1]
    if (!seen.add(ref)) report(path, "duplicate label $ref.")
    val fields = fields(head.groupValues[2], SCENARIO_FIELD_LABEL)
    return InteractionScenario(
      scenarioRef = ref,
      criterionRefs = criterionRefs(path, ref, fields),
      subtaskRefs = subtaskRefs(path, ref, fields),
      entryPoint = required(path, ref, ScenarioField.ENTRY_POINT, fields),
      preconditions = required(path, ref, ScenarioField.PRECONDITIONS, fields),
      steps = required(path, ref, ScenarioField.STEPS, fields),
      outcome = required(path, ref, ScenarioField.OUTCOME, fields),
    )
  }

  private fun criterionRefs(
    path: String,
    ref: String,
    fields: Map<ScenarioField, String>,
  ): List<String> {
    val fieldPath = "$path.${Keys.CRITERION_REFS}"
    val raw = required(path, ref, ScenarioField.CRITERIA, fields)
    val refs = commaList(raw).distinct()
    refs.forEach { criterion ->
      if (!InteractionScenarioCatalogShape.CRITERION_REF.matches(criterion)) {
        report(fieldPath, "$ref criterion ref `$criterion` must match AC-NNN.")
      } else if (criterion !in knownCriteria) {
        report(fieldPath, "$ref criterion ref $criterion does not resolve to a criterion of this spec.")
      }
    }
    if (raw.isNotBlank() && refs.size < InteractionScenarioCatalog.MIN_CRITERION_REFS) {
      report(fieldPath, "$ref needs at least two distinct criterion refs.")
    }
    return refs
  }

  private fun subtaskRefs(
    path: String,
    ref: String,
    fields: Map<ScenarioField, String>,
  ): List<Int> {
    val fieldPath = "$path.${Keys.SUBTASK_REFS}"
    if (context !is InteractionScenarioSpecContext.Parent) {
      if (ScenarioField.SUBTASKS in fields) {
        report(fieldPath, "$ref cannot declare a Subtasks: field in a subtask spec.")
      }
      return emptyList()
    }
    val raw = required(path, ref, ScenarioField.SUBTASKS, fields)
    val ids =
      commaList(raw).mapNotNull { token ->
        token.toIntOrNull()?.takeIf { it >= 1 } ?: reject<Int>(fieldPath, "$ref subtask ref `$token` is not an id.")
      }.distinct()
    if (raw.isNotBlank() && ids.size < InteractionScenarioCatalog.MIN_PARENT_SUBTASK_REFS) {
      report(fieldPath, "$ref must name at least two distinct subtasks.")
    }
    ids.filter { it !in context.knownSubtaskIds }.forEach { report(fieldPath, "$ref names unknown subtask $it.") }
    return ids
  }

  private fun regression(
    path: String,
    text: String,
    seen: MutableSet<String>,
  ): TransitionalRegression? {
    val head = REGRESSION_LABEL.find(text) ?: return reject(path, "item must start with a TR-NNN. label.")
    val ref = head.groupValues[1]
    if (!seen.add(ref)) report(path, "duplicate label $ref.")
    val fields = fields(head.groupValues[2], REGRESSION_FIELD_LABEL)
    val disabledBy = subtaskNumber(path, ref, ScenarioField.DISABLED_BY, fields)
    val restoredBy = subtaskNumber(path, ref, ScenarioField.RESTORED_BY, fields)
    regressionSubtaskIssues(path, ref, disabledBy, restoredBy)
    return TransitionalRegression(
      regressionRef = ref,
      behavior = required(path, ref, ScenarioField.BEHAVIOR, fields),
      disabledBySubtask = disabledBy ?: 0,
      restoredBySubtask = restoredBy ?: 0,
    )
  }

  private fun regressionSubtaskIssues(
    path: String,
    ref: String,
    disabledBy: Int?,
    restoredBy: Int?,
  ) {
    if (context !is InteractionScenarioSpecContext.Parent) {
      report(path, "$ref transitional regressions belong in the parent spec.")
      return
    }
    listOf(
      Keys.DISABLED_BY_SUBTASK to disabledBy,
      Keys.RESTORED_BY_SUBTASK to restoredBy,
    ).forEach { (key, id) ->
      if (id != null && id !in context.knownSubtaskIds) report("$path.$key", "$ref names unknown subtask $id.")
    }
    if (disabledBy != null && restoredBy != null && restoredBy <= disabledBy) {
      report("$path.${Keys.RESTORED_BY_SUBTASK}", "$ref must be restored by a subtask later than subtask $disabledBy.")
    }
  }

  private fun subtaskNumber(
    path: String,
    ref: String,
    field: ScenarioField,
    fields: Map<ScenarioField, String>,
  ): Int? {
    val raw = required(path, ref, field, fields)
    if (raw.isBlank()) return null
    val number =
      SUBTASK_NUMBER.matchEntire(raw.trim().trimEnd('.'))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }
    return number ?: reject(
      "$path.${field.key}",
      "$ref ${field.label}: must read `subtask N.`.",
    )
  }

  private fun required(
    path: String,
    ref: String,
    field: ScenarioField,
    fields: Map<ScenarioField, String>,
  ): String {
    val value = fields[field].orEmpty()
    if (value.isBlank()) report("$path.${field.key}", "$ref is missing a non-blank ${field.label}: field.")
    return value
  }

  private fun report(
    fieldPath: String,
    reason: String,
  ) {
    issues += InteractionScenarioIssue(fieldPath, reason)
  }

  private fun <T : Any> reject(
    fieldPath: String,
    reason: String,
  ): T? {
    report(fieldPath, reason)
    return null
  }

  private fun fields(
    rest: String,
    labelRegex: Regex,
  ): Map<ScenarioField, String> {
    val matches = labelRegex.findAll(rest).toList()
    val fields = linkedMapOf<ScenarioField, String>()
    matches.forEachIndexed { index, match ->
      val end = matches.getOrNull(index + 1)?.range?.first ?: rest.length
      val field = ScenarioField.entries.first { it.label.equals(match.groupValues[1], ignoreCase = true) }
      fields.putIfAbsent(field, rest.substring(match.range.last + 1, end).replace(WHITESPACE_RUN, " ").trim())
    }
    return fields
  }

  private fun commaList(raw: String): List<String> =
    raw.trim().trimEnd('.').split(',').map { it.trim() }.filter { it.isNotEmpty() }

  private companion object {
    const val NONE = "None."
    val LIST_ITEM = Regex("""^(\s*)(?:\d+\.|[-*])\s+(.*)$""")
    val SCENARIO_LABEL = Regex("""^(IS-\d{3})\.\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    val REGRESSION_LABEL = Regex("""^(TR-\d{3})\.\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
    val SUBTASK_NUMBER = Regex("""subtask\s+(\d+)""", RegexOption.IGNORE_CASE)
    val WHITESPACE_RUN = Regex("""\s+""")
    const val MAX_LABEL_GAP = 8
    val SCENARIO_FIELD_LABEL = fieldLabelRegex(ofRegression = false)
    val REGRESSION_FIELD_LABEL = fieldLabelRegex(ofRegression = true)

    fun fieldLabelRegex(ofRegression: Boolean): Regex {
      val labels = ScenarioField.entries.filter { it.ofRegression == ofRegression }
      return Regex(
        "(?:^|(?<=[.!?]\\s{1,$MAX_LABEL_GAP}))(" + labels.joinToString("|") { Regex.escape(it.label) } + "):\\s*",
        setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE),
      )
    }
  }
}
