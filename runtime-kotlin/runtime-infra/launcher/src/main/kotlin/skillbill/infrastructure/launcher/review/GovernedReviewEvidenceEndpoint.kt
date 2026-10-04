package skillbill.infrastructure.launcher.review

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.review.GovernedReviewEvidenceContracts
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.GovernedReviewFailureCode
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.resolveUserHome
import skillbill.infrastructure.host.jvm.rollbackDeleteIfExists
import skillbill.infrastructure.launcher.mcp.GovernedReviewMcpConfigWriter
import skillbill.model.EnvironmentContext
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointBinder
import skillbill.ports.review.evidence.GovernedReviewEvidenceEndpointHandle
import skillbill.ports.review.evidence.ReviewEvidenceBroker
import skillbill.ports.review.model.GovernedReviewEvidenceEndpointDescriptor
import skillbill.ports.system.HostPlatformPort
import skillbill.review.context.model.execution.GovernedReviewJsonRpcArguments
import skillbill.review.context.model.hunk.ReviewExpansionRecord
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

private const val TOKEN_BYTES = 24
private const val UNIX_SOCKET_PATH_LIMIT = 103
private const val TEMP_SUFFIX_DIGITS = 20
private const val PER_LAUNCH_PREFIX = "skill-bill-review-evidence-"
private const val SOCKET_FILE_NAME = "evidence.sock"

@Inject
class UnixSocketGovernedReviewEvidenceEndpointBinder(
  private val environment: EnvironmentContext,
) : GovernedReviewEvidenceEndpointBinder {
  override fun bind(
    lane: String,
    broker: ReviewEvidenceBroker,
    onEvidenceRead: (() -> Unit)?,
  ): GovernedReviewEvidenceEndpointHandle =
    GovernedReviewEvidenceEndpoint.bind(
      lane,
      broker,
      bridgeCommand(environment.environment, environment.userHome),
      onEvidenceRead,
    )
}

internal fun bridgeCommand(
  environment: Map<String, String>,
  userHome: Path,
): List<String> {
  val configured = environment["SKILL_BILL_RUNTIME_MCP_BIN"]?.takeIf(String::isNotBlank)
  val home =
    userHome.takeUnless { it.toString().isBlank() }
      ?: environment["HOME"]?.takeIf(String::isNotBlank)?.let(Path::of)
      ?: resolveUserHome(null)
  val bin =
    configured?.let(Path::of)
      ?: home.resolve(".skill-bill").resolve("runtime").resolve("runtime-mcp").resolve("bin").resolve("runtime-mcp")
  if (!Files.isExecutable(bin)) {
    throw SkillBillRuntimeException(
      GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
      "Governed review evidence bridge binary '$bin' is missing or not executable.",
    )
  }
  return listOf(bin.toAbsolutePath().normalize().toString())
}

