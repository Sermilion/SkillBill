package skillbill.infrastructure.host.jvm

import me.tatarka.inject.annotations.Inject
import skillbill.infrastructure.host.process.BoundedExternalProcessOutput
import skillbill.infrastructure.host.process.BoundedExternalProcessRequest
import skillbill.infrastructure.host.process.BoundedExternalProcessRunner
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

sealed interface GateJvmDisposition {
  data class Export(val javaHome: String) : GateJvmDisposition

  object LeaveUnset : GateJvmDisposition

  data class Unresolved(val rejectedCandidate: String, val requiredMajor: String) : GateJvmDisposition
}

fun GateJvmDisposition.applyTo(environment: MutableMap<String, String>) {
  when (this) {
    is GateJvmDisposition.Export -> environment[GateJvmEnvironmentKeys.JAVA_HOME] = javaHome
    GateJvmDisposition.LeaveUnset -> environment.remove(GateJvmEnvironmentKeys.JAVA_HOME)
    is GateJvmDisposition.Unresolved -> environment.remove(GateJvmEnvironmentKeys.JAVA_HOME)
  }
}

@Inject
class GateJvmResolver(
  private val diagnostics: RuntimeDiagnostics,
  private val hostPlatform: HostPlatformPort,
) {
  fun resolve(childEnvironment: MutableMap<String, String>): GateJvmDisposition {
    val imageRoot = runtimeImageRoot(hostPlatform)
    val rejected = rejectedCandidate(childEnvironment)
    val dropped = dropRuntimeImageJava(childEnvironment, imageRoot)
    val disposition = evaluateGuard(childEnvironment, rejected)
    recordDecision(childEnvironment, dropped, imageRoot, disposition)
    return disposition
  }

  private fun evaluateGuard(
    environment: Map<String, String>,
    rejectedCandidate: String,
  ): GateJvmDisposition {
    val guard = materializeGuard()
    return try {
      dispositionOf(runGuard(guard, environment), rejectedCandidate)
    } finally {
      runCatching { Files.deleteIfExists(guard) }
    }
  }

  private fun materializeGuard(): Path {
    val bytes =
      GateJvmResolver::class.java.classLoader
        .getResourceAsStream(GUARD_CLASSPATH_RESOURCE)
        ?.use { stream -> stream.readBytes() }
        ?: throw gateJvmGuardResourceMissing(GUARD_CLASSPATH_RESOURCE)
    val guard = Files.createTempFile("skill-bill-java-guard", ".sh", OWNER_ONLY)
    Files.write(guard, bytes)
    return guard
  }

  private fun runGuard(
    guard: Path,
    environment: Map<String, String>,
  ): GuardEvaluation {
    val result =
      BoundedExternalProcessRunner.run(
        BoundedExternalProcessRequest(
          argv = listOf("sh", "-c", GUARD_PROGRAM, "sh", guard.toString()),
          environment = environment,
          clearEnvironment = true,
          mergeStderr = false,
          deadlineSeconds = GUARD_TIMEOUT_SECONDS,
          output = BoundedExternalProcessOutput.Captured(capBytes = null),
        ),
      )
    if (result.launchFailure) {
      throw gateJvmGuardExecution(
        "no POSIX sh available to evaluate $guard",
        result.readFailure ?: IOException(result.output),
      )
    }
    if (result.timedOut) {
      throw gateJvmGuardTimeout(GUARD_TIMEOUT_SECONDS)
    }
    return GuardEvaluation(status = result.exitCode, stdout = result.output)
  }

  private fun dispositionOf(
    evaluation: GuardEvaluation,
    rejectedCandidate: String,
  ): GateJvmDisposition {
    val output = parseGuardOutput(evaluation)
    if (evaluation.status == GUARD_RESOLVED_EXIT) {
      return if (output.resolvedHome.isEmpty()) {
        GateJvmDisposition.LeaveUnset
      } else {
        GateJvmDisposition.Export(output.resolvedHome)
      }
    }
    if (output.reachedRemediation) {
      return GateJvmDisposition.Unresolved(rejectedCandidate, output.requiredMajor)
    }
    throw gateJvmGuardExecution(
      "guard exited with status ${evaluation.status} without reaching its no-qualifying-JDK branch",
    )
  }

  private fun parseGuardOutput(evaluation: GuardEvaluation): GuardOutput {
    val lines = evaluation.stdout.split("\n")
    val requiredMajor = lines.getOrNull(1)?.trim().orEmpty()
    if (lines.size < GUARD_OUTPUT_LINES || requiredMajor.isEmpty()) {
      throw gateJvmGuardOutput("exit=${evaluation.status} output=${evaluation.stdout.trim()}")
    }
    return GuardOutput(
      resolvedHome = lines[0].trim(),
      requiredMajor = requiredMajor,
      reachedRemediation = lines[2].trim() == GUARD_REMEDIATION_SENTINEL,
    )
  }

  private fun recordDecision(
    sanitized: Map<String, String>,
    dropped: List<String>,
    imageRoot: Path?,
    disposition: GateJvmDisposition,
  ) {
    diagnostics.warning(
      "Gate JVM resolution: seam=GateJvmResolver.resolve branch=${branchOf(sanitized, disposition)} " +
        "used=${usedValueOf(disposition)} " +
        "dropped_image_candidates=${dropped.joinToString(",").ifEmpty { "none" }} " +
        "image_root=${imageRoot?.toString() ?: "unknown"}" +
        unresolvedDetailOf(disposition),
    )
  }

  private companion object {
    const val GUARD_CLASSPATH_RESOURCE = "skillbill/infrastructure/host/jvm/skill-bill-java-guard.sh"
    const val GUARD_TIMEOUT_SECONDS = 60L
    const val GUARD_RESOLVED_EXIT = 0
    const val GUARD_REMEDIATION_SENTINEL = "1"
    const val GUARD_OUTPUT_LINES = 3

    val OWNER_ONLY = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

    val GUARD_PROGRAM =
      """
      trap 'skill_bill_gate_exit=${'$'}?
      printf "%s\n" "${'$'}{JAVA_HOME:-}"
      printf "%s\n" "${'$'}{skill_bill_required_java_major:-}"
      printf "%s\n" "${'$'}{skill_bill_java_unresolved:-}"
      exit ${'$'}skill_bill_gate_exit' EXIT
      . "${'$'}1" >/dev/null
      """.trimIndent()

    fun rejectedCandidate(childEnvironment: Map<String, String>): String =
      GateJvmEnvironmentKeys.JAVA_HOME_CANDIDATES
        .firstNotNullOfOrNull { key -> childEnvironment[key]?.takeIf(String::isNotBlank) }
        ?: "<unset>"

    fun branchOf(
      sanitized: Map<String, String>,
      disposition: GateJvmDisposition,
    ): String =
      when (disposition) {
        is GateJvmDisposition.Export ->
          when (disposition.javaHome) {
            sanitized[GateJvmEnvironmentKeys.SKILL_BILL_JAVA_HOME] -> "skill_bill_java_home"
            sanitized[GateJvmEnvironmentKeys.JAVA_HOME] -> "inherited_java_home"
            else -> "scan"
          }

        GateJvmDisposition.LeaveUnset -> "path_java"
        is GateJvmDisposition.Unresolved -> "unresolved"
      }

    fun usedValueOf(disposition: GateJvmDisposition): String =
      when (disposition) {
        is GateJvmDisposition.Export -> disposition.javaHome
        GateJvmDisposition.LeaveUnset -> "<unset>"
        is GateJvmDisposition.Unresolved -> "<none>"
      }

    fun unresolvedDetailOf(disposition: GateJvmDisposition): String =
      when (disposition) {
        is GateJvmDisposition.Unresolved ->
          " rejected_candidate=${disposition.rejectedCandidate} expected=java_${disposition.requiredMajor}+"

        else -> ""
      }
  }
}

