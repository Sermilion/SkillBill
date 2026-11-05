package skillbill.approval.model

import skillbill.contracts.approval.ApprovalAuthorityScope
import skillbill.contracts.approval.ApprovalOption
import skillbill.contracts.approval.ApprovalRequestRecord
import skillbill.contracts.approval.ApprovalRuleRecord

const val ONE_TIME_ALLOW_OPTION_KIND = "allow"
const val DENY_OPTION_KIND = "deny"

const val PROVIDER_REMEMBER_OPTION_KIND = "provider_remember"

enum class ApprovalExecutionMode(val wireValue: String) {
  READ_ONLY("read_only"),
  ACT("act"),
}

enum class ApprovalActionClass(val wireValue: String) {
  ORDINARY_TOOL("ordinary_tool"),
  ORDINARY_COMMAND("ordinary_command"),
  PERMISSION_ESCALATION("permission_escalation"),
  NETWORK_OR_CREDENTIAL("network_or_credential"),
  PROVIDER_INTERACTION("provider_interaction"),
  PARKED_CONFIRMATION("parked_confirmation"),
}

enum class ApprovalProviderDisposition(val wireValue: String) {
  NONE("none"),
  DENIED("denied"),
  INTERACTION_REQUIRED("interaction_required"),
}

enum class ApprovalGrantRefusal {
  PROVIDER_DENIED,
  PROVIDER_INTERACTION_REQUIRED,
  NO_ONE_TIME_GRANT,
  READ_ONLY_WRITE_ACTION,
  NOT_ORDINARY_ACTION,
  INCOMPLETE_INPUT,
  INCOMPLETE_SCOPE,
}

data class ApprovalGrantFacts(
  val actionClass: ApprovalActionClass,
  val writeCapable: Boolean,
  val inputComplete: Boolean,
  val providerDisposition: ApprovalProviderDisposition,
)

fun ApprovalGrantFacts.onceRefusal(
  mode: ApprovalExecutionMode,
  selected: ApprovalOption,
): ApprovalGrantRefusal? =
  when {
    providerDisposition == ApprovalProviderDisposition.DENIED -> ApprovalGrantRefusal.PROVIDER_DENIED
    providerDisposition == ApprovalProviderDisposition.INTERACTION_REQUIRED ->
      ApprovalGrantRefusal.PROVIDER_INTERACTION_REQUIRED
    selected.kind != ONE_TIME_ALLOW_OPTION_KIND -> ApprovalGrantRefusal.NO_ONE_TIME_GRANT
    mode == ApprovalExecutionMode.READ_ONLY && writeCapable -> ApprovalGrantRefusal.READ_ONLY_WRITE_ACTION
    else -> null
  }

fun ApprovalGrantFacts.durableRefusal(
  mode: ApprovalExecutionMode,
  selected: ApprovalOption,
  request: ApprovalRequestRecord,
): ApprovalGrantRefusal? {
  val scope = request.authorityScope
  return onceRefusal(mode, selected) ?: when {
    actionClass != ApprovalActionClass.ORDINARY_TOOL && actionClass != ApprovalActionClass.ORDINARY_COMMAND ->
      ApprovalGrantRefusal.NOT_ORDINARY_ACTION
    !inputComplete -> ApprovalGrantRefusal.INCOMPLETE_INPUT
    scope == null || !scope.isComplete() || request.actionFingerprint != scope.actionFingerprint ->
      ApprovalGrantRefusal.INCOMPLETE_SCOPE
    else -> null
  }
}

fun ApprovalAuthorityScope.isComplete(): Boolean =
  profileIdentity.isNotBlank() && protocolRevision.isNotBlank() && actionKind.isNotBlank() &&
    workingDirectory.isNotBlank() && actionFingerprint.isNotBlank()

fun ApprovalRuleRecord.matches(request: ApprovalRequestRecord): Boolean {
  val scope = request.authorityScope ?: return false
  return revokedAt == null &&
    repositoryIdentity == request.repositoryIdentity &&
    provider == request.provider &&
    profileIdentity == scope.profileIdentity &&
    protocolRevision == scope.protocolRevision &&
    actionKind == scope.actionKind &&
    workingDirectory == scope.workingDirectory &&
    actionFingerprint == scope.actionFingerprint
}

fun ApprovalAuthorityScope.toRule(
  request: ApprovalRequestRecord,
  ruleId: String,
  createdAt: String,
): ApprovalRuleRecord =
  ApprovalRuleRecord(
    ruleId = ruleId,
    repositoryIdentity = request.repositoryIdentity,
    provider = request.provider,
    profileIdentity = profileIdentity,
    protocolRevision = protocolRevision,
    actionKind = actionKind,
    workingDirectory = workingDirectory,
    actionFingerprint = actionFingerprint,
    createdAt = createdAt,
  )
