package dev.skillbill.desktop.core.data.usage.codex

import dev.skillbill.desktop.core.common.AppDispatchers
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import dev.skillbill.desktop.core.data.chat.BoundedProcessStart
import dev.skillbill.desktop.core.data.chat.BoundedProtocolConnection
import dev.skillbill.desktop.core.data.chat.BoundedProtocolInput
import dev.skillbill.desktop.core.data.chat.BoundedProtocolLauncher
import dev.skillbill.desktop.core.data.chat.ChatCommand
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableCode
import dev.skillbill.desktop.core.domain.usage.model.UsageUnavailableReason
import dev.skillbill.desktop.core.domain.usage.model.UsageResult
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.data.usage.codex.model.CodexVerifiedSubjectConfirmation
import dev.skillbill.desktop.core.data.usage.codex.model.CodexVerifiedUsageObservation
import dev.skillbill.desktop.core.data.usage.UsageDiagnosticCategories
import dev.skillbill.desktop.core.data.usage.UsageDiagnosticFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import dev.skillbill.desktop.core.domain.usage.model.OmissionSemantics
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageAuthMode
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageFreshness
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsageObservation
import dev.skillbill.desktop.core.domain.usage.model.UsageObservationSubject
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshFamily
import dev.skillbill.desktop.core.domain.usage.model.UsageRefreshState
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageIdentity
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageSubject
import dev.skillbill.desktop.core.domain.usage.model.VerifiedUsageWorkspaceScope
import kotlinx.serialization.json.booleanOrNull
import kotlin.time.Clock
import kotlin.time.Instant

data class CodexAcquisitionContext(
  val profile: UsageProfileLookup,
  val environment: Map<String, String>,
  val reservationId: String,
  val emit: suspend (CodexAcquisitionEvent) -> Unit,
)

sealed interface CodexAcquisitionEvent {
  val reservationId: String
  data class AccountInvalidated(override val reservationId: String) : CodexAcquisitionEvent
  data class SubjectConfirmed(val evidence: CodexVerifiedSubjectConfirmation) : CodexAcquisitionEvent {
    override val reservationId: String get() = evidence.reservationId
  }
  data class VerifiedObservation(val evidence: CodexVerifiedUsageObservation) : CodexAcquisitionEvent {
    override val reservationId: String get() = evidence.reservationId
  }
}

internal data class CodexRateLimitsCandidate(
  override val reservationId: String,
  val buckets: List<CodexQuotaBucket>,
) : CodexAcquisitionEvent

