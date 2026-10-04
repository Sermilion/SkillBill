package skillbill.workflow.taskruntime.handoff

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpointPolicy
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDeclaration
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionDelivery
import skillbill.workflow.taskruntime.model.handoff.PhaseHandoffProjectionShape
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeHandoffProjectionInputs
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimeResolvedUpstreamOutputs
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeCompactReferenceKind
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffProjectionBudget
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffPromptVisibility
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffSourceRef
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowQueries
import kotlin.test.assertTrue

internal const val HANDOFF_VALIDATOR_TEST_CONSUMER: String = "implement"
internal const val HANDOFF_VALIDATOR_TEST_PRODUCER: String = "plan"
internal const val HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD: String =
  """{"produced_outputs":{"value":"finished","validation_result":{"validation_status":"passed","checks":[],""" +
    """"repository_checkpoint":{"fingerprint":"tree-1"},"gate_run_count":1,"gate_runs":[],""" +
    """"validation_evidence":{"contract_version":"0.1","results":[{"command":"./gradlew check","exit_code":0}]}}}}"""
internal const val HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD: String =
  """{"produced_outputs":{"value":"Recorded the boundary history entry.",""" +
    """"runtime_measured_facts":{"changed_paths":["agent/history.md"],"history_written":true,""" +
    """"decisions_recorded":false}}}"""

internal data class HandoffProjectionDeclarationFixture(
  var consumerPhaseId: String = HANDOFF_VALIDATOR_TEST_CONSUMER,
  var sourceRef: FeatureTaskRuntimeHandoffSourceRef =
    FeatureTaskRuntimeHandoffSourceRef.UpstreamPhaseOutput(HANDOFF_VALIDATOR_TEST_PRODUCER),
  var projectionName: String = "plan_receipt",
  var projectionContractId: String = "test.upstream_phase_receipt",
  var contractVersion: String = "0.1",
  var promptVisibility: FeatureTaskRuntimeHandoffPromptVisibility =
    FeatureTaskRuntimeHandoffPromptVisibility.PROMPT_VISIBLE,
  var budget: FeatureTaskRuntimeHandoffProjectionBudget = FeatureTaskRuntimeHandoffProjectionBudget.PHASE_RECEIPT,
  var declaredFieldNames: List<String> =
    listOf(FeatureTaskRuntimeHandoffProjectionValidator.PHASE_OUTPUT_RECEIPT_FIELD),
  var checkpointPolicy: FeatureTaskRuntimeRepositoryCheckpointPolicy =
    FeatureTaskRuntimeRepositoryCheckpointPolicy.NOT_REQUIRED,
  var required: Boolean = true,
  var allowsPrivateArtifactReference: Boolean = false,
  var inlineAlternative: FeatureTaskRuntimeCompactReferenceKind? = null,
) {
  fun build(): PhaseHandoffProjectionDeclaration =
    PhaseHandoffProjectionDeclaration(
      consumerPhaseId = consumerPhaseId,
      sourceRef = sourceRef,
      shape =
        PhaseHandoffProjectionShape(
          projectionName = projectionName,
          projectionContractId = projectionContractId,
          projectionContractVersion = contractVersion,
          promptVisibility = promptVisibility,
          budget = budget,
          declaredFieldNames = declaredFieldNames,
        ),
      delivery =
        PhaseHandoffProjectionDelivery(
          checkpointPolicy = checkpointPolicy,
          required = required,
          allowsPrivateArtifactReference = allowsPrivateArtifactReference,
          inlineAlternative = inlineAlternative,
          authorizedReferenceKinds = listOfNotNull(inlineAlternative).toSet(),
        ),
    )
}

internal data class HandoffProjectionValidatorInputsFixture(
  var consumerPhaseId: String = HANDOFF_VALIDATOR_TEST_CONSUMER,
  var declarations: List<PhaseHandoffProjectionDeclaration> = listOf(handoffProjectionDeclaration()),
  var resolvedUpstream: FeatureTaskRuntimeResolvedUpstreamOutputs = handoffProjectionUpstream(),
  var runInvariants: FeatureTaskRuntimeRunInvariants = handoffProjectionRunInvariants(),
  var resolvedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  var expectedCheckpoint: FeatureTaskRuntimeRepositoryCheckpoint? = null,
  var validationDepth: ValidationDepth = ValidationDepth.DEFAULT,
  var unselectedStepIds: Set<String> = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD),
) {
  fun build(): FeatureTaskRuntimeHandoffProjectionInputs =
    FeatureTaskRuntimeHandoffProjectionInputs(
      consumerPhaseId = consumerPhaseId,
      declarations = declarations,
      resolvedUpstream = resolvedUpstream,
      runInvariants = runInvariants,
      resolvedCheckpoint = resolvedCheckpoint,
      expectedCheckpoint = expectedCheckpoint,
      workflowId = "wftr-1",
      validationDepth = validationDepth,
      unselectedStepIds = unselectedStepIds,
    )
}

internal inline fun handoffProjectionValidatorInputs(
  block: HandoffProjectionValidatorInputsFixture.() -> Unit = {},
): FeatureTaskRuntimeHandoffProjectionInputs = HandoffProjectionValidatorInputsFixture().apply(block).build()

internal fun handoffProjectionValidatorInputs(
  fixture: HandoffProjectionValidatorInputsFixture,
): FeatureTaskRuntimeHandoffProjectionInputs = fixture.build()

internal inline fun handoffProjectionDeclaration(
  block: HandoffProjectionDeclarationFixture.() -> Unit = {},
): PhaseHandoffProjectionDeclaration = HandoffProjectionDeclarationFixture().apply(block).build()

internal fun handoffProjectionDeclaration(
  fixture: HandoffProjectionDeclarationFixture,
): PhaseHandoffProjectionDeclaration = fixture.build()

