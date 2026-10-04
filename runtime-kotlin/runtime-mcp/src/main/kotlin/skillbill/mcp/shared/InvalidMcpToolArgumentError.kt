package skillbill.mcp.shared

import skillbill.error.core.ShellContentContractException

internal class InvalidMcpToolArgumentError(
  val toolName: String,
  val argumentKey: String,
  val detail: String,
  cause: Throwable? = null,
) : ShellContentContractException(
    "MCP tool '${toolName.ifBlank { "<unknown>" }}' argument '$argumentKey': $detail",
    cause,
  )
