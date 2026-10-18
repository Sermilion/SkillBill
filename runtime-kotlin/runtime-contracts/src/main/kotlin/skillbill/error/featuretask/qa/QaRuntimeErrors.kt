package skillbill.error.featuretask.qa

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode

private const val DEVICE_LEASE_FAILED_PREFIX = "QA device lease failed: "

private val QA_FAILURE_CODES =
  setOf(
    FeatureTaskRuntimeFailureCode.INVALID_QA_RECEIPT_SCHEMA,
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_IDENTITY_MISMATCH,
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_UNSUPPORTED_CLAIM,
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_MISSING,
    FeatureTaskRuntimeFailureCode.INCOMPATIBLE_QA_CAPABILITY,
    FeatureTaskRuntimeFailureCode.QA_DEVICE_LEASE_FAILED,
    FeatureTaskRuntimeFailureCode.QA_WORKTREE_CHANGED,
    FeatureTaskRuntimeFailureCode.QA_REPOSITORY_UNREADABLE,
  )

val SkillBillRuntimeException.isQaFailure: Boolean
  get() = code in QA_FAILURE_CODES

val SkillBillRuntimeException.isQaDeviceLeaseFailure: Boolean
  get() = code == FeatureTaskRuntimeFailureCode.QA_DEVICE_LEASE_FAILED

val SkillBillRuntimeException.qaDeviceLeaseReason: String
  get() = message.orEmpty().removePrefix(DEVICE_LEASE_FAILED_PREFIX)

fun invalidQaReceipt(
  reason: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.INVALID_QA_RECEIPT_SCHEMA,
    "QA receipt fails schema validation: $reason",
    cause,
  )

fun qaReceiptIdentityMismatch(
  field: String,
  expected: String,
  actual: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_IDENTITY_MISMATCH,
    "QA receipt $field '$actual' does not match the tested $field '$expected'.",
  )

fun qaReceiptUnsupportedClaim(reason: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_UNSUPPORTED_CLAIM,
    "QA receipt makes an unsupported claim: $reason",
  )

fun qaReceiptMissing(receiptPath: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_RECEIPT_MISSING,
    "The QA run wrote no receipt at '$receiptPath'.",
  )

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

fun qaWorktreeChanged(detail: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_WORKTREE_CHANGED,
    "QA is report-only, but the run changed the repository: $detail",
  )

fun qaRepositoryUnreadable(detail: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    FeatureTaskRuntimeFailureCode.QA_REPOSITORY_UNREADABLE,
    "QA could not read the repository state it tests: $detail",
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
