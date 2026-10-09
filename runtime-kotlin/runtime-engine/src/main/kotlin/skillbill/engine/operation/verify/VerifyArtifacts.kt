package skillbill.engine.operation.verify

internal object VerifyWorkflow {
  const val COLLECT_INPUTS = "collect_inputs"
  const val EXTRACT_CRITERIA = "extract_criteria"
  const val GATHER_DIFF = "gather_diff"
  const val FEATURE_FLAG_AUDIT = "feature_flag_audit"
  const val CODE_REVIEW = "code_review"
  const val UNIT_TEST_VALUE_CHECK = "unit_test_value_check"
  const val COMPLETENESS_AUDIT = "completeness_audit"
  const val VERDICT = "verdict"
  const val FINISH = "finish"

  val CONFIRMED_STEPS: List<String> =
    listOf(GATHER_DIFF, FEATURE_FLAG_AUDIT, CODE_REVIEW, UNIT_TEST_VALUE_CHECK, COMPLETENESS_AUDIT, VERDICT, FINISH)

  const val INPUT_CONTEXT = "input_context"
  const val CRITERIA_SUMMARY = "criteria_summary"
  const val DIFF_PROJECTION = "diff_projection"
  const val FEATURE_FLAG_POLICY = "feature_flag_policy"
  const val REVIEW_RUBRIC = "review_rubric"
  const val UNIT_TEST_VALUE_RUBRIC = "unit_test_value_rubric"
  const val COMPLETENESS_RUBRIC = "completeness_rubric"
  const val FEATURE_FLAG_AUDIT_RECEIPT = "feature_flag_audit_receipt"
  const val CODE_REVIEW_RECEIPT = "code_review_receipt"
  const val UNIT_TEST_VALUE_RECEIPT = "unit_test_value_receipt"
  const val COMPLETENESS_AUDIT_RECEIPT = "completeness_audit_receipt"
  const val VERDICT_RESULT = "verdict_result"
  const val SESSION_NOTES = "session_notes"

  const val REPO_ROOT = "repo_root"
  const val SPEC_PATH = "spec_path"
  const val INTAKE = "intake"
  const val TARGET = "target"
  const val BASE_REVISION = "base_revision"
  const val HEAD_REVISION = "head_revision"
  const val REVIEW_MODE = "review_mode"
  const val SUPERSEDED_BY = "superseded_by"

  const val CONTRACT_VERSION = "contract_version"
  const val RULES = "rules"
  const val VERDICT_FIELD = "verdict"
  const val FINDINGS = "findings"
  const val CHECKPOINT = "checkpoint"
  const val COMPARISON_SCOPE = "comparison_scope"
  const val CHANGED_FILES = "changed_files"

  const val ARTIFACT_CONTRACT_VERSION = "0.1"

  const val SKIPPED = "skipped"

  fun policy(rules: String): Map<String, Any?> = mapOf(CONTRACT_VERSION to ARTIFACT_CONTRACT_VERSION, RULES to rules)

  fun receipt(
    verdict: String,
    findings: List<String>,
  ): Map<String, Any?> =
    mapOf(CONTRACT_VERSION to ARTIFACT_CONTRACT_VERSION, VERDICT_FIELD to verdict, FINDINGS to findings)

  fun string(
    artifact: Any?,
    field: String,
  ): String? = (artifact as? Map<*, *>)?.get(field) as? String

  fun strings(
    artifact: Any?,
    field: String,
  ): List<String> = ((artifact as? Map<*, *>)?.get(field) as? List<*>).orEmpty().filterIsInstance<String>()
}

