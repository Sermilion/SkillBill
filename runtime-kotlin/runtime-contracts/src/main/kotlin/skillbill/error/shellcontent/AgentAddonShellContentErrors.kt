package skillbill.error.shellcontent

import skillbill.error.core.ShellContentContractException

class InvalidAgentAddonSchemaError(
  val sourceLabel: String,
  val reason: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "Agent add-on '${sourceLabel.ifBlank { "<unknown>" }}' fails schema validation: $reason",
    cause,
  )

class MissingAgentAddonDeclarationError(
  val slug: String,
  val expectedRoot: String,
) : ShellContentContractException(
    "Required agent add-on '$slug' was not found under '$expectedRoot'.",
  )

class InvalidAgentAddonDeliveryTargetError(
  val slug: String,
  val target: String,
  val reason: String,
) : ShellContentContractException("Agent add-on '$slug' has invalid delivery target '$target': $reason")

class AgentAddonPointerCollisionError(
  val pointerName: String,
) : ShellContentContractException("Agent add-on pointer '$pointerName' collides in the portable staging namespace.")

class InvalidAgentAddonSelectionError(
  message: String,
  cause: Throwable? = null,
) : ShellContentContractException(message, cause)

class AgentAddonSelectionDriftError(
  val slug: String,
  val sourceIdentity: String,
) : ShellContentContractException(
    "Selected agent add-on '$slug' changed at '$sourceIdentity'; start a new run to accept the new content.",
  )
