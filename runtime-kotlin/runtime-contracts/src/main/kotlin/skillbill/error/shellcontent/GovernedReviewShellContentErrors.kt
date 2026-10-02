package skillbill.error.shellcontent

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.ShellContentContractException

enum class GovernedReviewFailureCode : RuntimeFailureCode {
  UNADDRESSED_FINDINGS_LEDGER_ABSENT,
}

class InvalidUnaddressedFindingsLedgerSchemaError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class GovernedReviewEvidenceTransportError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class InlineParallelReviewUnsupportedError(
  val requestedMode: String,
) : ShellContentContractException(
    "The parallel code-review runner runs only delegated reviews; requested mode '$requestedMode' " +
      "resolves to inline, which the inline review strategy runs.",
  )

class GovernedReviewLaunchCapabilityError(
  val provider: String,
  val capability: String,
) : ShellContentContractException(
    "Agent '$provider' cannot launch a governed review: missing capability '$capability'.",
  )
