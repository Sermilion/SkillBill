package skillbill.infrastructure.skills.scaffold.runtime.validation

import skillbill.contracts.SharedPayloadKeys
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE

data class RepoValidationReport(
  val issues: List<String>,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val structuredIssues: List<RepoValidationIssue> = issues.map(RepoValidationIssue::fromRawIssue),
) {
  val passed: Boolean = issues.isEmpty()

  fun toPayload(): Map<String, Any?> =
    mapOf(
      SharedPayloadKeys.STATUS to if (passed) "passed" else "failed",
      "skill_count" to skillCount,
      "governed_addon_count" to addonCount,
      "platform_pack_count" to platformPackCount,
      "native_agent_count" to nativeAgentCount,
      "issues" to issues,
    )
}

data class RepoValidationIssue(
  val severity: RepoValidationIssueSeverity,
  val message: String,
  val sourcePath: String?,
  val code: String? = null,
  val name: String? = null,
  val exceptionName: String? = null,
) {
  companion object {
    fun fromRawIssue(raw: String): RepoValidationIssue {
      val separator = raw.indexOf(": ")
      return if (separator > 0 && raw.substring(0, separator).isNotBlank() &&
        !raw.substring(0, separator).contains(' ')
      ) {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = raw.substring(0, separator),
          message = raw.substring(separator + 2),
        )
      } else {
        RepoValidationIssue(
          severity = RepoValidationIssueSeverity.ERROR,
          sourcePath = null,
          message = raw,
        )
      }
    }
  }
}

enum class RepoValidationIssueSeverity {
  ERROR,
  WARNING,
  INFO,
}

data class ReleaseRefMetadata(
  val tag: String,
  val version: String,
  val major: Int,
  val minor: Int,
  val patch: Int,
  val prerelease: Boolean,
  val prereleaseIdentifier: String?,
  val buildMetadata: String?,
) {
  fun toPayload(): Map<String, Any?> =
    mapOf(
      "tag" to tag,
      "version" to version,
      "major" to major,
      "minor" to minor,
      "patch" to patch,
      "prerelease" to prerelease,
      "prerelease_identifier" to prereleaseIdentifier,
      "build_metadata" to buildMetadata,
    )
}

sealed interface ReleaseRefValidationResult {
  data class Valid(val metadata: ReleaseRefMetadata) : ReleaseRefValidationResult

  data class Rejected(val message: String) : ReleaseRefValidationResult
}

object RepoValidationRuntime {
  internal fun validateRepo(
    repoRoot: Path,
    nativeAgentCompositionContext: NativeAgentCompositionContext,
    plannedNativeAgentWorkerIssues: (Path) -> List<String> = { _ -> emptyList() },
  ): RepoValidationReport {
    val root = repoRoot.toAbsolutePath().normalize()
    val collected = collectRepoValidationIssues(root, nativeAgentCompositionContext, plannedNativeAgentWorkerIssues)
    return RepoValidationReport(
      issues = collected.issues.sorted(),
      skillCount = collected.skillNames.size,
      addonCount = collected.addonCount,
      platformPackCount = collected.platformPackCount,
      nativeAgentCount = collected.nativeAgentCount,
    )
  }

  internal fun extractSecondLevelHeading(
    text: String,
    heading: String,
  ): String {
    val normalized = text.replace("\r\n", "\n")
    val startMarker = "## $heading"
    val start = normalized.indexOf(startMarker)
    if (start < 0) return ""
    val next = normalized.indexOf("\n## ", start + startMarker.length)
    return normalized.substring(start, if (next < 0) normalized.length else next).trimEnd()
  }

  fun parseReleaseRef(rawValue: String): ReleaseRefMetadata =
    RepoValidationRuntimeReleasePolicy.parseReleaseRef(rawValue)

  fun validateReleaseRef(
    repoRoot: Path,
    rawValue: String,
    forcePrerelease: Boolean = false,
  ): ReleaseRefValidationResult =
    RepoValidationRuntimeReleasePolicy.validateReleaseRef(repoRoot, rawValue, forcePrerelease)

  fun appendGithubOutput(
    outputPath: Path,
    metadata: ReleaseRefMetadata,
  ) {
    Files.writeString(
      outputPath,
      buildString {
        appendLine("tag=${metadata.tag}")
        appendLine("version=${metadata.version}")
        appendLine("prerelease=${if (metadata.prerelease) "true" else "false"}")
      },
      CREATE,
      APPEND,
    )
  }
}
