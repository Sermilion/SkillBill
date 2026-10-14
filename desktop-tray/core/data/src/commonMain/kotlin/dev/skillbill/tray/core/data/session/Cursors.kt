package dev.skillbill.tray.core.data.session

import dev.skillbill.tray.core.domain.model.DetailList
import dev.skillbill.tray.core.domain.model.HistoryFilter
import dev.skillbill.tray.core.domain.model.WorkSection
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
internal data class PageCursor(
  val generation: Long,
  val session: Long,
  val section: WorkSection,
  val observedAt: String,
  val retention: String,
  val filter: String,
  val historyFilter: HistoryFilter,
  val afterTime: String,
  val afterId: String,
  val storeId: String? = null,
  val storeRevision: Long? = null,
  val queryEpoch: Long = 0,
)

@Serializable
internal data class DetailCursor(
  val generation: Long,
  val session: Long,
  val revision: Long,
  val id: String,
  val repositoryIdentity: String,
  val runtimeCursor: String,
  val list: DetailList? = null,
)

/** Cursors are opaque URL-safe Base64 JSON; anything that does not decode back is treated as a stale cursor. */
@OptIn(ExperimentalEncodingApi::class)
internal object CursorCodec {
  private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

  fun encode(cursor: PageCursor): String = base64.encode(Json.encodeToString(cursor).encodeToByteArray())

  fun encode(cursor: DetailCursor): String = base64.encode(Json.encodeToString(cursor).encodeToByteArray())

  fun decodePage(token: String): PageCursor? = decode(token) { Json.decodeFromString<PageCursor>(it) }

  fun decodeDetail(token: String): DetailCursor? = decode(token) { Json.decodeFromString<DetailCursor>(it) }

  private inline fun <T> decode(token: String, parse: (String) -> T): T? = try {
    parse(base64.decode(token).decodeToString())
  } catch (_: SerializationException) {
    null
  } catch (_: IllegalArgumentException) {
    null
  }
}
