package skillbill.contracts.operationcard

const val OPERATOR_OPERATION_CARD_CONTRACT_VERSION = "1.0"

object OperationCardEnvironmentKeys {
  const val LAUNCH_ID = "SKILL_BILL_OPERATION_LAUNCH_ID"
}

object OperationCardExitCodes {
  const val AWAITING_CONFIRMATION = 4
}

object OperationCardOperationIds {
  const val VERIFY = "verify"
  const val PR_REVIEW_FIX = "pr-review-fix"
  val ALL: Set<String> = setOf(VERIFY, PR_REVIEW_FIX)
}

enum class OperationCardStatus(val wireValue: String) {
  AWAITING_CONFIRMATION("awaiting_confirmation"),
  CONFIRMING("confirming"),
  COMPLETED("completed"),
  REFUSED("refused"),
  FAILED("failed"),
  CANCELLED("cancelled"),
  SUPERSEDED("superseded"),
  CONSUMED_UNCERTAIN("consumed_uncertain"),
  ;

  val terminal: Boolean get() = this != AWAITING_CONFIRMATION && this != CONFIRMING

  companion object {
    fun fromWire(token: String): OperationCardStatus? = entries.firstOrNull { it.wireValue == token }
  }
}

data class OperationCardProjection(
  val contractVersion: String = OPERATOR_OPERATION_CARD_CONTRACT_VERSION,
  val launchId: String,
  val operationId: String,
  val invocationId: String,
  val repositoryIdentity: String,
  val databaseIdentity: String,
  val checkoutRoot: String,
  val branch: String,
  val sourceAnchors: Map<String, String>,
  val originalArguments: List<String>,
  val selectedAgent: String,
  val selectedProfile: String? = null,
  val token: String,
  val fullSummary: String,
  val proposalDetails: OperationCardDetails,
  val status: OperationCardStatus = OperationCardStatus.AWAITING_CONFIRMATION,
  val confirmationInvocationId: String? = null,
  val selection: String? = null,
  val evidence: String? = null,
)

sealed interface OperationCardDetails {
  val operationId: String

  data class Verify(
    val criteria: List<String>,
    val intake: String,
    val spec: String?,
    val target: String,
    val mode: String,
    val qa: String,
  ) : OperationCardDetails {
    override val operationId: String = OperationCardOperationIds.VERIFY
  }

  data class PrReviewFix(
    val mode: String,
    val pullRequest: String?,
    val push: String?,
    val replies: String?,
    val threads: List<OperationCardThread>,
  ) : OperationCardDetails {
    override val operationId: String = OperationCardOperationIds.PR_REVIEW_FIX
  }
}

data class OperationCardThread(
  val ordinal: String,
  val threadId: String,
  val options: List<OperationCardOption>,
)

data class OperationCardOption(
  val optionId: String,
  val label: String,
  val recommended: Boolean,
)

data class OperationLaunchContext(
  val launchId: String,
  val operationId: String,
  val repositoryIdentity: String,
  val databaseIdentity: String,
  val checkoutRoot: String,
  val branch: String,
  val originalArguments: List<String>,
  val selectedAgent: String,
  val selectedProfile: String? = null,
  val holderId: String,
  val retention: OperationLaunchRetention? = null,
)

enum class OperationLaunchRetention(val wireValue: String) {
  CALLER_OWNED("caller_owned"),
  MANAGED_HOLDER("managed_holder"),
  ;

  companion object {
    fun fromWire(value: String): OperationLaunchRetention? = entries.firstOrNull { it.wireValue == value }
  }
}
