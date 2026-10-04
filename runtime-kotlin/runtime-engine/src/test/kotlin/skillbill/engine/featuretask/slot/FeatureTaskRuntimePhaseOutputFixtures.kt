package skillbill.engine.featuretask.slot

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.phase.briefing.PlanningProjectionFixtures
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.slot.state.PhaseLaunchState
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput

private const val VERSION: String = FEATURE_TASK_RUNTIME_CONTRACT_VERSION

internal const val REVIEW_FIX_BLOCKER_FINDING_ID = "F-001"

internal var harnessPendingVerifyFindingIds: List<String> = emptyList()

internal fun harnessReviewRunnerSyncingPendingVerifyFindings(delegate: PhaseRunner): PhaseRunner =
  object : PhaseRunner {
    override fun run(
      input: PhaseStepInput,
      state: PhaseLaunchState,
    ): PhaseStepOutput {
      val output = delegate.run(input, state)
      val lane =
        ParallelReviewLaneResult(
          agentId = input.facts.invokedAgentId,
          findings = ParallelReviewFindingParser.parse(output.stdout.text).findings,
        )
      harnessPendingVerifyFindingIds =
        ParallelReviewMerger.merge(lane, lane.copy(findings = emptyList())).findings.map { it.fNumber }
      return output
    }
  }

internal fun verifyFindingsPhaseOutput(
  verifiedFindingIds: List<String> = harnessPendingVerifyFindingIds,
): FeatureTaskRuntimePhaseOutput =
  FeatureTaskRuntimePhaseOutput(
    "verify_findings",
    1,
    verifyFindingsOutput(verifiedFindingIds),
  )

internal fun FeatureTaskRuntimePhaseOutput.withRawPayload(payload: String): FeatureTaskRuntimePhaseOutput =
  copy(output = PhaseOutput(value = payload), normalizedOutput = null)

internal fun verifyFindingsOutputForFindingIds(vararg findingIds: String): String =
  verifyFindingsOutput(findingIds.toList())

internal fun verifyFindingsOutput(verifiedFindingIds: List<String> = harnessPendingVerifyFindingIds): String {
  val dispositions =
    verifiedFindingIds.joinToString(",") { findingId ->
      """{"finding_id":"$findingId","disposition":"verified","boundary_context_unavailable":true}"""
    }
  val verdict = if (verifiedFindingIds.isEmpty()) "no_findings_verified" else "findings_verified"
  val dispositionsJson = if (dispositions.isEmpty()) "[]" else "[$dispositions]"
  return """
    {
      "contract_version": "$VERSION",
      "phase_id": "verify_findings",
      "status": "completed",
      "summary": "Phase produced a validated output.",
      "verdict": "$verdict",
      "produced_outputs": {"finding_dispositions": $dispositionsJson}
    }
    """.trimIndent()
}

internal val IMPLEMENT_NO_RECONCILE_OUTPUT: String =
  """
  {
    "contract_version": "$VERSION",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": {
      "value": "Implement prose without a reconciliation report.",
      "changed_files": ["src/Foo.kt"]
    }
  }
  """.trimIndent()

internal fun verdictPlanOutput(verdict: String): String =
  """
  {
    "contract_version": "$VERSION",
    "phase_id": "plan",
    "status": "completed",
    "summary": "Plan produced a validated output.",
    "verdict": "$verdict",
    "produced_outputs": ${validProducedOutputs("plan")}
  }
  """.trimIndent()

internal val FINALISED_COMMIT_PUSH_OUTPUT: String =
  """
  {
    "contract_version": "$VERSION",
    "phase_id": "commit_push",
    "status": "completed",
    "summary": "Phase produced a validated output.",
    "produced_outputs": ${commitPushProducedOutputs(commitSha = "commit-runtime-1")}
  }
  """.trimIndent()

internal fun commitPushProducedOutputs(
  commitSha: String? = null,
  changedPaths: List<String> = listOf("src/Foo.kt"),
): String {
  val sha = commitSha?.let { """"commit_sha":"$it",""" } ?: ""
  val pathsJson = changedPaths.joinToString(",") { "\"$it\"" }
  return """
    {"value":"Runtime committed the subtask and pushed it.","commit_push_result":{
      $sha
      "message":"SKILL-65: runtime feature-task parity",
      "changed_paths":[$pathsJson],
      "branch":"feat/SKILL-65-runtime-feature-task-parity",
      "base_branch":"main",
      "pushed":true
    }}
    """.trimIndent()
}

internal fun validJsonOutput(
  phaseId: String,
  commitPushChangedPaths: List<String>? = null,
): String {
  if (phaseId == "verify_findings") {
    return verifyFindingsOutput()
  }
  if (phaseId == "audit") {
    return """
      {
        "contract_version": "$VERSION",
        "phase_id": "audit",
        "status": "completed",
        "summary": "Phase produced a validated output.",
        "verdict": "satisfied",
        "produced_outputs": ${validProducedOutputs(phaseId, commitPushChangedPaths)}
      }
      """.trimIndent()
  }
  return """
    {
      "contract_version": "$VERSION",
      "phase_id": "$phaseId",
      "status": "completed",
      "summary": "Phase produced a validated output.",
      "produced_outputs": ${validProducedOutputs(phaseId, commitPushChangedPaths)}
    }
    """.trimIndent()
}

internal fun validJsonOutputForGitPhase(
  phaseId: String,
  git: RecordingWorkflowGitOperations,
): String =
  validJsonOutput(
    phaseId,
    commitPushChangedPaths =
      if (phaseId == "commit_push" && git.ownedPathsValue.isNotEmpty()) {
        git.ownedPathsValue
      } else {
        null
      },
  )

