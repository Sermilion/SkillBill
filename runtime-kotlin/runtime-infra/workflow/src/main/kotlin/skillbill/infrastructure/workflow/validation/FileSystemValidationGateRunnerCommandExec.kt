package skillbill.infrastructure.workflow.validation

import org.w3c.dom.Element
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationFailureSubject
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import skillbill.workflow.taskruntime.validation.unparseableGateFailureMessage
import java.nio.file.Path
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

internal fun FileSystemValidationGateRunner.deriveOutcome(
  exitCode: Int,
  findings: List<ValidationGateFinding>,
): ValidationGateRunOutcome =
  when {
    findings.isNotEmpty() || exitCode != 0 -> ValidationGateRunOutcome.FAILED
    else -> ValidationGateRunOutcome.PASSED
  }

internal fun FileSystemValidationGateRunner.deriveExecutedWorkUnits(
  request: ValidationGateRunRequest,
  stdout: String,
): Int {
  val signal =
    request.declaration.findings.executedWork
      ?: return FileSystemValidationGateRunner.DEFAULT_EXECUTED_WORK_WHEN_UNDECLARED
  return when (signal.format) {
    ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY ->
      FileSystemValidationGateRunner.GRADLE_EXECUTED_PATTERN.find(stdout)?.groupValues?.get(1)?.toIntOrNull() ?: 0
  }
}

internal fun FileSystemValidationGateRunner.deriveExecutedCheckIdentities(
  request: ValidationGateRunRequest,
  stdout: String,
): List<String> =
  when (request.declaration.findings.executedWork?.format) {
    ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY ->
      FileSystemValidationGateGradleStdoutParsers.parseGradleExecutedTaskIdentities(stdout)
    null -> emptyList()
  }

internal fun FileSystemValidationGateRunner.parseFindings(
  request: ValidationGateRunRequest,
  stdout: String,
  artifactFloor: Instant,
): List<ValidationGateFinding> {
  val artifacts = parseArtifactFindings(request, artifactFloor)
  if (request.findingParseMode != ValidationGateFindingParseMode.COLLECT_ALL) {
    return artifacts
  }
  val compiler = parseCompilerDiagnostics(request, stdout)
  val spotless = FileSystemValidationGateGradleSpotlessStdoutParsers.parseGradleSpotlessStdout(request.repoRoot, stdout)
  val finerFindings = compiler + artifacts + spotless
  var coveredTaskKeys = FileSystemValidationGateGradleStdoutParsers.coveredGradleTaskKeys(finerFindings)
  val projectHealth = FileSystemValidationGateGradleStdoutParsers.parseGradleProjectHealthStdout(stdout)
  coveredTaskKeys = coveredTaskKeys + projectHealth.map { "${it.module}|projectHealth" }.toSet()
  val architectureCheck =
    FileSystemValidationGateGradleStdoutParsers.parseGradleArchitectureCheckStdout(stdout)
  coveredTaskKeys = coveredTaskKeys + architectureCheck.map { "${it.module}|architectureCheck" }.toSet()
  val taskHeaders =
    FileSystemValidationGateGradleStdoutParsers.parseGradleTaskFailureHeaders(stdout, coveredTaskKeys)
  return FileSystemValidationGateGradleSpotlessStdoutParsers.enrichSpotlessTaskHeaderFindings(
    (finerFindings + projectHealth + architectureCheck + taskHeaders)
      .distinctBy { FileSystemValidationGateRunner.findingIdentity(it) to it.failureSubject },
    stdout,
  )
}

internal fun FileSystemValidationGateRunner.finalizeFindings(
  request: ValidationGateRunRequest,
  parsed: List<ValidationGateFinding>,
  exitCode: Int,
  outcome: ValidationGateRunOutcome,
  stdout: String,
): List<ValidationGateFinding> {
  if (request.findingParseMode != ValidationGateFindingParseMode.COLLECT_ALL) {
    return parsed
  }
  val failed = exitCode != 0 || outcome == ValidationGateRunOutcome.FAILED
  if (failed && parsed.isEmpty()) {
    return listOf(
      ValidationGateFinding(
        module = FileSystemValidationGateRunner.UNPARSEABLE_GATE_MODULE,
        ruleOrTestId = FileSystemValidationGateRunner.UNPARSEABLE_GATE_RULE_ID,
        message =
          unparseableGateFailureMessage(
            gateLabel = "Validation gate",
            outcome = outcome.wireValue,
            exitCode = exitCode,
            stdout = stdout,
          ),
        location = null,
        failureSubject = FeatureTaskRuntimeValidationFailureSubject.UNKNOWN,
      ),
    )
  }
  return parsed
}

