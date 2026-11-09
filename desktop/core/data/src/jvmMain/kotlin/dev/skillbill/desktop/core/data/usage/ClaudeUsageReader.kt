package dev.skillbill.desktop.core.data.usage

import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.usage.StoredUsageReading
import dev.skillbill.desktop.core.domain.usage.model.RequestPermission
import dev.skillbill.desktop.core.domain.usage.model.UsageCapability
import dev.skillbill.desktop.core.domain.usage.model.UsageMeter
import dev.skillbill.desktop.core.domain.usage.model.UsageMeterScope
import dev.skillbill.desktop.core.domain.usage.model.UsagePeriod
import dev.skillbill.desktop.core.domain.usage.model.UsageProfileLookup
import dev.skillbill.desktop.core.domain.usage.model.UsageProvider
import dev.skillbill.desktop.core.domain.usage.model.UsageSource
import dev.skillbill.desktop.core.domain.usage.model.UsageValue
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.tatarka.inject.annotations.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun interface ClaudeUsageReader {
  suspend fun read(profile: UsageProfileLookup): ClaudeUsageRead
}

sealed interface ClaudeUsageRead {
  data class Success(val reading: StoredUsageReading) : ClaudeUsageRead
  data object SignInRequired : ClaudeUsageRead
  data class Throttled(val retryAt: Instant?) : ClaudeUsageRead
  data object Failed : ClaudeUsageRead
}

/** Reads the same OAuth allowance endpoint as Claude Code, using only the selected profile's credentials. */
@Inject
class ClaudeUsageCollector(private val clock: Clock, private val diagnostics: Diagnostics) : ClaudeUsageReader {
  override suspend fun read(profile: UsageProfileLookup): ClaudeUsageRead = try {
    runInterruptible(Dispatchers.IO) {
      val home = profile.configurationReference ?: return@runInterruptible ClaudeUsageRead.SignInRequired
      val credentials = Path.of(home).resolve(".credentials.json")
      if (!Files.exists(credentials)) return@runInterruptible ClaudeUsageRead.SignInRequired
      val token = Files.newInputStream(credentials).use { stream ->
        val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) return@runInterruptible ClaudeUsageRead.Failed
        val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        (root[ClaudeUsageKeys.OAUTH] as? JsonObject)?.get(ClaudeUsageKeys.ACCESS_TOKEN)
          ?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      } ?: return@runInterruptible ClaudeUsageRead.SignInRequired
      val connection = URI(USAGE_URL).toURL().openConnection() as HttpURLConnection
      try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
        connection.setRequestProperty("Accept", "application/json")
        when (connection.responseCode) {
          200 -> connection.inputStream.use { stream ->
            val bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1)
            if (bytes.size > MAX_RESPONSE_BYTES) ClaudeUsageRead.Failed
            else parseClaudeUsage(bytes.decodeToString(), clock.now())
          }
          401, 403 -> ClaudeUsageRead.SignInRequired
          429 -> {
            val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()?.takeIf { it in 0..86_400 }
            ClaudeUsageRead.Throttled(seconds?.let { clock.now() + it.seconds })
          }
          else -> ClaudeUsageRead.Failed
        }
      } finally {
        connection.disconnect()
      }
    }.also { result ->
      if (result !is ClaudeUsageRead.Success) diagnostics.record("usage.claude_read_unavailable")
    }
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    diagnostics.record("usage.claude_read_failed")
    ClaudeUsageRead.Failed
  }

  private companion object {
    const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
    const val MAX_RESPONSE_BYTES = 262_144
  }
}

/** A malformed window rejects the read instead of replacing good retained numbers with a partial parse. */
internal fun parseClaudeUsage(body: String, observedAt: Instant): ClaudeUsageRead {
  return try {
    val root = Json.parseToJsonElement(body).jsonObject
    val meters = root.mapNotNull { (key, element) ->
      if (key != ClaudeUsageKeys.FIVE_HOUR && key != ClaudeUsageKeys.SEVEN_DAY && !key.startsWith(ClaudeUsageKeys.MODEL_WEEK_PREFIX)) return@mapNotNull null
      if (element is JsonNull) return@mapNotNull null
      val window = element.jsonObject
      val percent = window[ClaudeUsageKeys.UTILIZATION]?.jsonPrimitive?.doubleOrNull ?: return ClaudeUsageRead.Failed
      val value = UsageValue.PercentageUsed.create(percent) ?: return ClaudeUsageRead.Failed
      val reset = window[ClaudeUsageKeys.RESETS_AT]?.jsonPrimitive?.contentOrNull?.let(Instant::parse)
      val model = key.takeIf { it.startsWith(ClaudeUsageKeys.MODEL_WEEK_PREFIX) }?.removePrefix(ClaudeUsageKeys.MODEL_WEEK_PREFIX)
      UsageMeter.create(
        bucketIdentity = "claude:$key",
        label = if (key == ClaudeUsageKeys.FIVE_HOUR) "5 hour" else "Weekly",
        scope = model?.let { UsageMeterScope.Model(it.replace('_', ' ').replaceFirstChar(Char::uppercase)) } ?: UsageMeterScope.General,
        observedAt = observedAt,
        resetAt = reset,
        period = UsagePeriod.Rolling.create(if (key == ClaudeUsageKeys.FIVE_HOUR) 5 * 3600L else 7 * 24 * 3600L)!!,
        value = value,
        sourceOwner = UsageSource(UsageProvider.Claude, UsageCapability.NumericSnapshot),
        freshnessBasis = 5.minutes,
      )!!
    }
    if (meters.isEmpty()) ClaudeUsageRead.Failed
    else ClaudeUsageRead.Success(StoredUsageReading(RequestPermission.Unknown, false, observedAt, meters))
  } catch (_: IllegalArgumentException) {
    ClaudeUsageRead.Failed
  }
}

private object ClaudeUsageKeys {
  const val OAUTH = "claudeAiOauth"
  const val ACCESS_TOKEN = "accessToken"
  const val FIVE_HOUR = "five_hour"
  const val SEVEN_DAY = "seven_day"
  const val MODEL_WEEK_PREFIX = "seven_day_"
  const val UTILIZATION = "utilization"
  const val RESETS_AT = "resets_at"
}
