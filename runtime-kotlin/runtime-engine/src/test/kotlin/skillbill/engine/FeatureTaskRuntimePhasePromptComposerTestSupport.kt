
package skillbill.engine

import skillbill.application.realPlanningProjectionValidator
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeImplementationContinuation
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLaunchBriefing
import skillbill.engine.featuretask.phase.briefing.FeatureTaskRuntimePhaseBriefingAssembler
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.handoff.FeatureTaskRuntimeHandoffContract
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeRepositoryCheckpoint
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeHandoffAssemblyRequest
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.repair.CorrectiveRepairCapturedResponse
import skillbill.workflow.taskruntime.model.repair.CorrectiveRepairDiagnosticLocator
import skillbill.workflow.taskruntime.model.repair.task.FeatureTaskRuntimeCorrectiveRepairContext
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val PROMPT_COMPOSER_ISSUE_KEY = "SKILL-66"
internal const val TEST_VALUE_DISCIPLINE_TITLE = "## Test-value discipline"
internal const val PROMPT_COMPOSER_SPEC_REFERENCE = ".feature-specs/SKILL-66/spec.md"

internal val PROMPT_COMPOSER_PREPLAN_OUTPUT =
  promptComposerProjectionEnvelope("preplan", PlanningProjectionFixtures.PREPLAN_DIGEST)
internal val PROMPT_COMPOSER_PLAN_OUTPUT =
  promptComposerProjectionEnvelope("plan", PlanningProjectionFixtures.PLAN_PROSE)

internal val promptComposerPhasePreplan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN
internal val promptComposerPhasePlan = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
internal val promptComposerImplementPhase = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT

internal fun promptComposerProjectionEnvelope(
  phaseId: String,
  producedOutputs: String,
): String =
  """{"contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION","phase_id":"$phaseId","status":"completed",""" +
    """"summary":"Phase produced a validated output.","produced_outputs":$producedOutputs}"""

internal fun composePromptForPhase(phaseId: String) =
  composePhasePrompt(
    PROMPT_COMPOSER_ISSUE_KEY,
    promptComposerBriefingFor(phaseId),
  )

internal fun promptComposerProjectionExampleCases() =
  listOf(
    Pair(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
      promptComposerBriefingFor(promptComposerPhasePreplan),
    ),
    Pair(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN, promptComposerBriefingFor(promptComposerPhasePlan)),
    Pair(promptComposerImplementPhase, promptComposerBriefingFor(promptComposerImplementPhase)),
  )

internal data class PromptComposerBriefingOptions(
  val featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
  val auditOutput: String = validJsonOutput("audit"),
  val acceptanceCriteria: List<String> = listOf("AC-1"),
)

internal fun promptComposerBriefingFor(
  phaseId: String,
  featureSize: FeatureTaskRuntimeFeatureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
): FeatureTaskRuntimePhaseLaunchBriefing =
  promptComposerBriefingFor(phaseId, PromptComposerBriefingOptions(featureSize = featureSize))

internal fun promptComposerBriefingFor(
  phaseId: String,
  options: PromptComposerBriefingOptions,
): FeatureTaskRuntimePhaseLaunchBriefing {
  val checkpoint = FeatureTaskRuntimeRepositoryCheckpoint(fingerprint = "fixture-checkpoint-1")
  val declaration =
    phaseDeclaration(phaseId, options.featureSize, setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD))
  return FeatureTaskRuntimePhaseBriefingAssembler.assemble(
    FeatureTaskRuntimeHandoffContract.assembleHandoff(
      FeatureTaskRuntimeHandoffAssemblyRequest(
        declaration = declaration,
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = PROMPT_COMPOSER_SPEC_REFERENCE,
            featureSize = options.featureSize,
            acceptanceCriteria = options.acceptanceCriteria,
            mandatesAndOverrides = emptyList(),
          ),
        recordedOutputs =
          listOf(
            FeatureTaskRuntimePhaseOutput("preplan", 1, PROMPT_COMPOSER_PREPLAN_OUTPUT),
            FeatureTaskRuntimePhaseOutput("plan", 1, PROMPT_COMPOSER_PLAN_OUTPUT),
            FeatureTaskRuntimePhaseOutput("implement", 1, IMPLEMENT_OUTPUT),
            FeatureTaskRuntimePhaseOutput("simplify", 1, SIMPLIFY_OUTPUT),
            FeatureTaskRuntimePhaseOutput("audit", 1, options.auditOutput),
            FeatureTaskRuntimePhaseOutput("review", 1, validJsonOutput("review")),
            verifyFindingsPhaseOutput(),
            FeatureTaskRuntimePhaseOutput("validate", 1, validJsonOutput("validate")),
            FeatureTaskRuntimePhaseOutput("write_history", 1, validJsonOutput("write_history")),
            FeatureTaskRuntimePhaseOutput("commit_push", 1, FINALISED_COMMIT_PUSH_OUTPUT),
          ),
        repositoryCheckpoint = checkpoint,
        expectedRepositoryCheckpoint = checkpoint,
        validationDepth = ValidationDepth.DEFAULT,
      ),
    ),
    planningProjectionValidator = realPlanningProjectionValidator,
  )
}

