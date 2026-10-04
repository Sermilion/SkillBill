package skillbill.ports.validation.model

import skillbill.contracts.validation.ReleaseRefMetadataContract
import skillbill.contracts.validation.RepoValidationReportContract

data class RepoValidationReport(
  val issues: List<String>,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val structuredIssues: List<RepoValidationIssue>,
) {
  val passed: Boolean = issues.isEmpty()

  fun toContract(): RepoValidationReportContract =
    RepoValidationReportContract(
      passed = passed,
      skillCount = skillCount,
      addonCount = addonCount,
      platformPackCount = platformPackCount,
      nativeAgentCount = nativeAgentCount,
      issues = issues,
    )
}

data class RepoValidationIssue(
  val severity: RepoValidationIssueSeverity,
  val message: String,
  val sourcePath: String?,
  val code: String? = null,
  val name: String? = null,
  val exceptionName: String? = null,
)

enum class RepoValidationIssueSeverity {
  ERROR,
  WARNING,
  INFO,
}

sealed interface ReleaseRefValidation {
  data class Valid(val metadata: ReleaseRefMetadata) : ReleaseRefValidation

  data class Rejected(val message: String) : ReleaseRefValidation
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
  fun toContract(): ReleaseRefMetadataContract =
    ReleaseRefMetadataContract(
      tag = tag,
      version = version,
      major = major,
      minor = minor,
      patch = patch,
      prerelease = prerelease,
      prereleaseIdentifier = prereleaseIdentifier,
      buildMetadata = buildMetadata,
    )
}
