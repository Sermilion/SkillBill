package skillbill.infrastructure.launcher.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.infrastructure.launcher.process.launch.captureLaunchResourceOutcome
import skillbill.ports.agentrun.model.SkillRunRequest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.PosixFilePermissions.asFileAttribute

internal object OpenCodeReviewConfigWriter {
  private val mapper = ObjectMapper()

  fun write(
    path: Path,
    request: SkillRunRequest,
  ) {
    val endpoint = requireNotNull(request.reviewEvidenceEndpoint).descriptor
    requireNotNull(request.reviewEvidenceBroker) { "OpenCode governed review requires its lane evidence broker." }
    val binding =
      mapper.readTree(Files.readAllBytes(endpoint.mcpConfigPath))
        ?.get(OpenCodeReviewConfigKeys.MCP_SERVERS)?.get(GovernedReviewEvidenceContracts.SERVER_NAME)
        ?: transportFailure("The lane evidence endpoint has no owned MCP configuration.")
    val endpointEnvironment =
      binding.get(OpenCodeReviewConfigKeys.INPUT_ENVIRONMENT)
        ?.takeIf { it.isObject }
        ?: transportFailure("The lane evidence endpoint has no valid bound environment.")
    val expectedEnvironment =
      mapOf(
        GovernedReviewEvidenceContracts.SOCKET_ENV to endpoint.socketPath.toString(),
        GovernedReviewEvidenceContracts.TOKEN_ENV to endpoint.token,
        GovernedReviewEvidenceContracts.LANE_ENV to endpoint.lane,
      )
    val bindingMismatch =
      expectedEnvironment.any { (key, value) ->
        endpointEnvironment.get(key)?.takeIf { it.isTextual }?.asText() != value
      }
    if (bindingMismatch) {
      transportFailure("The lane evidence endpoint binding does not match its descriptor.")
    }
    val command =
      binding.get(OpenCodeReviewConfigKeys.COMMAND)
        ?.takeIf { it.isTextual }?.asText()?.takeIf(String::isNotBlank)
        ?: transportFailure("The lane evidence endpoint command is unavailable.")
    val argsNode = binding.get(OpenCodeReviewConfigKeys.ARGS)
    if (argsNode != null && (!argsNode.isArray || argsNode.any { !it.isTextual })) {
      transportFailure("The lane evidence endpoint arguments are malformed.")
    }
    val args = argsNode?.map { it.asText() }.orEmpty()
    val server = GovernedReviewEvidenceContracts.SERVER_NAME
    val tools = GovernedReviewEvidenceContracts.OPERATIONS.map { operation -> qualifiedToolName(server, operation) }
    val rules =
      linkedMapOf(OpenCodeReviewConfigKeys.DENY_ALL_TOOL_KEY to OpenCodeReviewConfigKeys.DENY) +
        tools.associateWith { OpenCodeReviewConfigKeys.ALLOW }
    val config =
      linkedMapOf<String, Any?>(
        OpenCodeReviewConfigKeys.PERMISSION to rules,
        OpenCodeReviewConfigKeys.MCP to
          mapOf(
            server to
              mapOf(
                OpenCodeReviewConfigKeys.TYPE to "local",
                OpenCodeReviewConfigKeys.COMMAND to listOf(command) + args,
                OpenCodeReviewConfigKeys.ENVIRONMENT to expectedEnvironment,
              ),
          ),
      ).apply { request.modelOverride?.let { put(OpenCodeReviewConfigKeys.MODEL, it) } }
    Files.createDirectories(path.parent)
    writeOwnerOnly(path, mapper.writeValueAsBytes(config))
  }
}

private fun transportFailure(message: String): Nothing =
  throw SkillBillRuntimeException(
    GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
    message,
  )

internal fun qualifiedToolName(
  server: String,
  operation: String,
): String = "${sanitizeToolNamePart(server)}_${sanitizeToolNamePart(operation)}"

private fun sanitizeToolNamePart(value: String): String = value.replace(Regex("[^A-Za-z0-9_-]"), "_")

internal fun writeOwnerOnly(
  path: Path,
  bytes: ByteArray,
) {
  val permissions = PosixFilePermissions.fromString("rw-------")
  val temporary = Files.createTempFile(path.parent, ".opencode-review-", ".tmp", asFileAttribute(permissions))
  captureLaunchResourceOutcome {
    Files.write(temporary, bytes)
    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
  }.getOrElse { failure ->
    captureLaunchResourceOutcome {
      Files.deleteIfExists(temporary)
    }.getOrElse { cleanupFailure ->
      failure.addSuppressed(cleanupFailure)
    }
    throw failure
  }
}
