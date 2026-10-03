
package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.featuretask.slot.verifyFindingsPhaseOutput
import skillbill.engine.featuretask.slot.withRawPayload
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class FeatureTaskRuntimeRemediationPassPromptTest {
  @Test
  fun `pass two ceremony directive orders the remediation delta, not the complete immutable-base delta`() {
    val prompt = implementFixPrompt()

    assertContains(prompt, "Address every finding verify_findings carried")
    assertContains(prompt, "Every carried finding — Blocker, Major, Minor, and Nit — is in scope")

    assertContains(prompt, "a finding verification refuted is not carried at all")
    assertFalse(prompt.contains("Address every verified finding"))
    assertFalse(
      prompt.contains("to the subtask's complete delta from its immutable base"),
      "implement_fix must not order the complete immutable-base delta.",
    )
  }

  @Test
  fun `pass two reserved remediation section preserves prohibitions and Blocker-or-Major survival`() {
    val prompt = implementFixPrompt()

    assertContains(prompt, "Every carried finding — Blocker, Major, Minor, and Nit — is in scope")
    assertContains(prompt, "Do not re-apply the plan from scratch")
    assertFalse(prompt.contains("## Last-commit review scope"))
    assertFalse(prompt.contains("immediately preceding pass's Blocker findings"))
  }

  @Test
  fun `pass two omits the last-commit materialized scope block and baseline-untracked policy`() {
    val prompt = implementFixPrompt()

    assertFalse(
      prompt.contains("## Last-commit review scope"),
      "The last-commit scope block is pass one's authority only.",
    )
    assertFalse(
      prompt.contains("## Baseline-untracked review policy"),
      "Baseline-untracked policy is pass one's authority only.",
    )
    assertContains(prompt, "Phase: implement_fix")
  }

  @Test
  fun `pass one keeps the last-commit materialized scope block`() {
    val prompt =
      composeReview(
        passNumber = 1,
        resolvedTier = CodeReviewExecutionMode.INLINE,
        reviewInput = REVIEW_INPUT,
      )

    assertContains(prompt, "## Last-commit review scope")
  }

  @Test
  fun `pass one keeps baseline-untracked policy when inventory is present`() {
    val prompt =
      composeReview(
        passNumber = 1,
        resolvedTier = CodeReviewExecutionMode.INLINE,
        baselineUntrackedPaths = listOf("preexisting.tmp"),
      )

    assertContains(prompt, "## Baseline-untracked review policy")
    assertContains(prompt, "preexisting.tmp")
  }

  @Test
  fun `the remediation pass always renders mode inline`() {
    val prompt = implementFixPrompt()

    assertContains(prompt, "Phase: implement_fix")
    assertFalse(
      prompt.contains("mode:delegated context:feature-remediation"),
      "implement_fix is runtime-owned reconciliation, not a delegated remediation review.",
    )
  }

  @Test
  fun `implement_fix asks for a prose report naming each carried finding`() {
    val prompt = implementFixPrompt()

    assertContains(prompt, "Name every carried finding by its finding id")
    assertFalse(
      prompt.contains("blocker_dispositions"),
      "implement_fix emits repair receipts; review dispositions stay on verify_findings.",
    )
  }

  @Test
  fun `pass one never orders blocker dispositions`() {
    val prompt =
      composeReview(
        passNumber = 1,
        resolvedTier = CodeReviewExecutionMode.INLINE,
      )

    assertFalse(prompt.contains("blocker_dispositions"), "Pass one has no prior pass to dispose.")
  }

  @Test
  fun `worked example pass two scopes only the four remediation-touched files plus all addressed findings`() {
    val touched =
      listOf(
        "src/TouchedOne.kt",
        "src/TouchedTwo.kt",
        "src/TouchedThree.kt",
        "src/TouchedFour.kt",
      )
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
              FeatureTaskRuntimeFeatureSize.MEDIUM,
            ),
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = ".feature-specs/SKILL-142/spec.md",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("AC-008", "AC-010"),
              mandatesAndOverrides = emptyList(),
            ),
          recordedOutputs =
            listOf(
              FeatureTaskRuntimePhaseOutput(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
                1,
                """{"produced_outputs":{"findings":[""" +
                  """{"finding_id":"F-001","severity":"Blocker","location":"${
                    touched.first()
                  }:1","message":"must fix"}]}}""",
              ),
              verifyFindingsPhaseOutput(listOf("F-001")).withRawPayload(
                payload = "F-001 stands: must fix at ${touched.first()}:1.",
              ),
            ),
          repositoryCheckpoint = checkpoint,
          expectedRepositoryCheckpoint = checkpoint,
        ),
      )
    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val prompt =
      composePhasePrompt(
        issueKey = "SKILL-142",
        briefing = briefing,
      )

    assertContains(prompt, touched.first())
    assertContains(prompt, "must fix")
    assertContains(prompt, "F-001")
    assertFalse(prompt.contains("immediately preceding pass's Blocker findings"))
  }

  @Test
  fun `implement_fix briefing includes a Minor finding from the preceding pass without severity re-filter`() {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
              FeatureTaskRuntimeFeatureSize.MEDIUM,
            ),
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = ".feature-specs/SKILL-178/spec.md",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("AC-005"),
              mandatesAndOverrides = emptyList(),
            ),
          recordedOutputs =
            listOf(
              FeatureTaskRuntimePhaseOutput(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
                1,
                """{"produced_outputs":{"findings":[""" +
                  """{"finding_id":"F-BLOCKER","severity":"Blocker","location":"A.kt:1","message":"must fix"},""" +
                  """{"finding_id":"F-MINOR","severity":"Minor","location":"B.kt:2","message":"polish naming"}]}}""",
              ),
              verifyFindingsPhaseOutput(listOf("F-BLOCKER", "F-MINOR")).withRawPayload(
                payload =
                  """
                  {"produced_outputs":{"finding_dispositions":[
                    {"finding_id":"F-BLOCKER","disposition":"verified","reason":"Matches spec intent.","severity":"blocker",
                      "location":"A.kt:1","message":"must fix"},
                    {"finding_id":"F-MINOR","disposition":"verified","reason":"Matches spec intent.","severity":"minor",
                      "location":"B.kt:2","message":"polish naming"}
                  ]}}
                  """.trimIndent(),
              ),
            ),
          repositoryCheckpoint = checkpoint,
          expectedRepositoryCheckpoint = checkpoint,
        ),
      )
    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    val prompt =
      composePhasePrompt(
        issueKey = "SKILL-178",
        briefing = briefing,
      )

    assertContains(prompt, "F-MINOR")
    assertContains(prompt, "minor")
    assertContains(prompt, "polish naming")
    assertContains(prompt, "Every carried finding — Blocker, Major, Minor, and Nit — is in scope")
    assertFalse(prompt.contains("Major, Minor, and Nit findings, specialist narratives"))
  }

  @Test
  fun `review ceremony orders every severity into produced_outputs findings without Blocker-only filter`() {
    val prompt = composeReview(passNumber = 1, resolvedTier = CodeReviewExecutionMode.INLINE)

    assertContains(prompt, "Fix every Blocker and Major")
    assertFalse(prompt.contains("Do not severity-filter the findings array"))
    assertFalse(
      prompt.contains("only unresolved actionable Blocker findings"),
      "PHASE_REVIEW must not order a Blocker-only findings array; AC-005 needs the full producer set.",
    )
  }

  private fun implementFixPrompt(): String {
    val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
    val handoff =
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
              FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
              FeatureTaskRuntimeFeatureSize.MEDIUM,
            ),
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = ".feature-specs/SKILL-142/spec.md",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("AC-008", "AC-010"),
              mandatesAndOverrides = emptyList(),
            ),
          recordedOutputs =
            listOf(
              FeatureTaskRuntimePhaseOutput(
                FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
                1,
                """{"produced_outputs":{"findings":[{"finding_id":"F-001","severity":"Blocker",""" +
                  """"location":"A.kt:1","message":"must fix"}]}}""",
              ),
              verifyFindingsPhaseOutput(listOf("F-001")),
            ),
          repositoryCheckpoint = checkpoint,
          expectedRepositoryCheckpoint = checkpoint,
        ),
      )
    val briefing =
      FeatureTaskRuntimePhaseBriefingAssembler.assemble(handoff)
    return composePhasePrompt(
      issueKey = "SKILL-142",
      briefing = briefing,
    )
  }

  private fun composeReview(
    passNumber: Int,
    resolvedTier: CodeReviewExecutionMode,
    reviewInput: GoalSubtaskReviewInput? = null,
    baselineUntrackedPaths: List<String> = emptyList(),
  ): String =
    composePhasePrompt(
      issueKey = "SKILL-142",
      briefing = reviewBriefing(),
    ) {
      copy(
        codeReviewMode = resolvedTier,
        reviewPassNumber = passNumber,
        goalSubtaskReviewInput = reviewInput,
        resolvedReviewTier = resolvedTier,
        reviewDecidingRule = "auto_mode_by_pass_number:pass_n_inline",
        baselineUntrackedPaths = baselineUntrackedPaths,
      )
    }
}

private val REVIEW_INPUT =
  GoalSubtaskReviewInput(
    reviewBaseSha = "a".repeat(40),
    currentHeadSha = "b".repeat(40),
    trackedDelta = "diff --git a/A.kt b/A.kt",
    ownedUntrackedPatches = "",
  )

private fun reviewBriefing() =
  FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration =
          FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclaration(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
            FeatureTaskRuntimeFeatureSize.MEDIUM,
          ),
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = ".feature-specs/SKILL-142/spec.md",
            featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
            acceptanceCriteria = listOf("AC-008", "AC-010"),
            mandatesAndOverrides = emptyList(),
          ),
        recordedOutputs =
          listOf(
            FeatureTaskRuntimePhaseOutput("audit", 1, validJsonOutput("audit")),
          ),
        repositoryCheckpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1"),
      ),
    ),
  )