internal data class VerifyCriteria(
  val acceptanceCriteria: String,
  val nonGoals: String,
  val rolloutExpectation: String,
  val technicalConstraints: String,
) {
  val acceptanceCriteriaCount: Int get() = acceptanceCriteria.lines().count(LIST_ITEM::containsMatchIn)

  val rolloutRelevant: Boolean get() =
    rolloutExpectation.isNotBlank() && !NOT_REQUIRED.containsMatchIn(rolloutExpectation.trim().trimStart('*', '-', ' '))

  fun toArtifact(): Map<String, Any?> =
    mapOf(
      ACCEPTANCE_CRITERIA to acceptanceCriteria,
      NON_GOALS to nonGoals,
      ROLLOUT_EXPECTATION to rolloutExpectation,
      TECHNICAL_CONSTRAINTS to technicalConstraints,
    )

  fun summary(): String =
    listOf(
      "## Acceptance criteria" to acceptanceCriteria,
      "## Non-goals" to nonGoals,
      "## Rollout expectation" to rolloutExpectation,
      "## Key technical constraints" to technicalConstraints,
    ).joinToString("\n\n") { (heading, body) -> "$heading\n\n${body.ifBlank { "(none)" }}" }

  companion object {
    private const val ACCEPTANCE_CRITERIA = "acceptance_criteria"
    private const val NON_GOALS = "non_goals"
    private const val ROLLOUT_EXPECTATION = "rollout_expectation"
    private const val TECHNICAL_CONSTRAINTS = "technical_constraints"

    private val LIST_ITEM = Regex("""^\s*(\d+[.)]|[-*])\s+\S""")
    private val NOT_REQUIRED = Regex("""^(no|none|n/?a|not required|not needed)\b""", RegexOption.IGNORE_CASE)
    private val NUMBER_PREFIX = Regex("""^\d+[.)]\s*""")
    private val HEADINGS: List<Pair<String, List<String>>> =
      listOf(
        ACCEPTANCE_CRITERIA to listOf("acceptance criteria"),
        NON_GOALS to listOf("non-goals", "non goals"),
        ROLLOUT_EXPECTATION to listOf("rollout expectation"),
        TECHNICAL_CONSTRAINTS to listOf("key technical constraints", "technical constraints"),
      )

    fun parse(prose: String): VerifyCriteria {
      val sections = mutableMapOf<String, MutableList<String>>()
      var current: String? = null
      prose.lines().forEach { line ->
        val heading = headingOf(line)
        if (heading != null) {
          current = heading.first
          val lines = sections.getOrPut(heading.first) { mutableListOf() }
          if (heading.second.isNotBlank()) lines += heading.second
        } else {
          current?.let { key -> sections.getValue(key).add(line) }
        }
      }

      fun part(key: String): String = sections[key].orEmpty().joinToString("\n").trim()
      if (sections.isEmpty()) return VerifyCriteria(prose.trim(), "", "", "")
      return VerifyCriteria(
        part(ACCEPTANCE_CRITERIA),
        part(NON_GOALS),
        part(ROLLOUT_EXPECTATION),
        part(TECHNICAL_CONSTRAINTS),
      )
    }

    fun fromArtifact(artifact: Any?): VerifyCriteria =
      VerifyCriteria(
        VerifyWorkflow.string(artifact, ACCEPTANCE_CRITERIA).orEmpty(),
        VerifyWorkflow.string(artifact, NON_GOALS).orEmpty(),
        VerifyWorkflow.string(artifact, ROLLOUT_EXPECTATION).orEmpty(),
        VerifyWorkflow.string(artifact, TECHNICAL_CONSTRAINTS).orEmpty(),
      )

    private fun headingOf(line: String): Pair<String, String>? {
      val trimmed = line.trim()
      val marked = trimmed.startsWith("#") || trimmed.startsWith("*") || trimmed.firstOrNull()?.isDigit() == true
      val text = trimmed.trimStart('#', ' ').replace("**", "").replace(NUMBER_PREFIX, "").trim()
      val lower = text.lowercase()
      HEADINGS.forEach { (key, names) ->
        names.firstOrNull { name -> lower.startsWith(name) }?.let { name ->
          val rest = text.substring(name.length).trimStart(' ', ':', '—', '–', '-').trim()
          if (marked || rest.isEmpty()) return key to rest
        }
      }
      return null
    }
  }
}
