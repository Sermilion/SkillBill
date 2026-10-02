package skillbill.engine.featuretask.validation

import skillbill.config.model.RepoLocalConfig
import skillbill.config.model.ValidationGateRepoConfig
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairLauncher
import skillbill.engine.featuretask.validation.model.ValidationGateAgentRepairResult
import skillbill.engine.featuretask.validation.model.ValidationGateCycleRequest
import skillbill.engine.featuretask.validation.model.ValidationGateProgressStore
import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.repository.toFileLocation
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Path

internal val validationGateTestRepoRoot: Path = Path.of(".").toAbsolutePath().normalize()

internal val validationGateTestDeclaration: ValidationGateDeclaration =
  ValidationGateDeclaration(
    fullGateCommand = listOf("echo", "cache"),
    cacheBypassingFullGateCommand = listOf("echo", "full"),
    collectAllFullGateCommand = listOf("echo", "collect-all"),
    cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
    findings =
      ValidationGateFindingsLocator(
        format = ValidationGateFindingsFormat.JUNIT_XML,
        artifactGlobs = listOf("**/*.xml"),
        compilerDiagnostics =
          ValidationGateCompilerDiagnosticsLocator(
            GRADLE_KOTLIN_COMPILER_STDOUT,
          ),
        executedWork = ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY),
      ),
  )

internal fun outOfContractResolver(): ValidationGateResolver =
  ValidationGateResolver {
    throw ContractVersionMismatchError(
      "Platform pack 'fallback': declares contract_version '0.1' but the shell expects '1.8'.",
    )
  }

internal fun neverRunsGate(): ValidationGateRunner =
  object : ValidationGateRunner {
    override fun run(request: ValidationGateRunRequest): ValidationGateRunResult =
      error("validation gate must not launch when platform packs are out of contract")
  }

internal fun outOfContractCycle(): ValidationGateCycleRequest =
  ValidationGateCycleRequest(
    phaseId = "build",
    repoRoot = validationGateTestRepoRoot,
    request = minimalRequest(),
    validationDepth = ValidationDepth.DEFAULT,
    changedPaths = listOf("runtime-kotlin/foo.kt"),
    repositoryCheckpoint = "checkpoint",
    agentRepairLauncher =
      ValidationGateAgentRepairLauncher { _, _, _ ->
        error("repair must not launch when platform packs are out of contract")
      },
    progressStore = RecordingProgressStore(mutableListOf(), null),
  )

internal fun findingRow(finding: ValidationGateFinding): Map<String, String?> =
  linkedMapOf(
    "module" to finding.module,
    "rule_or_test_id" to finding.ruleOrTestId,
    "message" to finding.message,
    "location" to finding.location,
  )

internal class RecordingProgressStore(
  private val recorded: MutableList<FeatureTaskRuntimeValidationGateProgress>,
  initial: FeatureTaskRuntimeValidationGateProgress?,
) : ValidationGateProgressStore {
  private var loaded: FeatureTaskRuntimeValidationGateProgress? = initial

  override fun persist(
    workflowId: String,
    progress: FeatureTaskRuntimeValidationGateProgress,
  ) {
    recorded += progress
    loaded = progress
  }

  override fun load(workflowId: String): FeatureTaskRuntimeValidationGateProgress? = loaded
}

internal fun repoLocalConfig(gradleWrapper: String? = null): RepoLocalConfigPort =
  object : RepoLocalConfigPort {
    override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
      ReadRepoLocalConfigResult(
        RepoLocalConfig.defaults().copy(
          validationGate = ValidationGateRepoConfig(gradleWrapper = gradleWrapper),
        ),
      )
  }

internal fun declaredResolver(
  declaration: ValidationGateDeclaration = validationGateTestDeclaration,
): ValidationGateResolver =
  ValidationGateResolver { listOf(kotlinPackWithoutGate().copy(validationGate = declaration)) }

internal fun minimalRequest(): FeatureTaskRuntimeRunRequest =
  FeatureTaskRuntimeRunRequest(
    issueKey = "SKILL-180",
    workflowId = "wf-skill-180",
    sessionId = "session",
    runInvariants =
      FeatureTaskRuntimeRunInvariants(
        specReference = "spec.md",
        featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
        acceptanceCriteria = listOf("AC-001"),
        mandatesAndOverrides = emptyList(),
      ),
    invokedAgentId = "claude",
    repoRoot = validationGateTestRepoRoot,
  )

internal fun passed(forced: Boolean = false): ValidationGateRunResult =
  ValidationGateRunResult(
    exitCode = 0,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.PASSED,
    cacheMode = if (forced) ValidationGateCacheMode.FORCED_FULL else ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    executedCheckIdentities = emptyList(),
    findings = emptyList(),
    command = "./gradlew check",
  )

internal fun failedEmptyFindings(stdout: String = ""): ValidationGateRunResult =
  ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    executedCheckIdentities = emptyList(),
    findings = emptyList(),
    stdout = stdout,
    command = "./gradlew check",
  )

internal fun failedWith(vararg findings: ValidationGateFinding): ValidationGateRunResult =
  ValidationGateRunResult(
    exitCode = 1,
    durationMs = 1,
    outcome = ValidationGateRunOutcome.FAILED,
    cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
    executedWorkUnits = 1,
    executedCheckIdentities = emptyList(),
    findings = findings.toList(),
    command = "./gradlew check",
  )

internal fun completedRepair(): ValidationGateAgentRepairResult {
  val payload = JsonCodec.mapToJsonString(mapOf("produced_outputs" to emptyMap<String, Any?>()))
  return ValidationGateAgentRepairResult.Completed(
    FeatureTaskRuntimePhaseOutput(phaseId = "validate", iteration = 1, payload = payload),
  )
}

internal fun blockedRepair(reason: String = "child process failed"): ValidationGateAgentRepairResult =
  ValidationGateAgentRepairResult.Blocked(reason)

internal fun kotlinPackWithoutGate(): PlatformManifest =
  PlatformManifest(
    slug = "kotlin",
    packRoot = validationGateTestRepoRoot.resolve("platform-packs/kotlin").toFileLocation(),
    contractVersion = "1.8",
    routingSignals =
      RoutingSignals(
        strong = listOf("runtime-kotlin"),
        tieBreakers = emptyList(),
        path = listOf("runtime-kotlin"),
      ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    validationGate = null,
  )

internal fun reviewFallbackPackWithoutGate(): PlatformManifest =
  PlatformManifest(
    slug = "generic",
    packRoot = validationGateTestRepoRoot.resolve("platform-packs/generic").toFileLocation(),
    contractVersion = "1.8",
    routingSignals =
      RoutingSignals(
        strong = emptyList(),
        tieBreakers = emptyList(),
        path = emptyList(),
      ),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles =
      DeclaredFiles(
        baseline =
          validationGateTestRepoRoot.resolve(
            "code-review/bill-generic-code-review/content.md",
          ).toFileLocation(),
        areas = emptyMap(),
      ),
    areaMetadata = emptyMap(),
    fallbackCapabilities = setOf("code-review"),
    validationGate = null,
  )

internal class ScriptedGateRunner(
  private val results: List<ValidationGateRunResult>,
) : ValidationGateRunner {
  var calls: Int = 0
    private set
  val requests: MutableList<ValidationGateRunRequest> = mutableListOf()

  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
    val index = calls
    calls++
    requests += request
    return results.getOrElse(index) {
      error("ScriptedGateRunner exhausted after ${results.size} results; call=$index")
    }.copy(command = request.argv.joinToString(" "))
  }
}
