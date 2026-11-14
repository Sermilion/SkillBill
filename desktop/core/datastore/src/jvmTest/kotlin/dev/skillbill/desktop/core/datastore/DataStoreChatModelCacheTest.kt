package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.skillbill.desktop.core.domain.model.ChatModel
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreChatModelCacheTest {
  @TempDir
  lateinit var directory: File

  private val models = listOf(ChatModel("model-a", "Model A"), ChatModel("model-b", "Model B"))

  @Test
  fun `a written list survives a new store on the same file, keyed by agent and profile`() = runTest {
    val file = File(directory, "models.preferences_pb")
    // One DataStore per file at a time: the first one's scope ends before the file opens again, as on an app restart.
    val firstRun = Job(backgroundScope.coroutineContext[Job])
    val first = DataStoreChatModelCache(
      PreferenceDataStoreFactory.create(scope = CoroutineScope(backgroundScope.coroutineContext + firstRun)) { file },
      RecordingDiagnostics(),
    )
    first.write("codex", "/profiles/work", models)
    firstRun.cancelAndJoin()

    val reopened = DataStoreChatModelCache(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, RecordingDiagnostics())

    assertEquals(models, reopened.read("codex", "/profiles/work"))
    assertNull(reopened.read("codex", null))
    assertNull(reopened.read("codex", "/profiles/other"))
    assertNull(reopened.read("claude", "/profiles/work"))
  }

  @Test
  fun `a newer write replaces only its agent profile's list`() = runTest {
    val store = DataStoreChatModelCache(
      PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "replace.preferences_pb") },
      RecordingDiagnostics(),
    )
    store.write("codex", null, models)
    store.write("codex", "/profiles/work", listOf(ChatModel("other", "Other")))
    store.write("codex", null, listOf(ChatModel("model-c", "Model C")))

    assertEquals(listOf(ChatModel("model-c", "Model C")), store.read("codex", null))
    assertEquals(listOf(ChatModel("other", "Other")), store.read("codex", "/profiles/work"))
  }

  @Test
  fun `unreadable stored lists are skipped`() = runTest {
    val data = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "bad.preferences_pb") }
    data.edit { it[AppPreferenceKeys.CHAT_MODELS] = setOf("v1;garbage", "v2;1;x") }

    assertNull(DataStoreChatModelCache(data, RecordingDiagnostics()).read("codex", null))
  }

  @Test
  fun `the encoding rejects trailing data and truncated parts`() {
    val encoded = ChatModelCacheEncoding.encode(StoredChatModels("codex", null, models))

    assertEquals(StoredChatModels("codex", null, models), ChatModelCacheEncoding.decode(encoded))
    assertNull(ChatModelCacheEncoding.decode(encoded + "1;x"))
    assertNull(ChatModelCacheEncoding.decode(encoded.dropLast(3)))
  }
}
