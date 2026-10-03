
package skillbill.engine.featuretask.phase.briefing

import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.runner.AUDIT_GAP_MESSAGE
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimeHandoffProjectionError
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseDeclaration
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeSharedReviewEvidenceReference
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FeatureTaskRuntimePlanningProjectionEdgeTest {
  @Test
  fun `preplan to plan delivers only prose fields and omits absent prompt cleanly`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phasePreplan,
          consumer = phasePlan,
          prose = "Dense preplan prose for downstream plan.",
          options = ProseEdgeOptions(includePrompt = false),
        ),
      expectedInBriefing = listOf("Dense preplan prose for downstream plan."),
      mustNotContain = listOf("optional directive", "complete_envelope_secret"),
    )
  }

  @Test
  fun `plan to implement delivers only plan prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phasePlan,
          consumer = phaseImplement,
          prose = "Dense plan prose for downstream implement.",
          options = ProseEdgeOptions(undeclaredFields = true),
        ),
      expectedInBriefing = listOf("Dense plan prose for downstream implement."),
      mustNotContain = listOf("complete_plan_envelope_secret"),
    )
  }

  @Test
  fun `implement to audit delivers implement prose and optional directive`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = phaseAudit,
          prose = "Dense implement prose for downstream audit.",
          options = ProseEdgeOptions(includePrompt = true),
        ),
      expectedInBriefing = listOf("Dense implement prose for downstream audit.", "optional directive"),
      mustNotContain = listOf("complete_implement_envelope_secret"),
    )
  }

  @Test
  fun `audit to implement delivers audit prose and optional directive`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseAudit,
          consumer = phaseImplement,
          prose = AUDIT_GAP_MESSAGE,
          options = ProseEdgeOptions(includePrompt = true),
        ),
      expectedInBriefing = listOf(AUDIT_GAP_MESSAGE, "optional directive"),
      mustNotContain = listOf("complete_audit_envelope_secret"),
    )
  }

  @Test
  fun `stuffed JSON in value advances the implement to audit edge`() {
    val stuffed =
      """{"projection_kind":"implementation_receipt","completed_task_ids":["task-01"],""" +
        """"changed_paths":["runtime-domain/model/X.kt"]}"""
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = phaseAudit,
          prose = stuffed,
        ),
      expectedInBriefing = listOf("task-01", "runtime-domain/model/X.kt"),
      mustNotContain = emptyList(),
    )
  }

  @Test
  fun `legacy keys beside value advance the handoff`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = phaseAudit,
          prose = "Implement prose with legacy keys beside value.",
          options = ProseEdgeOptions(legacyKeys = true),
        ),
      expectedInBriefing = listOf("Implement prose with legacy keys beside value."),
      mustNotContain = listOf("complete_implement_envelope_secret"),
    )
  }

  @Test
  fun `malformed partial and non-json non-blank value strings advance`() {
    listOf(
      "not-json but still non-blank prose",
      "{partial json without closing",
      "   leading whitespace still counts   ",
    ).forEach { prose ->
      assertProseHandoffAdvances(
        edge =
          proseEdge(
            producer = phaseImplement,
            consumer = phaseAudit,
            prose = prose,
          ),
        expectedInBriefing = listOf(prose.trim()),
        mustNotContain = emptyList(),
      )
    }
  }

  @Test
  fun `blank value blocks the prose handoff`() {
    val edge =
      proseEdge(
        producer = phasePreplan,
        consumer = phasePlan,
        prose = "   ",
      )
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        assemble(
          BriefingAssembleFixture(
            edge.consumer,
            listOf(edge.declaration),
            listOf(phaseOutput(edge.producer, edge.payload)),
          ),
        )
      }
    assertContains(error.message.orEmpty(), "non-blank prose")
  }

  @Test
  fun `missing value blocks the prose handoff`() {
    val edge =
      proseEdge(
        producer = phasePreplan,
        consumer = phasePlan,
        prose = "",
        options = ProseEdgeOptions(omitValue = true),
      )
    val error =
      assertFailsWith<InvalidFeatureTaskRuntimeHandoffProjectionError> {
        assemble(
          BriefingAssembleFixture(
            edge.consumer,
            listOf(edge.declaration),
            listOf(phaseOutput(edge.producer, edge.payload)),
          ),
        )
      }
    assertContains(error.message.orEmpty(), "non-blank prose")
  }

  @Test
  fun `diff and current_unit_of_work name the delivered shared evidence projection instead of self-read`() {
    val evidence =
      FeatureTaskRuntimeSharedReviewEvidenceReference(
        storePath = ".skill-bill/run-evidence/wf/fp",
        checkpointFingerprint = "fp",
        baseRef = "base",
        headRef = "head",
        changedFileCount = 1,
        changedHunkCount = 1,
        fileHunkIndexDigest = "a".repeat(64),
      )
    val projectionName = FeatureTaskRuntimePhaseWorkflowDefinition.SHARED_REVIEW_EVIDENCE_PROJECTION_NAME

    val reviewBriefing =
      assemble(
        BriefingAssembleFixture(
          consumer = phaseReview,
          declarations =
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.sharedReviewEvidenceDeclaration(phaseReview),
            ),
          recordedOutputs = emptyList(),
          derivedContextKeys = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF),
          sharedReviewEvidence = evidence,
        ),
      )
    assertContains(reviewBriefing.briefingText, "- diff: the branch diff is already derived for you")
    assertContains(reviewBriefing.briefingText, "'$projectionName' projection")
    assertFalse(reviewBriefing.briefingText.contains("read the branch diff yourself"))

    val unitBriefing =
      assemble(
        BriefingAssembleFixture(
          consumer = phaseReview,
          declarations =
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.sharedReviewEvidenceDeclaration(phaseReview),
            ),
          recordedOutputs = emptyList(),
          derivedContextKeys = listOf("current_unit_of_work"),
          sharedReviewEvidence = evidence,
        ),
      )
    assertContains(unitBriefing.briefingText, "- current_unit_of_work: the current unit of work is already derived")
    assertContains(unitBriefing.briefingText, "'$projectionName' projection")
    assertFalse(unitBriefing.briefingText.contains("read the current unit of work yourself"))
  }

  @Test
  fun `omitted shared evidence falls back to self-read rather than naming a missing projection`() {
    val reviewBriefing =
      assemble(
        BriefingAssembleFixture(
          consumer = phaseReview,
          declarations =
            listOf(
              FeatureTaskRuntimePhaseWorkflowDefinition.sharedReviewEvidenceDeclaration(phaseReview),
            ),
          recordedOutputs = emptyList(),
          derivedContextKeys = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_DIFF),
          sharedReviewEvidence = null,
        ),
      )
    assertContains(
      reviewBriefing.briefingText,
      "- diff: read the branch diff yourself; it is not delivered in this briefing",
    )
    assertFalse(reviewBriefing.briefingText.contains("already derived for you"))
    assertFalse(reviewBriefing.briefingText.contains("### shared_review_evidence"))
  }

  @Test
  fun `plan to validate delivers only plan prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phasePlan,
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
          prose = "Dense plan prose for downstream validate.",
        ),
      expectedInBriefing = listOf("Dense plan prose for downstream validate."),
      mustNotContain = listOf("complete_plan_envelope_secret"),
    )
  }

  @Test
  fun `plan to build delivers only plan prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phasePlan,
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
          prose = "Dense plan prose for downstream build.",
        ),
      expectedInBriefing = listOf("Dense plan prose for downstream build."),
      mustNotContain = listOf("complete_plan_envelope_secret"),
    )
  }

  @Test
  fun `implement to write_history delivers only implement prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
          prose = "Dense implement prose for downstream write_history.",
        ),
      expectedInBriefing = listOf("Dense implement prose for downstream write_history."),
      mustNotContain = listOf("complete_implement_envelope_secret"),
    )
  }

  @Test
  fun `implement to commit_push delivers only implement prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
          prose = "Dense implement prose for downstream commit_push.",
        ),
      expectedInBriefing = listOf("Dense implement prose for downstream commit_push."),
      mustNotContain = listOf("complete_implement_envelope_secret"),
    )
  }

  @Test
  fun `implement to pr delivers only implement prose fields`() {
    assertProseHandoffAdvances(
      edge =
        proseEdge(
          producer = phaseImplement,
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
          prose = "Dense implement prose for downstream pr.",
        ),
      expectedInBriefing = listOf("Dense implement prose for downstream pr."),
      mustNotContain = listOf("complete_implement_envelope_secret"),
    )
  }

  @Test
  fun `pr keeps the self-read branch-diff instruction on its own derived-context key`() {
    val briefing =
      assemble(
        BriefingAssembleFixture(
          consumer = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
          declarations = emptyList(),
          recordedOutputs = emptyList(),
          derivedContextKeys = listOf(FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF),
        ),
      )

    assertContains(
      briefing.briefingText,
      "- ${FeatureTaskRuntimePhaseWorkflowDefinition.DERIVED_CONTEXT_PR_BRANCH_DIFF}: " +
        "read the branch diff yourself; it is not delivered in this briefing",
    )
    assertFalse(briefing.briefingText.contains("shared_review_evidence"))
  }

  private data class ProseHandoffEdge(
    val producer: String,
    val consumer: String,
    val declaration: PhaseHandoffProjectionDeclaration,
    val payload: String,
  )

  private data class ProseEdgeOptions(
    val includePrompt: Boolean = true,
    val undeclaredFields: Boolean = false,
    val legacyKeys: Boolean = false,
    val omitValue: Boolean = false,
  )

  private fun proseEdge(
    producer: String,
    consumer: String,
    prose: String,
    options: ProseEdgeOptions = ProseEdgeOptions(),
  ): ProseHandoffEdge {
    val declaration =
      when (consumer) {
        phasePlan -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(phasePlan)
        phaseImplement ->
          when (producer) {
            phasePlan -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(phaseImplement, phasePlan)
            phaseAudit ->
              FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(
                phaseImplement,
                phaseAudit,
                checkpointPolicy = FeatureTaskRuntimeRepositoryCheckpointPolicy.REFRESH_FROM_REPOSITORY,
              )
            else -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(phaseImplement, producer)
          }
        phaseAudit -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(phaseAudit, phaseImplement)
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD,
        -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(consumer, phasePlan)
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR,
        -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(consumer, phaseImplement)
        else -> FeatureTaskRuntimePhaseWorkflowDefinition.phaseProseDeclaration(consumer, producer)
      }
    return ProseHandoffEdge(
      producer = producer,
      consumer = consumer,
      declaration = declaration,
      payload =
        prosePayload(
          prose = prose,
          includePrompt = options.includePrompt,
          undeclaredFields = options.undeclaredFields,
          legacyKeys = options.legacyKeys,
          omitValue = options.omitValue,
        ),
    )
  }

  private fun assertProseHandoffAdvances(
    edge: ProseHandoffEdge,
    expectedInBriefing: List<String>,
    mustNotContain: List<String>,
  ) {
    val briefing =
      assemble(
        BriefingAssembleFixture(
          consumer = edge.consumer,
          declarations = listOf(edge.declaration),
          recordedOutputs = listOf(phaseOutput(edge.producer, edge.payload)),
        ),
      )
    expectedInBriefing.forEach { expected ->
      assertContains(briefing.briefingText, expected)
    }
    mustNotContain.forEach { forbidden ->
      assertFalse(briefing.briefingText.contains(forbidden), "briefing must not contain '$forbidden'")
    }
  }

  private fun prosePayload(
    prose: String,
    includePrompt: Boolean = true,
    undeclaredFields: Boolean = false,
    legacyKeys: Boolean = false,
    omitValue: Boolean = false,
  ): String {
    val produced = linkedMapOf<String, Any?>()
    if (!omitValue) produced["value"] = prose
    if (includePrompt) produced["prompt"] = "optional directive"
    when {
      undeclaredFields && legacyKeys -> {
        produced["complete_envelope_secret"] = "MUST NOT SURVIVE"
        produced["complete_implement_envelope_secret"] = "MUST NOT SURVIVE"
      }
      undeclaredFields -> {
        produced["complete_envelope_secret"] = "MUST NOT SURVIVE"
        produced["progress_diagnostics"] = "MUST NOT SURVIVE"
      }
      legacyKeys -> {
        produced["complete_implement_envelope_secret"] = "MUST NOT SURVIVE"
        produced["changed_paths"] = listOf("runtime-domain/model/X.kt")
      }
    }
    return JsonCodec.mapToJsonString(mapOf("produced_outputs" to produced))
  }

  private data class BriefingAssembleFixture(
    val consumer: String,
    val declarations: List<PhaseHandoffProjectionDeclaration>,
    val recordedOutputs: List<FeatureTaskRuntimePhaseOutput>,
    val derivedContextKeys: List<String> = emptyList(),
    val sharedReviewEvidence: FeatureTaskRuntimeSharedReviewEvidenceReference? = null,
    val checkpoint: FeatureTaskRuntimeRepositoryCheckpoint =
      FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1"),
  )

  private fun assemble(fixture: BriefingAssembleFixture) =
    FeatureTaskRuntimePhaseBriefingAssembler.assemble(
      FeatureTaskRuntimeHandoffContract.assembleHandoff(
        FeatureTaskRuntimeHandoffAssemblyRequest(
          declaration =
            FeatureTaskRuntimePhaseDeclaration(
              fixture.consumer,
              fixture.declarations,
              fixture.derivedContextKeys,
            ),
          runInvariants = runInvariants(),
          recordedOutputs = fixture.recordedOutputs,
          repositoryCheckpoint = fixture.checkpoint,
        ),
      ),
      scope = FeatureTaskRuntimeBriefingScope(sharedReviewEvidence = fixture.sharedReviewEvidence),
    )

  private fun phaseOutput(
    phaseId: String,
    payload: String,
  ) = FeatureTaskRuntimePhaseOutput(
    phaseId = phaseId,
    iteration = 1,
    payload = payload,
    normalizedOutput =
      NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
        FeatureTaskRuntimeWorkflowArtifactMap.from(
          JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(payload))),
        ),
      ),
  )

  private fun runInvariants() =
    FeatureTaskRuntimeRunInvariants(
      specReference = "spec.md",
      acceptanceCriteria = listOf("AC-001"),
      mandatesAndOverrides = emptyList(),
    )

  private val phasePreplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
  private val phasePlan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
  private val phaseImplement = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT
  private val phaseAudit = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
  private val phaseReview = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW
}
