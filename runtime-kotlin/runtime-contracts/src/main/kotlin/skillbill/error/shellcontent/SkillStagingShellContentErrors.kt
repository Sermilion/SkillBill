package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class SkillStagingFailureCode : RuntimeFailureCode {
  INTERNAL_SKILL_SIDECAR_COLLISION,
  INVALID_AUTHORED_SKILL_SIDECAR,
  INVALID_REVIEW_SKILL_STRUCTURE,
  MISSING_CONTENT_FILE,
  COMPOSED_NATIVE_AGENT_BUDGET_EXCEEDED,
  MISSING_REQUIRED_SECTION,
  INVALID_SKILL_MD_SHAPE,
  MISSING_INSTALLED_NATIVE_AGENT,
  INVALID_INTERNAL_SKILL_CLASSIFICATION,
  MISSING_BASELINE_PLATFORM_SELECTION,
  INVALID_FALLBACK_CAPABILITY,
  SKILL_STAGING_FAILURE,
}

fun internalSkillSidecarCollision(
  parentSkillName: String,
  internalSkillName: String,
  sidecarRelativePath: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    SkillStagingFailureCode.INTERNAL_SKILL_SIDECAR_COLLISION,
    "Internal skill '$internalSkillName' cannot be staged as sidecar " +
      "'$sidecarRelativePath' inside parent '$parentSkillName' skill directory: " +
      "another staged or authored file already claims that path. Rename or remove the conflicting file.",
    cause,
  )

fun invalidAuthoredSkillSidecar(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.INVALID_AUTHORED_SKILL_SIDECAR, message, cause)

fun invalidReviewSkillStructure(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.INVALID_REVIEW_SKILL_STRUCTURE, message, cause)

fun missingContentFile(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.MISSING_CONTENT_FILE, message, cause)

fun composedNativeAgentBudgetExceeded(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.COMPOSED_NATIVE_AGENT_BUDGET_EXCEEDED, message, cause)

fun missingRequiredSection(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.MISSING_REQUIRED_SECTION, message, cause)

fun invalidSkillMdShape(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.INVALID_SKILL_MD_SHAPE, message, cause)

fun invalidNativeAgentLinkInventorySchema(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.SKILL_STAGING_FAILURE, message, cause)

fun missingInstalledNativeAgent(
  logicalName: String,
  provider: String,
  expectedPath: String,
  reason: String,
  repairCommand: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    SkillStagingFailureCode.MISSING_INSTALLED_NATIVE_AGENT,
    "Native agent '$logicalName' for provider '$provider' failed preflight at '$expectedPath': $reason. " +
      "Repair with: $repairCommand",
    cause,
  )

fun invalidInternalSkillClassification(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.INVALID_INTERNAL_SKILL_CLASSIFICATION, message, cause)

fun missingBaselinePlatformSelection(
  selectingSlug: String,
  requiredBaselineSlug: String,
  declaringManifestPath: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    SkillStagingFailureCode.MISSING_BASELINE_PLATFORM_SELECTION,
    "Platform pack '$selectingSlug' declares a required baseline layer on '$requiredBaselineSlug' " +
      "(declared in '$declaringManifestPath'), but '$requiredBaselineSlug' is not in the selection. " +
      "Select '$requiredBaselineSlug' (or use platform mode ALL) so the baseline sidecar is present " +
      "at review time.",
    cause,
  )

fun invalidFallbackCapability(message: String, cause: Throwable? = null): SkillBillRuntimeException =
  SkillBillRuntimeException(SkillStagingFailureCode.INVALID_FALLBACK_CAPABILITY, message, cause)