private data class GuardEvaluation(val status: Int, val stdout: String)

private data class GuardOutput(
  val resolvedHome: String,
  val requiredMajor: String,
  val reachedRemediation: Boolean,
)

internal fun runtimeImageRoot(hostPlatform: HostPlatformPort): Path? =
  runningJavaHome(hostPlatform)?.takeUnless(::hostsAJavaCompiler)

private fun runningJavaHome(hostPlatform: HostPlatformPort): Path? =
  runCatching { hostPlatform.resolveJavaHome().toRealPath() }.getOrNull()

private fun hostsAJavaCompiler(home: Path): Boolean =
  JAVA_COMPILER_EXECUTABLES.any { name -> Files.isExecutable(home.resolve("bin").resolve(name)) }

private val JAVA_COMPILER_EXECUTABLES = listOf("javac", "javac.exe")

internal fun dropRuntimeImageJava(
  environment: MutableMap<String, String>,
  imageRoot: Path?,
): List<String> {
  val dropped = mutableListOf<String>()
  GateJvmEnvironmentKeys.JAVA_HOME_CANDIDATES.forEach { key ->
    val value = environment[key]
    if (value != null && liesInside(value, imageRoot)) {
      environment.remove(key)
      dropped += "$key=$value"
    }
  }
  val entries = environment[GateJvmEnvironmentKeys.PATH]?.split(File.pathSeparator) ?: return dropped
  val (inImage, kept) = entries.partition { entry -> liesInside(entry, imageRoot) }
  if (inImage.isNotEmpty()) {
    environment[GateJvmEnvironmentKeys.PATH] = kept.joinToString(File.pathSeparator)
    dropped += inImage.map { entry -> "${GateJvmEnvironmentKeys.PATH}=$entry" }
  }
  return dropped
}

private fun liesInside(
  value: String,
  imageRoot: Path?,
): Boolean {
  if (imageRoot == null || value.isBlank()) return false
  val candidate = runCatching { Path.of(value).toRealPath() }.getOrNull() ?: return false
  return candidate.startsWith(imageRoot)
}
