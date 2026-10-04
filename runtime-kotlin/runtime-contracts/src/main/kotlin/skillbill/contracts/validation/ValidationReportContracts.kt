package skillbill.contracts.validation

import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.SharedPayloadKeys

private object ValidationReportPayloadKeys {
  const val SKILL_COUNT: String = "skill_count"
  const val GOVERNED_ADDON_COUNT: String = "governed_addon_count"
  const val PLATFORM_PACK_COUNT: String = "platform_pack_count"
  const val NATIVE_AGENT_COUNT: String = "native_agent_count"
  const val ISSUES: String = "issues"
  const val TAG: String = "tag"
  const val VERSION: String = "version"
  const val MAJOR: String = "major"
  const val MINOR: String = "minor"
  const val PATCH: String = "patch"
  const val PRERELEASE: String = "prerelease"
  const val PRERELEASE_IDENTIFIER: String = "prerelease_identifier"
  const val BUILD_METADATA: String = "build_metadata"
}

data class RepoValidationReportContract(
  val passed: Boolean,
  val skillCount: Int,
  val addonCount: Int,
  val platformPackCount: Int,
  val nativeAgentCount: Int,
  val issues: List<String>,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to if (passed) "passed" else "failed",
      ValidationReportPayloadKeys.SKILL_COUNT to skillCount,
      ValidationReportPayloadKeys.GOVERNED_ADDON_COUNT to addonCount,
      ValidationReportPayloadKeys.PLATFORM_PACK_COUNT to platformPackCount,
      ValidationReportPayloadKeys.NATIVE_AGENT_COUNT to nativeAgentCount,
      ValidationReportPayloadKeys.ISSUES to issues,
    )
}

data class ReleaseRefMetadataContract(
  val tag: String,
  val version: String,
  val major: Int,
  val minor: Int,
  val patch: Int,
  val prerelease: Boolean,
  val prereleaseIdentifier: String?,
  val buildMetadata: String?,
) : JsonPayloadContract {
  override fun toPayload(): Map<String, Any?> =
    linkedMapOf(
      ValidationReportPayloadKeys.TAG to tag,
      ValidationReportPayloadKeys.VERSION to version,
      ValidationReportPayloadKeys.MAJOR to major,
      ValidationReportPayloadKeys.MINOR to minor,
      ValidationReportPayloadKeys.PATCH to patch,
      ValidationReportPayloadKeys.PRERELEASE to prerelease,
      ValidationReportPayloadKeys.PRERELEASE_IDENTIFIER to prereleaseIdentifier,
      ValidationReportPayloadKeys.BUILD_METADATA to buildMetadata,
    )
}