fun interface CodexUsageReader {
  suspend fun read(context: CodexAcquisitionContext): UsageResult

  val refreshCapability: UsageRefreshCapability
    get() = UsageRefreshCapability.ProbeOnly
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CodexUsageCollector(
  private val host: HostEnvironment,
  private val launcher: BoundedProtocolLauncher,
  private val diagnostics: Diagnostics,
  private val dispatchers: AppDispatchers,
  private val clock: Clock = Clock.System,
) : CodexUsageReader {
  override val refreshCapability: UsageRefreshCapability = UsageRefreshCapability.Pull(UsageRefreshFamily.CodexSnapshot)

  override suspend fun read(context: CodexAcquisitionContext): UsageResult = withContext(dispatchers.io) {
    val profile = context.profile
    if (profile.provider != UsageProvider.Codex) return@withContext protocolUnsupported(1)
    var connection: BoundedProtocolConnection? = null
    try {
      val startup = withTimeoutOrNull(15.seconds) {
        val commandName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "codex" else
          resolveUnixExecutable(context.environment["PATH"], host.userHome)
        if (commandName == null) StartupResult.Failed else {
          currentCoroutineContext().ensureActive()
          val command = ChatCommand(
            argv = listOf(commandName, "app-server", "--listen", "stdio://"),
            workingDirectory = host.userHome,
            environment = context.environment,
            stdin = "",
          )
          when (val start = launcher.start(command)) {
            is BoundedProcessStart.Failed -> StartupResult.Failed
            is BoundedProcessStart.Started -> {
              connection = start.connection
              val response = request(start.connection, "initialize", buildJsonObject {
                put("clientInfo", buildJsonObject { put("name", "skill-bill-desktop"); put("version", "1") })
                put("capabilities", buildJsonObject { put("experimentalApi", true) })
              }, context) { 1 }
              if (response == null) StartupResult.Failed
              else if (response == RpcResponse.Invalidated) StartupResult.Invalidated
              else if (response == RpcResponse.Invalid) StartupResult.Unsupported
              else if (response.error != null || response.result !is JsonObject) StartupResult.Unsupported
              else if (!start.connection.sendLine(notification("initialized", buildJsonObject { }))) StartupResult.Failed
              else StartupResult.Ready(start.connection)
            }
          }
        }
      } ?: return@withContext transient()
      when (startup) {
        StartupResult.Failed -> transient()
        StartupResult.Invalidated -> invalidated()
        StartupResult.Unsupported -> protocolUnsupported(2)
        is StartupResult.Ready -> readAccount(startup.connection, context)
      }
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: IOException) {
      transient()
    } catch (_: Exception) {
      protocolUnsupported(3)
    } finally {
      try {
        connection?.close()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        diagnostics.recordRequest(UsageDiagnosticCategories.SOURCE_CLEANUP_FAILED, mapOf(
          UsageDiagnosticFields.REASON_CODE to 1,
        ))
      }
    }
  }

  private suspend fun readAccount(connection: BoundedProtocolConnection, context: CodexAcquisitionContext): UsageResult {
    var requestSequence = 1
    val account = readRequest(connection, "account/read", buildJsonObject { put("refreshToken", false) }, context) { ++requestSequence } ?: return transient()
    if (account == RpcResponse.Invalidated) return invalidated()
    if (account.error != null) return protocolUnsupported(4)
    val accountObject = account.result as? JsonObject ?: return parseRejected(1)
    val accountValue = accountObject["account"] ?: return parseRejected(2)
    if (accountValue == JsonNull) {
      diagnostics.record("usage.codex.sign_in_required")
      return UsageResult.SignInRequired(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
    }
    val accountRecord = accountValue as? JsonObject ?: return parseRejected(3)
    val accountType = (accountRecord["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (accountType == "apiKey") {
      diagnostics.record("usage.codex.api_key_unsupported")
      return UsageResult.Unsupported(UsageUnavailableReason(UsageUnavailableCode.UnsupportedPersonalSnapshot))
    }
    if (accountType != "chatgpt") return protocolUnsupported(5)

    val limits = readRequest(connection, "account/rateLimits/read", buildJsonObject { }, context) { ++requestSequence } ?: return transient()
    if (limits == RpcResponse.Invalidated) return invalidated()
    if (limits.error != null || limits.result == null) return protocolUnsupported(6)
    val quota = CodexUsageProtocol.parseQuota(limits.result) as? CodexQuotaParse.Valid ?: return parseRejected(4)
    val limitsRecord = limits.result as? JsonObject
    val identity = limitsRecord?.string("accountId") ?: accountRecord.string("email")
    if (identity == null) {
      diagnostics.record("usage.codex.identity_unavailable")
      return UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))
    }
    val workspace = accountRecord.string("workspaceName")
      ?.let { VerifiedUsageWorkspaceScope.Workspace(it) }
      ?: VerifiedUsageWorkspaceScope.VerifiedNonWorkspace
    val subject = VerifiedUsageSubject(context.profile, VerifiedUsageIdentity(identity, workspace), UsageAuthMode.Account)
    val observedAt = clock.now()
    val source = UsageSource(UsageProvider.Codex, UsageCapability.NumericSnapshot)
    val meters = quota.buckets.flatMap { it.meters(observedAt, source) }
    if (meters.isEmpty()) {
      return UsageResult.EmptySuccess(subject, source, observedAt, OmissionSemantics.OmittedFieldsAreRemoved, context.reservationId)
    }
    val permission = when ((limitsRecord?.get("ordinaryUsageAllowed") as? JsonPrimitive)?.booleanOrNull) {
      true -> RequestPermission.Allowed
      false -> RequestPermission.Rejected
      null -> RequestPermission.Unknown
    }
    return UsageResult.Snapshot(
      UsageObservation(
        subject = UsageObservationSubject.Account(subject),
        collectionTime = observedAt,
        sourceObservationTime = null,
        source = source,
        freshness = UsageFreshness.Current,
        refreshState = UsageRefreshState.Complete,
        meters = meters,
        requestPermission = permission,
      ),
      OmissionSemantics.OmittedFieldsAreRemoved,
      context.reservationId,
    )
  }

  private fun CodexQuotaBucket.meters(observedAt: Instant, source: UsageSource): List<UsageMeter> =
    listOfNotNull(primary, secondary).mapNotNull { window ->
      val durationSeconds = window.durationMinutes * 60
      UsageMeter.create(
        bucketIdentity = "$limitId:${window.durationMinutes}",
        label = windowLabel(window.durationMinutes),
        scope = if (limitId == DEFAULT_LIMIT_ID) UsageMeterScope.General else UsageMeterScope.Pool(limitName ?: limitId),
        observedAt = observedAt,
        resetAt = window.resetsAtUnixSeconds?.let(Instant::fromEpochSeconds),
        period = UsagePeriod.Rolling.create(durationSeconds) ?: UsagePeriod.Unknown,
        value = UsageValue.PercentageUsed.create(window.usedPercent) ?: return@mapNotNull null,
        sourceOwner = source,
      )
    }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)

  private suspend fun readRequest(
    connection: BoundedProtocolConnection,
    method: String,
    params: JsonObject,
    context: CodexAcquisitionContext,
    nextId: () -> Int,
  ): RpcResponse? = withTimeoutOrNull(10.seconds) { request(connection, method, params, context, nextId) }

  private suspend fun request(
    connection: BoundedProtocolConnection,
    method: String,
    params: JsonObject,
    context: CodexAcquisitionContext,
    nextId: () -> Int,
  ): RpcResponse? {
    connection.beginResponseBudget()
    try {
      val id = nextId()
      if (!connection.sendLine(requestLine(id, method, params))) return null
      while (true) {
        when (val input = connection.receive()) {
          is BoundedProtocolInput.Failed -> {
            if (input.kind == BoundedProtocolInput.Kind.Transport) return null
            diagnostics.recordRequest(UsageDiagnosticCategories.INPUT_REJECTED, mapOf(
              UsageDiagnosticFields.REASON_CODE to input.kind.ordinal,
            ))
            return RpcResponse.Invalid
          }
          BoundedProtocolInput.Exited -> return null
          is BoundedProtocolInput.Line -> when (val frame = classify(input.text, id)) {
            is Frame.Response -> return frame.value
            is Frame.Invalid -> {
              diagnostics.recordRequest(UsageDiagnosticCategories.PARSE_REJECTED, mapOf(
                UsageDiagnosticFields.REASON_CODE to frame.reasonCode,
              ))
              return RpcResponse.Invalid
            }
            is Frame.Notification -> when (frame.method) {
              // Codex announces the current account on every startup. Each read uses a fresh process, so the
              // announcement cannot describe a change during this read and the account/read response is authoritative.
              "account/updated" -> Unit
              "account/rateLimits/updated" -> {
                val candidate = frame.params?.let(CodexUsageProtocol::parseQuota)
                if (candidate !is CodexQuotaParse.Valid) {
                  diagnostics.recordRequest(UsageDiagnosticCategories.PARSE_REJECTED, mapOf(
                    UsageDiagnosticFields.REASON_CODE to 5,
                  ))
                  return RpcResponse.Invalid
                }
                context.emit(CodexRateLimitsCandidate(context.reservationId, candidate.buckets))
              }
            }
          }
        }
      }
    } finally {
      connection.endResponseBudget()
    }
  }

  private fun classify(line: String, pendingId: Int): Frame {
    val envelope = CodexUsageProtocol.parseLine(line) ?: return Frame.Invalid(1)
    val version = envelope["jsonrpc"]
    if (version != null && (version as? JsonPrimitive)?.content != "2.0") return Frame.Invalid(2)
    val id = envelope["id"]
    if (id != null) {
      if (id != JsonPrimitive(pendingId) || "method" in envelope || "params" in envelope) return Frame.Invalid(3)
      val hasResult = "result" in envelope
      val hasError = "error" in envelope
      if (hasResult == hasError) return Frame.Invalid(4)
      val error = envelope["error"]
      if (error != null && (error as? JsonObject)?.let {
          (it["code"] as? JsonPrimitive)?.takeUnless { code -> code.isString }?.intOrNull != null &&
            (it["message"] as? JsonPrimitive)?.isString == true
        } != true) return Frame.Invalid(5)
      return Frame.Response(RpcResponse(envelope["result"], error))
    }
    if ("result" in envelope || "error" in envelope) return Frame.Invalid(6)
    val method = (envelope["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Frame.Invalid(7)
    val paramsElement = envelope["params"]
    val params = paramsElement as? JsonObject
    if (paramsElement != null && params == null) return Frame.Invalid(8)
    if (method in setOf("account/updated", "account/rateLimits/updated") && params == null) return Frame.Invalid(9)
    return Frame.Notification(method, params)
  }

  private fun requestLine(id: Int, method: String, params: JsonObject) = buildJsonObject {
    put("id", id); put("method", method); put("params", params)
  }.toString()

  private fun notification(method: String, params: JsonObject) = buildJsonObject {
    put("method", method); put("params", params)
  }.toString()

  private fun resolveUnixExecutable(path: String?, workingDirectory: String): String? {
    if (path == null) return null
    return path.split(java.io.File.pathSeparatorChar).firstNotNullOfOrNull { entry ->
      val directory = if (entry.isEmpty()) Path.of(workingDirectory) else Path.of(entry).let { if (it.isAbsolute) it else Path.of(workingDirectory).resolve(it) }
      val candidate = directory.resolve("codex")
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) candidate.toAbsolutePath().normalize().toString() else null
    }
  }

  private fun transient(): UsageResult {
    diagnostics.record("usage.codex.transient_failure")
    return UsageResult.TransientFailure(UsageUnavailableReason(UsageUnavailableCode.TransientFailure))
  }

  private fun protocolUnsupported(reasonCode: Int): UsageResult {
    diagnostics.record("usage.codex.protocol_unavailable")
    diagnostics.recordRequest(UsageDiagnosticCategories.SOURCE_UNSUPPORTED, mapOf(UsageDiagnosticFields.REASON_CODE to reasonCode))
    return UsageResult.Unsupported(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
  }

  private fun parseRejected(reasonCode: Int): UsageResult {
    diagnostics.record("usage.codex.protocol_unavailable")
    diagnostics.recordRequest(UsageDiagnosticCategories.PARSE_REJECTED, mapOf(UsageDiagnosticFields.REASON_CODE to reasonCode))
    return UsageResult.Unsupported(UsageUnavailableReason(UsageUnavailableCode.ProviderUnavailable))
  }

  private fun invalidated() = UsageResult.Unavailable(UsageUnavailableReason(UsageUnavailableCode.ProfileUnverified))

  private companion object {
    const val DEFAULT_LIMIT_ID = "codex"

    fun windowLabel(minutes: Long): String = when {
      minutes == 300L -> "5 hour"
      minutes == 7L * 24 * 60 -> "Weekly"
      minutes in 28L * 24 * 60..31L * 24 * 60 -> "Monthly"
      minutes % (24 * 60) == 0L -> "${minutes / (24 * 60)} day"
      minutes % 60 == 0L -> "${minutes / 60} hour"
      else -> "$minutes minute"
    }
  }

  private sealed interface StartupResult {
    data object Failed : StartupResult
    data object Invalidated : StartupResult
    data object Unsupported : StartupResult
    data class Ready(val connection: BoundedProtocolConnection) : StartupResult
  }

  private sealed interface Frame {
    data class Response(val value: RpcResponse) : Frame
    data class Notification(val method: String, val params: JsonObject?) : Frame
    data class Invalid(val reasonCode: Int) : Frame
  }

  private data class RpcResponse(val result: JsonElement?, val error: JsonElement?) {
    companion object { val Invalid = RpcResponse(null, JsonPrimitive("invalid")); val Invalidated = RpcResponse(null, JsonPrimitive("invalidated")) }
  }

}
