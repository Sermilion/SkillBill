package skillbill.infrastructure.workflow.validation

import skillbill.config.model.applyValidationGateGradleWrapper
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

private const val GRADLE_WRAPPER_FILE = "gradlew"
private val ROOT_GRADLE_WRAPPER_COMMANDS = setOf("./$GRADLE_WRAPPER_FILE", GRADLE_WRAPPER_FILE)

internal fun nestedProjectRequests(request: ValidationGateRunRequest): List<ValidationGateRunRequest> {
  val targetsRootWrapper = request.argv.firstOrNull() in ROOT_GRADLE_WRAPPER_COMMANDS
  if (!targetsRootWrapper || Files.exists(request.repoRoot.resolve(GRADLE_WRAPPER_FILE))) return emptyList()
  return nestedGradleWrappers(request.repoRoot).map { wrapper ->
    request.copy(argv = applyValidationGateGradleWrapper(request.argv, wrapper))
  }
}

private fun nestedGradleWrappers(repoRoot: Path): List<String> =
  Files.list(repoRoot).use { entries ->
    entries.toList()
      .filter { entry -> !entry.name.startsWith(".") && Files.isExecutable(entry.resolve(GRADLE_WRAPPER_FILE)) }
      .map { project -> "${project.name}/$GRADLE_WRAPPER_FILE" }
      .sorted()
  }

internal fun FileSystemValidationGateRunner.mergeNestedProjectRuns(
  request: ValidationGateRunRequest,
  runs: List<ValidationGateRunResult>,
): ValidationGateRunResult {
  val exitCode = runs.firstOrNull { run -> run.exitCode != 0 }?.exitCode ?: 0
  val findings = runs.flatMap { run -> run.findings }.distinctBy(FileSystemValidationGateRunner::findingIdentity)
  return ValidationGateRunResult(
    exitCode = exitCode,
    durationMs = runs.sumOf { run -> run.durationMs },
    outcome = deriveOutcome(exitCode, findings),
    cacheMode = request.cacheMode,
    executedWorkUnits = runs.sumOf { run -> run.executedWorkUnits },
    executedCheckIdentities = runs.flatMap { run -> run.executedCheckIdentities }.distinct(),
    findings = findings,
    stdout = runs.joinToString("\n") { run -> "> ${run.command}\n${run.stdout}" },
    command = request.argv.joinToString(" "),
  )
}
