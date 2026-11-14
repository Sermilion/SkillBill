package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

fun interface DevinUsageReader {
  /** Announces credential and organization changes before reading numbers, so old account data can be cleared. */
  suspend fun read(profile: UsageProfileLookup, onIdentity: (String?) -> Unit): UsageSnapshotRead
}

class DevinUsageCollector internal constructor(
  private val host: HostEnvironment,
  private val clock: Clock,
  private val diagnostics: Diagnostics,
  private val openConnection: (URI) -> HttpURLConnection,
) : DevinUsageReader {
  @Inject
  constructor(host: HostEnvironment, clock: Clock, diagnostics: Diagnostics) :
    this(host, clock, diagnostics, { it.toURL().openConnection() as HttpURLConnection })

  override suspend fun read(profile: UsageProfileLookup, onIdentity: (String?) -> Unit): UsageSnapshotRead = try {
    runInterruptible(Dispatchers.IO) {
      val credentials = credentials()
      onIdentity(credentials?.identity)
      if (credentials == null) return@runInterruptible UsageSnapshotRead.SignInRequired
      val url = URI("https://app.devin.ai/api/${credentials.organization}/billing/quota/usage")
      val connection = openConnection(url)
      val result = try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer ${credentials.token}")
        connection.setRequestProperty("x-cog-org-id", credentials.organization)
        connection.setRequestProperty("Accept", "application/json")
        when (connection.responseCode) {
          200 -> connection.inputStream.use { stream ->
            val bytes = stream.readNBytes(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) UsageSnapshotRead.Failed
            else parseDevinUsage(bytes.decodeToString(), clock.now())
          }
          401, 403 -> UsageSnapshotRead.SignInRequired
          429 -> UsageSnapshotRead.Throttled(connection.getHeaderField("Retry-After")
            ?.toLongOrNull()?.takeIf { it in 0..86_400 }?.let { clock.now() + it.seconds })
          else -> UsageSnapshotRead.Failed
        }
      } finally {
        connection.disconnect()
      }
      val current = credentials()
      if (current?.identity != credentials.identity) {
        onIdentity(current?.identity)
        UsageSnapshotRead.Failed
      } else result
    }.also { if (it !is UsageSnapshotRead.Success) diagnostics.record("usage.devin_read_unavailable") }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("usage.devin_read_failed")
    UsageSnapshotRead.Failed
  }

  private fun credentials(): DevinCredentials? = try {
    loadCredentials()
  } catch (_: Exception) {
    diagnostics.record("usage.devin_credentials_unreadable")
    null
  }

  private fun loadCredentials(): DevinCredentials? {
    val dataHome = host.variables["XDG_DATA_HOME"]?.takeIf(String::isNotBlank)
      ?: "${host.userHome}/.local/share"
    val configHome = host.variables["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)
      ?: "${host.userHome}/.config"
    val credentialsFile = Path.of(dataHome, "devin", "credentials.toml")
    val configFile = Path.of(configHome, "devin", "config.json")
    if (!Files.exists(credentialsFile) || !Files.exists(configFile)) return null
    val token = readDevinToken(readBounded(credentialsFile)) ?: return null
    val root = Json.parseToJsonElement(readBounded(configFile)).jsonObject
    val org = root[DevinUsageKeys.DEVIN]?.jsonObject?.get(DevinUsageKeys.ORG_ID)
      ?.jsonPrimitive?.contentOrNull?.takeIf { it.matches(Regex("org[-_][A-Za-z0-9_-]+")) } ?: return null
    val identity = MessageDigest.getInstance("SHA-256").digest("$org\u0000$token".toByteArray())
      .joinToString("") { "%02x".format(it) }
    return DevinCredentials(token, org, identity)
  }

  private fun readBounded(path: Path): String = Files.newInputStream(path).use { stream ->
    val bytes = stream.readNBytes(MAX_BYTES + 1)
    require(bytes.size <= MAX_BYTES)
    bytes.decodeToString()
  }

  private class DevinCredentials(val token: String, val organization: String, val identity: String)

  private companion object { const val MAX_BYTES = 262_144 }
}

/** Reads the CLI's flat TOML token without logging or returning unrelated credential fields. */
internal fun readDevinToken(body: String): String? {
  val line = body.lineSequence().firstOrNull { Regex("^\\s*${DevinUsageKeys.API_KEY}\\s*=").containsMatchIn(it) }
    ?: return null
  val raw = line.substringAfter('=').trim()
  val quoted = Regex("^(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')\\s*(?:#.*)?$").matchEntire(raw)?.groupValues?.get(1) ?: return null
  val token = if (quoted.startsWith("'")) quoted.drop(1).dropLast(1)
    else Json.parseToJsonElement(quoted).jsonPrimitive.contentOrNull
  return token?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
}

internal object DevinUsageKeys {
  const val API_KEY = "windsurf_api_key"
  const val DEVIN = "devin"
  const val ORG_ID = "org_id"
  const val IS_QUOTA_PLAN = "is_quota_plan"
  const val HAS_QUOTA_ALLOCATION = "has_quota_allocation"
  const val DAILY = "daily_percentage"
  const val WEEKLY = "weekly_percentage"
  const val DAILY_RESET = "daily_reset_at"
  const val WEEKLY_RESET = "weekly_reset_at"
  const val BALANCE = "overage_balance"
  const val HIDE_DAILY = "hide_daily_quota"
}