internal fun assertAuditPromptNamesSignal(
  auditPrompt: String,
  fragment: String,
  what: String,
) {
  assertContains(auditPrompt, fragment, false, "audit names $what")
}

internal fun assertSchemaCorrectionSuppressesContinuation(context: FeatureTaskRuntimeCorrectiveRepairContext) {
  val prompt =
    composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(
        implementationContinuation = promptComposerImplementationContinuation(),
        priorSchemaFailure = "produced_outputs must be an object.",
        correctiveRepairContext = context,
      )
    }
  assertContains(prompt, "Previous attempt was REJECTED by the schema gate")
  assertContains(prompt, "Untrusted prior phase output")
  assertTrue(prompt.contains("SKILL187-SHOULD-NOT-APPEAR"))
  assertFalse(prompt.contains("Continue this implementation"))
  assertFalse(prompt.contains("segment 2"))
}

internal fun assertTerminalAndContinuationRetriesOmitRepairContext() {
  val terminalOnly =
    composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(priorTerminalFailure = "blocked: waiting on operator")
    }
  assertFalse(terminalOnly.contains("Untrusted prior phase output"))
  assertFalse(terminalOnly.contains("SKILL187-SHOULD-NOT-APPEAR"))

  val continuationOnly =
    composePhasePrompt(
      PROMPT_COMPOSER_ISSUE_KEY,
      promptComposerBriefingFor("implement"),
    ) {
      copy(implementationContinuation = promptComposerImplementationContinuation())
    }
  assertFalse(continuationOnly.contains("Untrusted prior phase output"))
  assertFalse(continuationOnly.contains("SKILL187-SHOULD-NOT-APPEAR"))
}

internal fun promptComposerImplementationContinuation() =
  FeatureTaskRuntimeImplementationContinuation(
    phaseId = "implement",
    segmentNumber = 2,
    priorValueSegments = listOf("segment one prose"),
    latestPrompt = "optional directive",
    failureDisposition = null,
  )

internal fun shippedPlatformPackSlugs(): List<String> {
  val packs = locateAncestorDirectory("platform-packs")
  return Files.list(packs).use { stream ->
    stream
      .filter { path -> Files.isDirectory(path) && Files.isRegularFile(path.resolve("platform.yaml")) }
      .map { path -> path.fileName.toString() }
      .sorted()
      .toList()
  }
}

internal fun locateAncestorDirectory(name: String): Path {
  var dir: Path? = Path.of("").toAbsolutePath().normalize()
  while (dir != null) {
    val candidate = dir.resolve(name)
    if (Files.isDirectory(candidate)) {
      return candidate
    }
    dir = dir.parent
  }
  error("test working directory has no ancestor named $name")
}

internal fun promptComposerCorrectiveContext(body: String): FeatureTaskRuntimeCorrectiveRepairContext =
  FeatureTaskRuntimeCorrectiveRepairContext(
    phaseId = "audit",
    attempt = 1,
    rejectionRule = "phase-output-schema",
    rejectionPath = "\$.verdict",
    payloadFreeConstraint = "verdict: must be a top-level string",
    diagnosticLocator = CorrectiveRepairDiagnosticLocator("opaque-diagnostic-composer"),
    captured = CorrectiveRepairCapturedResponse.classify(body, alreadyTruncated = false),
  )