internal fun FileSystemValidationGateRunner.parseArtifactFindings(
  request: ValidationGateRunRequest,
  artifactFloor: Instant,
): List<ValidationGateFinding> =
  when (request.declaration.findings.format) {
    ValidationGateFindingsFormat.JUNIT_XML ->
      request.declaration.findings.artifactGlobs
        .flatMap { glob -> fileSystemValidationGateExpandGlob(request.repoRoot, glob) }
        .filter { FileSystemValidationGateRunner.producedByThisRun(it, artifactFloor) }
        .flatMap { path -> parseArtifactFile(request.repoRoot, path) }
        .distinctBy { FileSystemValidationGateRunner.findingIdentity(it) to it.failureSubject }
  }

internal fun FileSystemValidationGateRunner.parseArtifactFile(
  repoRoot: Path,
  path: Path,
): List<ValidationGateFinding> =
  if (path.toString().replace('\\', '/').contains("/reports/detekt/")) {
    parseDetektXmlFile(repoRoot, path)
  } else {
    parseJUnitXmlFile(path)
  }

internal fun FileSystemValidationGateRunner.parseCompilerDiagnostics(
  request: ValidationGateRunRequest,
  stdout: String,
): List<ValidationGateFinding> =
  when (request.declaration.findings.compilerDiagnostics.format) {
    ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT ->
      FileSystemValidationGateGradleStdoutParsers.parseGradleKotlinCompilerStdout(
        request.repoRoot,
        stdout,
      ) +
        FileSystemValidationGateGradleStdoutParsers.parseGradleQualityToolStdout(
          request.repoRoot,
          stdout,
        )
  }

internal fun FileSystemValidationGateRunner.parseDetektXmlFile(
  repoRoot: Path,
  path: Path,
): List<ValidationGateFinding> =
  try {
    val document = FileSystemValidationGateRunner.DOCUMENT_BUILDER.parse(path.toFile())
    val repo = repoRoot.toAbsolutePath().normalize()
    val files = document.getElementsByTagName("file")
    buildList {
      for (fileIndex in 0 until files.length) {
        val fileElement = files.item(fileIndex) as Element
        val rawFileName = fileElement.getAttribute("name").trim()
        if (rawFileName.isEmpty()) continue
        val relativeFile =
          FileSystemValidationGateGradlePaths.repoRelativeQualityPath(repo, rawFileName)
        val module = relativeFile.substringBefore('/').ifBlank { "<detekt>" }
        val errors = fileElement.getElementsByTagName("error")
        for (errorIndex in 0 until errors.length) {
          val error = errors.item(errorIndex) as Element
          val line = error.getAttribute("line").trim()
          val rule = error.getAttribute("source").substringAfterLast('.').ifBlank { "detekt" }
          val message = error.getAttribute("message").ifBlank { error.textContent?.trim().orEmpty() }
          add(
            ValidationGateFinding(
              module = module,
              ruleOrTestId = rule,
              message = message,
              location = listOf(relativeFile, line).filter(String::isNotBlank).joinToString(":").ifBlank { null },
              failureSubject =
                if (
                  rule != "detekt" && FileSystemValidationGateGradlePaths.isProjectOwnedPath(repo, rawFileName)
                ) {
                  FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE
                } else {
                  FeatureTaskRuntimeValidationFailureSubject.UNKNOWN
                },
            ),
          )
        }
      }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (_: Exception) {
    listOf(unparseableArtifactFinding(path, "detekt"))
  }

internal fun FileSystemValidationGateRunner.parseJUnitXmlFile(path: Path): List<ValidationGateFinding> =
  try {
    val document = FileSystemValidationGateRunner.DOCUMENT_BUILDER.parse(path.toFile())
    val testcases = document.getElementsByTagName("testcase")
    buildList {
      for (index in 0 until testcases.length) {
        val testcase = testcases.item(index) as Element
        val failure =
          testcase.getElementsByTagName("failure").item(0) as? Element
            ?: testcase.getElementsByTagName("error").item(0) as? Element
            ?: continue
        val classname = testcase.getAttribute("classname").ifBlank { path.parent?.fileName?.toString().orEmpty() }
        val name = testcase.getAttribute("name").ifBlank { "unknown" }
        val failureBody = failure.textContent?.trim().orEmpty()
        add(
          ValidationGateFinding(
            module = classname.substringBeforeLast('.').ifBlank { classname },
            ruleOrTestId = name,
            message = junitFailureMessage(failure),
            location = junitFailureLocation(testcase, failureBody),
            failureSubject = FeatureTaskRuntimeValidationFailureSubject.UNKNOWN,
          ),
        )
      }
    }
  } catch (error: CancellationException) {
    throw error
  } catch (_: Exception) {
    listOf(unparseableArtifactFinding(path, "junit"))
  }

internal fun FileSystemValidationGateRunner.unparseableArtifactFinding(
  path: Path,
  format: String,
): ValidationGateFinding =
  ValidationGateFinding(
    module = FileSystemValidationGateRunner.UNPARSEABLE_GATE_MODULE,
    ruleOrTestId = "unparseable_gate_artifact",
    message = "Validation gate $format XML at '$path' could not be parsed.",
    location = path.toString(),
    failureSubject = FeatureTaskRuntimeValidationFailureSubject.UNKNOWN,
  )
