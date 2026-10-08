package skillbill.infrastructure.workflow.validation

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.jvm.GateJvmDisposition
import skillbill.infrastructure.host.jvm.GateJvmResolver
import skillbill.infrastructure.host.jvm.GateJvmStartupFailureException
import skillbill.infrastructure.host.jvm.GateJvmUnresolvedException
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.applyTo
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.validation.model.ValidationGateRunResult
import skillbill.workflow.taskruntime.validation.gateStdoutExcerpt
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory

@Inject
class FileSystemValidationGateRunner(
  private val clock: Clock,
  private val gateJvmResolver: GateJvmResolver,
) : ValidationGateRunner {
  override fun run(request: ValidationGateRunRequest): ValidationGateRunResult {
    val baselineEnvironment = LinkedHashMap(JdkHostPlatformPort.resolveEnvironment())
    return run(request, baselineEnvironment, gateJvmResolver.resolve(baselineEnvironment))
  }

  internal fun run(
    request: ValidationGateRunRequest,
    baselineEnvironment: MutableMap<String, String>,
    gateJvm: GateJvmDisposition,
  ): ValidationGateRunResult {
    val started = System.nanoTime()
    val artifactFloor = clock.instant().truncatedTo(ChronoUnit.SECONDS)
    val outputFile = Files.createTempFile("skillbill-validation-gate", ".out")
    return try {
      gateJvm.applyTo(baselineEnvironment)
      val processResult =
        BoundedExternalProcessRunner.run(
          BoundedExternalProcessRequest(
            argv = request.argv,
            workingDirectory = request.repoRoot,
            environment = baselineEnvironment,
            clearEnvironment = true,
            deadlineSeconds = GATE_TIMEOUT_MINUTES * 60L,
            output = BoundedExternalProcessOutput.RedirectToFile(outputFile),
          ),
        )
      if (processResult.timedOut) {
        throw ValidationGateProcessException(
          "Validation gate command timed out after ${GATE_TIMEOUT_MINUTES}m: ${request.argv.joinToString(" ")}",
        )
      }
      if (processResult.launchFailure) {
        throw ValidationGateProcessException(processResult.output)
      }
      val stdout = processResult.output
      val durationMs = ((System.nanoTime() - started) / NANOS_PER_MILLIS).coerceAtLeast(0L)
      val executedWorkUnits = deriveExecutedWorkUnits(request, stdout)
      val executedCheckIdentities = deriveExecutedCheckIdentities(request, stdout)
      val exitCode = processResult.exitCode
      val parsedFindings = parseFindings(request, stdout, artifactFloor)
      rejectGateJvmFailure(gateJvm, exitCode, parsedFindings, stdout)
      val outcome = deriveOutcome(exitCode, parsedFindings)
      ValidationGateRunResult(
        exitCode = exitCode,
        durationMs = durationMs,
        outcome = outcome,
        cacheMode = request.cacheMode,
        executedWorkUnits = executedWorkUnits,
        executedCheckIdentities = executedCheckIdentities,
        findings = finalizeFindings(request, parsedFindings, exitCode, outcome, stdout),
        stdout = stdout,
        command = request.argv.joinToString(" "),
      )
    } finally {
      runCatching { Files.deleteIfExists(outputFile) }
    }
  }

  companion object {
    private const val GATE_TIMEOUT_MINUTES = 120L
    private const val NANOS_PER_MILLIS = 1_000_000L
    internal const val DEFAULT_EXECUTED_WORK_WHEN_UNDECLARED = 1
    internal const val UNPARSEABLE_GATE_MODULE = "<validation-gate>"
    internal const val UNPARSEABLE_GATE_RULE_ID = "unparseable_gate_failure"
    internal val GRADLE_EXECUTED_PATTERN = Regex("""(\d+)\s+executed""", RegexOption.IGNORE_CASE)
    internal val DOCUMENT_BUILDER =
      DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isValidating = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
      }.newDocumentBuilder()

    internal fun findingIdentity(finding: ValidationGateFinding): String =
      "${finding.module}|${finding.ruleOrTestId}|${finding.message}|${finding.location}"

    internal fun producedByThisRun(
      path: Path,
      artifactFloor: Instant,
    ): Boolean = runCatching { !Files.getLastModifiedTime(path).toInstant().isBefore(artifactFloor) }.getOrDefault(true)

    internal fun expandGlob(
      repoRoot: Path,
      glob: String,
    ): List<Path> = fileSystemValidationGateExpandGlob(repoRoot, glob)
  }
}

internal class ValidationGateProcessException(message: String, cause: Throwable? = null) : RuntimeException(
  message,
  cause,
)

private val JVM_STARTUP_FAILURE_MARKERS =
  listOf(
    "Error occurred during initialization of VM",
    "Could not create the Java Virtual Machine",
    "may be missing from runtime image",
  )

private val JAVA_MISSING_OR_TOO_OLD_MARKERS =
  listOf(
    "no 'java' command could be found",
    "JAVA_HOME is not set",
    "JAVA_HOME is set to an invalid directory",
    "JAVA_HOME is not defined correctly",
    "java: command not found",
    "java: not found",
    "Unable to locate a Java Runtime",
    "UnsupportedClassVersionError",
    "compiled by a more recent version of the Java Runtime",
    "Unsupported class file major version",
    "invalid source release",
    "invalid target release",
    "error: release version",
    "requires JVM 17 or later to run",
    "This build uses a Java",
    "Cannot find a Java installation on your machine",
    "No matching toolchains found",
  )

internal fun rejectGateJvmFailure(
  disposition: GateJvmDisposition,
  exitCode: Int,
  parsedFindings: List<ValidationGateFinding>,
  stdout: String,
) {
  if (exitCode == 0 || parsedFindings.isNotEmpty()) return
  if (disposition is GateJvmDisposition.Unresolved && reportsMissingOrTooOldJava(stdout)) {
    throw GateJvmUnresolvedException(disposition.rejectedCandidate, disposition.requiredMajor)
  }
  if (JVM_STARTUP_FAILURE_MARKERS.none { marker -> stdout.contains(marker) }) return
  throw GateJvmStartupFailureException(resolvedGateJvmLabel(disposition), gateStdoutExcerpt(stdout))
}

private fun reportsMissingOrTooOldJava(stdout: String): Boolean =
  JAVA_MISSING_OR_TOO_OLD_MARKERS.any { marker -> stdout.contains(marker) }

private fun resolvedGateJvmLabel(disposition: GateJvmDisposition): String =
  when (disposition) {
    is GateJvmDisposition.Export -> disposition.javaHome
    GateJvmDisposition.LeaveUnset -> PATH_RESOLVED_GATE_JVM
    is GateJvmDisposition.Unresolved -> disposition.rejectedCandidate
  }

private const val PATH_RESOLVED_GATE_JVM = "<java resolved from PATH>"