internal fun validProducedOutputs(
  phaseId: String,
  commitPushChangedPaths: List<String>? = null,
): String =
  if (phaseId == "commit_push") {
    commitPushProducedOutputs(
      commitSha = null,
      changedPaths = commitPushChangedPaths ?: listOf("src/Foo.kt"),
    )
  } else {
    STATIC_PRODUCED_OUTPUTS[phaseId] ?: """{"tasks":["task-1"]}"""
  }

private val STATIC_PRODUCED_OUTPUTS: Map<String, String> =
  mapOf(
    "validate" to VALIDATE_PRODUCED_OUTPUTS,
    "write_history" to WRITE_HISTORY_PRODUCED_OUTPUTS,
    "preplan" to preplanProducedOutputs(),
    "plan" to planProducedOutputs(),
    "implement" to implementProducedOutputs(),
    "simplify" to PlanningProjectionFixtures.SIMPLIFY_PROSE,
    "implement_fix" to implementFixProducedOutputs(),
    "review" to """{"findings": []}""",
    "audit_plan_fix" to
      """{"value":"### AC-001\nGap: Missing admission.\nProduction path: src/Foo.kt.\n""" +
      """Changes: Guard the mutation.\nClosure evidence: Mutation follows admission."}""",
    "audit_implement_fix" to
      """{"value": "Repaired the reported audit criteria.\naudit_repair_complete: true"}""",
    "audit" to """{"value": "[]"}""",
    "verify_findings" to """{"finding_dispositions": []}""",
    "pr" to """{"value": "Opened the pull request for the branch."}""",
  )

private const val VALIDATE_PRODUCED_OUTPUTS =
  """{"value":"Project checks passed.","validation_passed":true}"""

private const val WRITE_HISTORY_PRODUCED_OUTPUTS =
  """{"value":"Recorded the boundary history entry."}"""

private fun preplanProducedOutputs(): String =
  """
  {
    "value":"Fixture preplan prose for downstream plan."
  }
  """.trimIndent()

private fun planProducedOutputs(): String =
  """
  {
    "value":"Fixture plan prose for downstream implement and audit."
  }
  """.trimIndent()

private fun implementProducedOutputs(): String =
  """
  {
    "value":"Fixture implement prose for downstream audit."
  }
  """.trimIndent()

private fun implementFixProducedOutputs(): String =
  """
  {
    "repair_receipt": {
      "contract_version": "0.3",
      "entries": [{
        "finding_id": "F-001",
        "outcome": "addressed"
      }]
    },
    "reconciled_state": {"reconciled": true, "evidence": "Fixture tree at target state."}
  }
  """.trimIndent()

internal data class PhaseOutputFixture(
  val id: String,
  val phaseId: String,
  val producedOutputs: String,
)

internal val PLANNING_PROJECTION_FIXTURES: List<PhaseOutputFixture> = emptyList()

internal val PLANNING_PROJECTION_EXEMPT_PHASES: Set<String> =
  setOf(
    "preplan",
    "plan",
    "implement",
    "simplify",
    "review",
    "audit",
    "verify_findings",
    "implement_fix",
    "commit_push",
  )

internal object Skill187SyntheticAuditResponses {
  const val NESTED_VERDICT_SENTINEL: String = "SKILL187-NESTED-VERDICT"
  const val OBSERVATION_SENTINEL: String = "SKILL187-BAD-OBSERVATION"
  const val ARTIFACT_SENTINEL: String = "SKILL187-OVERSIZE-ARTIFACT"
  const val YAML_NESTED_SENTINEL: String = "SKILL187-YAML-NESTED"
  const val UNSUPPORTED_YAML_SENTINEL: String = "SKILL187-UNSUPPORTED-YAML"
  private const val AUDIT_VALUE_SATISFIED: String = """{\"gaps\":[],\"non_blocking_findings\":[]}"""

  fun nestedVerdictMissingDelimiter(): String =
    """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"$NESTED_VERDICT_SENTINEL",""" +
      """"produced_outputs":{"value":"$AUDIT_VALUE_SATISFIED","verdict":"satisfied"}"""

  fun nestedVerdictComplete(): String = nestedVerdictMissingDelimiter() + "}"

  fun nestedVerdictConservativeYaml(): String =
    "{contract_version: \"$VERSION\", phase_id: \"audit\", status: \"completed\", " +
      "summary: \"$YAML_NESTED_SENTINEL\", produced_outputs: {value: \"$AUDIT_VALUE_SATISFIED\", " +
      "verdict: \"satisfied\"}}"

  fun invalidCriterionShape(): String =
    """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"$OBSERVATION_SENTINEL",""" +
      """"verdict":"gaps_found","produced_outputs":{"value":"{\"gaps\":[{\"criterion\":\"AC-001\",""" +
      """\"note\":\"the behavior is absent\",\"severity\":\"blocker\"}]}"}}"""

  fun correctedSatisfied(): String =
    """{"contract_version":"$VERSION","phase_id":"audit","status":"completed","summary":"criteria met",""" +
      """"verdict":"satisfied","produced_outputs":{"value":"$AUDIT_VALUE_SATISFIED"}}"""

  fun unsupportedBlockYaml(): String =
    """
    contract_version: "$VERSION"
    phase_id: "audit"
    status: "completed"
    summary: "$UNSUPPORTED_YAML_SENTINEL"
    verdict: "satisfied"
    produced_outputs:
      value: "$AUDIT_VALUE_SATISFIED"
    """.trimIndent()
}