internal fun handoffProjectionUpstream(
  payload: String = """{"plan":"ok"}""",
): FeatureTaskRuntimeResolvedUpstreamOutputs =
  FeatureTaskRuntimeResolvedUpstreamOutputs(
    mapOf(
      HANDOFF_VALIDATOR_TEST_PRODUCER to
        FeatureTaskRuntimePhaseOutput(
          phaseId = HANDOFF_VALIDATOR_TEST_PRODUCER,
          iteration = 1,
          payload = payload,
        ),
    ),
  )

internal fun handoffProjectionRunInvariants(acceptanceCriteria: List<String> = listOf("AC-1")) =
  FeatureTaskRuntimeRunInvariants(
    specReference = ".feature-specs/SKILL-137/spec.md",
    acceptanceCriteria = acceptanceCriteria,
    mandatesAndOverrides = emptyList(),
  )

internal fun proseOutput(
  phaseId: String,
  value: String,
  prompt: String? = null,
): FeatureTaskRuntimePhaseOutput = FeatureTaskRuntimePhaseOutput(phaseId, 1, PhaseOutput(value, prompt))

internal fun recordedPhaseOutput(
  phaseId: String,
  recordJson: String,
): FeatureTaskRuntimePhaseOutput =
  FeatureTaskRuntimePhaseOutput(
    phaseId,
    1,
    recordJson,
    NormalizedFeatureTaskRuntimePhaseOutput.fromRecordMap(
      FeatureTaskRuntimeWorkflowArtifactMap.from(
        JsonCodec.jsonElementToValue(requireNotNull(JsonCodec.parseObjectOrNull(recordJson))),
      ),
    ),
  )

internal fun valueOnlyFinalizationUpstream(): FeatureTaskRuntimeResolvedUpstreamOutputs {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val planProse = """{"projection_kind":"executable_plan","tasks":[]}"""
  val implementProse = """{"projection_kind":"implementation_receipt","completed_task_ids":["task-1"]}"""
  return FeatureTaskRuntimeResolvedUpstreamOutputs(
    mapOf(
      def.PHASE_PLAN to proseOutput(def.PHASE_PLAN, planProse),
      def.PHASE_IMPLEMENT to proseOutput(def.PHASE_IMPLEMENT, implementProse),
      def.PHASE_AUDIT to proseOutput(def.PHASE_AUDIT, "audit satisfied"),
      def.PHASE_VALIDATE to recordedPhaseOutput(def.PHASE_VALIDATE, HANDOFF_VALIDATOR_VALIDATION_PHASE_PAYLOAD),
      def.PHASE_WRITE_HISTORY to recordedPhaseOutput(def.PHASE_WRITE_HISTORY, HANDOFF_VALIDATOR_HISTORY_PHASE_PAYLOAD),
      def.PHASE_COMMIT_PUSH to proseOutput(def.PHASE_COMMIT_PUSH, "pushed feat at abc"),
    ),
  )
}

internal fun assertValueOnlyConsumerLaunches(
  consumer: String,
  upstream: FeatureTaskRuntimeResolvedUpstreamOutputs,
  checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val declarations =
    if (consumer == def.PHASE_VALIDATE || consumer == def.PHASE_BUILD) {
      FeatureTaskRuntimePhaseWorkflowQueries
        .phaseDeclaration(consumer, FeatureTaskRuntimeFeatureSize.MEDIUM)
        .projectionDeclarations
    } else {
      FeatureTaskRuntimePhaseWorkflowQueries.phaseDeclarationWithoutSteps(
        consumer,
        FeatureTaskRuntimeFeatureSize.MEDIUM,
        setOf(def.PHASE_BUILD),
      ).projectionDeclarations
    }
  val envelope =
    FeatureTaskRuntimeHandoffProjectionValidator.validate(
      handoffProjectionValidatorInputs(
        HandoffProjectionValidatorInputsFixture(
          consumerPhaseId = consumer,
          declarations = declarations,
          resolvedUpstream = upstream,
          resolvedCheckpoint = checkpoint,
        ),
      ),
    )
  assertTrue(envelope.projections.isNotEmpty(), "$consumer must launch with value-only upstream outputs")
}

internal fun assertPrRequestOmitsStuffedImplementFields(
  implement: FeatureTaskRuntimePhaseOutput,
  validate: FeatureTaskRuntimePhaseOutput,
  commitPush: FeatureTaskRuntimePhaseOutput,
  checkpoint: FeatureTaskRuntimeRepositoryCheckpoint,
) {
  val def = FeatureTaskRuntimePhaseWorkflowDefinition
  val prEnvelope =
    FeatureTaskRuntimeHandoffProjectionValidator.validate(
      handoffProjectionValidatorInputs(
        HandoffProjectionValidatorInputsFixture(
          consumerPhaseId = def.PHASE_PR,
          declarations =
            FeatureTaskRuntimePhaseWorkflowQueries
              .phaseDeclaration(def.PHASE_PR, FeatureTaskRuntimeFeatureSize.MEDIUM)
              .projectionDeclarations,
          resolvedUpstream =
            FeatureTaskRuntimeResolvedUpstreamOutputs(
              mapOf(
                def.PHASE_IMPLEMENT to implement,
                def.PHASE_VALIDATE to validate,
                def.PHASE_COMMIT_PUSH to commitPush,
              ),
            ),
          resolvedCheckpoint = checkpoint,
        ),
      ),
    )
  val prRequest = prEnvelope.projections.single { it.projectionName == "pr_request" }
  val stuffedFieldNames = setOf("completed_task_ids", "tests_added", "tests_updated", "deviations")
  assertTrue(prRequest.fields.none { it.name in stuffedFieldNames })
}
