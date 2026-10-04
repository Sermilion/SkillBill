package skillbill.mcp.shared

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

internal enum class McpToolArgumentFailureCode : RuntimeFailureCode {
  INVALID,
}

internal fun invalidMcpToolArgument(
  toolName: String,
  argumentKey: String,
  detail: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    McpToolArgumentFailureCode.INVALID,
    "MCP tool '${toolName.ifBlank { "<unknown>" }}' argument '$argumentKey': $detail",
    cause,
  )