class GovernedReviewEvidenceEndpoint private constructor(
  override val descriptor: GovernedReviewEvidenceEndpointDescriptor,
  private val broker: ReviewEvidenceBroker,
  private val channel: ServerSocketChannel,
  private val directory: Path,
  private val onEvidenceRead: (() -> Unit)?,
) : GovernedReviewEvidenceEndpointHandle {
  private val issuedExpansions = ConcurrentHashMap<String, ReviewExpansionRecord>()

  @Volatile
  private var closed = false
  private val acceptor =
    thread(name = "skill-bill-review-evidence-${descriptor.lane}", isDaemon = true) {
      acceptLoop()
    }

  override fun close() {
    if (closed) return
    closed = true
    runCatching { channel.close() }
    acceptor.interrupt()
    deleteDirectory()
  }

  private fun deleteDirectory() {
    runCatching { rollbackDeleteIfExists(descriptor.socketPath) }
    runCatching { rollbackDeleteIfExists(descriptor.mcpConfigPath) }
    runCatching { rollbackDeleteIfExists(GovernedReviewMcpConfigWriter.tomlConfigPath(descriptor.mcpConfigPath)) }
    val cursorConfig = GovernedReviewMcpConfigWriter.cursorProjectConfigPath(descriptor.mcpConfigPath)
    runCatching { rollbackDeleteIfExists(cursorConfig) }
    runCatching { rollbackDeleteIfExists(cursorConfig.parent) }
    runCatching { rollbackDeleteIfExists(directory) }
  }

  private fun acceptLoop() {
    while (!closed) {
      val connection =
        try {
          channel.accept()
        } catch (_: IOException) {
          return
        } ?: return
      runCatching { connection.use { serve(it) } }
    }
  }

  private fun serve(connection: SocketChannel) {
    val reader = Channels.newInputStream(connection).bufferedReader()
    val writer = Channels.newOutputStream(connection).bufferedWriter()
    if (!authenticated(reader.readLine())) return
    writer.appendLine(JsonCodec.mapToJsonString(linkedMapOf("jsonrpc" to "2.0", "result" to "ok")))
    writer.flush()
    while (!closed) {
      val line = reader.readLine() ?: return
      writer.appendLine(handleFrame(line))
      writer.flush()
    }
  }

  private fun authenticated(handshake: String?): Boolean {
    val frame = handshake?.let(JsonCodec::parseObjectOrNull) ?: return false
    val params = JsonCodec.anyToStringAnyMap(frame["params"]?.let(JsonCodec::jsonElementToValue)).orEmpty()
    val presented = params["token"]?.toString().orEmpty()
    return MessageDigest.isEqual(
      presented.toByteArray(Charsets.UTF_8),
      descriptor.token.toByteArray(Charsets.UTF_8),
    )
  }

  internal fun handleFrame(line: String): String {
    val frame =
      JsonCodec.parseObjectOrNull(line)
        ?: return governedReviewEvidenceErrorResponse(
          null,
          GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
          "Malformed governed evidence frame.",
        )
    val id = frame["id"]?.let(JsonCodec::jsonElementToValue)
    val method = frame["method"]?.let(JsonCodec::jsonElementToValue)?.toString().orEmpty()
    return when (method) {
      "tools/list" -> governedReviewEvidenceToolsListResponse(id)
      "tools/call" -> {
        val params = JsonCodec.anyToStringAnyMap(frame["params"]?.let(JsonCodec::jsonElementToValue)).orEmpty()
        val name = params["name"]?.toString().orEmpty()
        val arguments = JsonCodec.anyToStringAnyMap(params["arguments"]).orEmpty()
        dispatch(id, name, arguments)
      }
      else ->
        governedReviewEvidenceErrorResponse(
          id,
          GOVERNED_REVIEW_EVIDENCE_JSON_RPC_METHOD_NOT_FOUND,
          "Method not found: $method",
        )
    }
  }

  private fun dispatch(
    id: Any?,
    name: String,
    arguments: Map<String, Any?>,
  ): String =
    try {
      when (name) {
        GovernedReviewEvidenceContracts.READ_EVIDENCE ->
          governedReviewEvidenceToolResponse(id, read(arguments))
        GovernedReviewEvidenceContracts.REQUEST_EXPANSION ->
          governedReviewEvidenceToolResponse(id, expand(arguments))
        else ->
          governedReviewEvidenceErrorResponse(
            id,
            GOVERNED_REVIEW_EVIDENCE_JSON_RPC_METHOD_NOT_FOUND,
            "Unknown governed operation: $name",
          )
      }
    } catch (error: CancellationException) {
      throw error
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      governedReviewEvidenceErrorResponse(
        id,
        GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
        error.message.orEmpty(),
      )
    } catch (error: IOException) {
      governedReviewEvidenceErrorResponse(
        id,
        GOVERNED_REVIEW_EVIDENCE_JSON_RPC_INVALID_PARAMS,
        error.message.orEmpty(),
      )
    }

  private fun read(arguments: Map<String, Any?>): Map<String, Any?> {
    val request =
      GovernedReviewEvidenceCodec.readRequest(
        descriptor.lane,
        GovernedReviewJsonRpcArguments.from(arguments),
        issuedExpansions::get,
      )
    val payload = GovernedReviewEvidenceCodec.batchResultPayload(broker.readBatch(request)).toPayload()
    onEvidenceRead?.invoke()
    return payload
  }

  private fun expand(arguments: Map<String, Any?>): Map<String, Any?> {
    val record =
      broker.authorizeExpansion(
        GovernedReviewEvidenceCodec.expansionRequest(descriptor.lane, GovernedReviewJsonRpcArguments.from(arguments)),
      )
    if (record.authorized) issuedExpansions[record.expansionId] = record
    return GovernedReviewEvidenceCodec.expansionRecordPayload(record).toPayload()
  }

  companion object {
    fun bind(
      lane: String,
      broker: ReviewEvidenceBroker,
      bridgeCommand: List<String>,
      onEvidenceRead: (() -> Unit)? = null,
    ): GovernedReviewEvidenceEndpoint {
      val directory = privateDirectory()
      val socketPath = directory.resolve(SOCKET_FILE_NAME)
      val token = newToken()
      val channel = openGovernedReviewChannel(lane, socketPath, directory)
      var failure: Throwable? = null
      var endpoint: GovernedReviewEvidenceEndpoint? = null
      try {
        val configPath =
          GovernedReviewMcpConfigWriter.write(
            configPath = directory.resolve("mcp.json"),
            bridgeCommand = bridgeCommand,
            socketPath = socketPath,
            token = token,
            lane = lane,
          )
        endpoint =
          GovernedReviewEvidenceEndpoint(
            GovernedReviewEvidenceEndpointDescriptor(lane, socketPath, configPath, token),
            broker,
            channel,
            directory,
            onEvidenceRead,
          )
      } catch (error: CancellationException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      } catch (error: IOException) {
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        rollbackGovernedReviewBindArtifacts(channel, socketPath, directory)
        failure = error
      }
      failure?.let { throw it }
      return endpoint!!
    }

    private fun openGovernedReviewChannel(
      lane: String,
      socketPath: Path,
      directory: Path,
    ): ServerSocketChannel {
      var failure: Throwable? = null
      var channel: ServerSocketChannel? = null
      try {
        channel =
          ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(socketPath))
      } catch (error: CancellationException) {
        failure = error
      } catch (error: IOException) {
        runCatching { rollbackDeleteIfExists(directory) }
        failure =
          SkillBillRuntimeException(
            GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
            "Failed to bind the governed review evidence endpoint for lane '$lane'.",
            error,
          )
      } catch (error: SkillBillRuntimeException) {
        error.rethrowUnless(error.isShellContentContractFailure())
        runCatching { rollbackDeleteIfExists(directory) }
        failure =
          SkillBillRuntimeException(
            GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
            "Failed to bind the governed review evidence endpoint for lane '$lane'.",
            error,
          )
      }
      failure?.let { throw it }
      return channel!!
    }

    private fun rollbackGovernedReviewBindArtifacts(
      channel: ServerSocketChannel,
      socketPath: Path,
      directory: Path,
    ) {
      runCatching { channel.close() }
      runCatching { rollbackDeleteIfExists(socketPath) }
      runCatching { rollbackDeleteIfExists(directory.resolve("mcp.json")) }
      runCatching {
        rollbackDeleteIfExists(
          GovernedReviewMcpConfigWriter.tomlConfigPath(directory.resolve("mcp.json")),
        )
      }
      val cursorConfig = directory.resolve(".cursor").resolve("mcp.json")
      runCatching { rollbackDeleteIfExists(cursorConfig) }
      runCatching { rollbackDeleteIfExists(directory.resolve(".cursor").resolve("cli.json")) }
      runCatching { rollbackDeleteIfExists(cursorConfig.parent) }
      runCatching { rollbackDeleteIfExists(directory) }
    }

    private fun privateDirectory(): Path =
      try {
        Files.createTempDirectory(
          perLaunchRoot(),
          PER_LAUNCH_PREFIX,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
        )
      } catch (error: IOException) {
        throw SkillBillRuntimeException(
          GovernedReviewFailureCode.EVIDENCE_TRANSPORT,
          "Failed to create the per-launch governed review directory.",
          error,
        )
      }

    internal fun perLaunchRoot(hostPlatform: HostPlatformPort = JdkHostPlatformPort): Path {
      val configured = hostPlatform.resolveTemporaryDirectory()
      if (socketPathFits(configured)) return configured
      val shortest = Path.of("/tmp")
      return if (Files.isDirectory(shortest) && socketPathFits(shortest)) shortest else configured
    }

    private fun socketPathFits(root: Path): Boolean =
      root
        .resolve(PER_LAUNCH_PREFIX + "0".repeat(TEMP_SUFFIX_DIGITS))
        .resolve(SOCKET_FILE_NAME)
        .toString()
        .toByteArray(Charsets.UTF_8)
        .size <= UNIX_SOCKET_PATH_LIMIT

    private fun newToken(): String =
      ByteArray(TOKEN_BYTES)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
  }
}
