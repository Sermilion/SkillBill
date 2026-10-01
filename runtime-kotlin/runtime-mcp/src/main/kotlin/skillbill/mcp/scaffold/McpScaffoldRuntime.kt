package skillbill.mcp.scaffold

import skillbill.application.scaffold.model.ScaffoldInvocationArgs
import skillbill.application.scaffold.runScaffoldInvocation
import skillbill.contracts.JsonCodec
import skillbill.mcp.shared.McpComponent
import skillbill.mcp.shared.McpToolArguments
import skillbill.mcp.shared.McpToolPayloadKeys
import kotlin.coroutines.cancellation.CancellationException

internal fun newSkillScaffold(
  arguments: McpToolArguments,
  component: McpComponent,
): Map<String, Any?> {
  val payload = arguments.map(McpToolPayloadKeys.PAYLOAD)
  val dryRun = arguments.boolean(McpToolPayloadKeys.DRY_RUN)
  val orchestrated = arguments.boolean(McpToolPayloadKeys.ORCHESTRATED)
  val environment = component.resolvedEnvironmentContext
  return runCatching {
    val invocation =
      runScaffoldInvocation(
        component.scaffoldGateway,
        ScaffoldInvocationArgs(
          payloadText = JsonCodec.mapToJsonString(payload),
          invocationRepositoryRoot = environment.repositoryRoot,
          dryRun = dryRun,
          registerExternalSources = false,
          userHome = environment.userHome,
          environment = environment.environment,
          clock = component.clock,
        ),
      )
    scaffoldSuccessMap(
      sessionId = invocation.sessionId,
      payload = payload,
      result = invocation.scaffoldResult,
      dryRun = dryRun,
      orchestrated = orchestrated,
    )
  }.getOrElse { error ->
    when (error) {
      is CancellationException -> throw error
      is Exception ->
        scaffoldFailureMap(
          sessionId = "nss-unknown",
          payload = payload,
          orchestrated = orchestrated,
          error = error,
        )
      else -> throw error
    }
  }
}
