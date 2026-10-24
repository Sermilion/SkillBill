package skillbill.error.featuretask.qa

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode

private const val DEVICE_LEASE_FAILED_PREFIX = "QA device lease failed: "
private const val OWNED_RESOURCE_FAILED_PREFIX = "QA owned resource failed: "

private val QA_FAILURE_CODES =
  setOf(
    FeatureTaskRuntimeFailureCode.INCOMPATIBLE_QA_CAPABILITY,
    FeatureTaskRuntimeFailureCode.QA_DEVICE_LEASE_FAILED,
    FeatureTaskRuntimeFailureCode.QA_OWNED_RESOURCE_FAILED,
    FeatureTaskRuntimeFailureCode.QA_WORKTREE_CHANGED,
    FeatureTaskRuntimeFailureCode.QA_REPOSITORY_UNREADABLE,
  )

val SkillBillRuntimeException.isQaFailure: Boolean
  get() = code in QA_FAILURE_CODES

val SkillBillRuntimeException.isQaDeviceLeaseFailure: Boolean
  get() = code == FeatureTaskRuntimeFailureCode.QA_DEVICE_LEASE_FAILED

val SkillBillRuntimeException.qaDeviceLeaseReason: String
  get() = message.orEmpty().removePrefix(DEVICE_LEASE_FAILED_PREFIX)

val SkillBillRuntimeException.isQaOwnedResourceFailure: Boolean
  get() = code == FeatureTaskRuntimeFailureCode.QA_OWNED_RESOURCE_FAILED

val SkillBillRuntimeException.qaOwnedResourceReason: String
  get() = message.orEmpty().removePrefix(OWNED_RESOURCE_FAILED_PREFIX)

fun incompatibleQaCapability(reason: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INCOMPATIBLE_QA_CAPABILITY,
    "The installed QA capability cannot run: $reason",
  )

fun qaDeviceLeaseFailed(
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_DEVICE_LEASE_FAILED,
    "$DEVICE_LEASE_FAILED_PREFIX$reason",
    cause,
  )

fun qaOwnedResourceFailed(
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_OWNED_RESOURCE_FAILED,
    "$OWNED_RESOURCE_FAILED_PREFIX$reason",
    cause,
  )

fun qaWorktreeChanged(detail: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_WORKTREE_CHANGED,
    "QA is report-only, but the run changed the repository: $detail",
  )

fun qaRepositoryUnreadable(
  detail: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_REPOSITORY_UNREADABLE,
    "QA could not read the repository state it tests: $detail",
    cause,
  )

fun unknownQaSelection(
  value: String,
  allowed: List<String>,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.UNKNOWN_QA_SELECTION,
    "Unknown QA selection '$value'. Allowed: ${allowed.joinToString()}.",
  )

fun invalidQaRepairStateSchema(
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_QA_REPAIR_STATE_SCHEMA,
    "QA repair state fails schema validation: $reason",
    cause,
  )
