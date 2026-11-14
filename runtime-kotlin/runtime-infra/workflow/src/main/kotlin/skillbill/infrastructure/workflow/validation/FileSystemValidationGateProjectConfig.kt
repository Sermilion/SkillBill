package skillbill.infrastructure.workflow.validation

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode
import skillbill.ports.validation.model.ValidationGateCommandSource
import skillbill.ports.validation.model.ValidationGateRunRequest
import java.nio.file.Files
import java.nio.file.Path

internal fun resolveProjectConfigWorkingDirectory(request: ValidationGateRunRequest): Path {
  val relative = requireRelativeWorkingDirectory(request.executionWorkingDirectory)
  val normalizedRepo = request.repoRoot.toAbsolutePath().normalize()
  val canonicalRepo = FileSystemValidationGateGradlePaths.canonicalizeExisting(normalizedRepo)
  if (relative == ".") return canonicalRepo
  return requireCanonicalContained(canonicalRepo, requireContainedDirectory(normalizedRepo, relative))
}

internal fun ValidationGateRunRequest.usesProjectConfig(): Boolean =
  commandSource == ValidationGateCommandSource.PROJECT_CONFIG

private fun requireRelativeWorkingDirectory(relative: String?): String {
  if (relative == null) {
    throw invalidProjectConfigWorkingDirectory("Project-config gate execution requires a working directory.")
  }
  if (relative.isBlank() || Path.of(relative).isAbsolute) {
    throw invalidProjectConfigWorkingDirectory(
      if (relative.isBlank()) {
        "Project-config gate working directory is missing or unreadable: $relative"
      } else {
        "Project-config gate working directory escapes the active checkout."
      },
    )
  }
  return relative
}

private fun requireContainedDirectory(
  repo: Path,
  relative: String,
): Path {
  val resolved = repo.resolve(relative).normalize()
  if (!resolved.startsWith(repo)) {
    throw invalidProjectConfigWorkingDirectory(
      "Project-config gate working directory escapes the active checkout.",
    )
  }
  if (!Files.isDirectory(resolved) || !Files.isReadable(resolved)) {
    throw invalidProjectConfigWorkingDirectory(
      "Project-config gate working directory is missing or unreadable: $relative",
    )
  }
  return resolved
}

private fun requireCanonicalContained(
  canonicalRepo: Path,
  resolved: Path,
): Path {
  val canonicalCwd = FileSystemValidationGateGradlePaths.canonicalizeExisting(resolved)
  if (!canonicalCwd.startsWith(canonicalRepo)) {
    throw invalidProjectConfigWorkingDirectory(
      "Project-config gate working directory escapes the active checkout.",
    )
  }
  return canonicalCwd
}

private fun invalidProjectConfigWorkingDirectory(detail: String): SkillBillRuntimeException =
  SkillBillRuntimeException(FeatureTaskRuntimeFailureCode.INVALID_QUALITY_GATE_CONFIGURATION, detail)
