package dev.skillbill.desktop.core.testing

import dev.skillbill.desktop.core.domain.model.ChatBaselineCapture
import dev.skillbill.desktop.core.domain.model.ChatChangesBaseline
import dev.skillbill.desktop.core.domain.model.ChatChangesRead
import dev.skillbill.desktop.core.domain.repository.ChatChangesRepository

class FakeChatChangesRepository(
  var captureResult: ChatBaselineCapture = ChatBaselineCapture.Unavailable("Not configured."),
  var readResult: ChatChangesRead = ChatChangesRead.Unavailable("Not configured."),
) : ChatChangesRepository {
  val captureCalls = mutableListOf<String>()
  val readCalls = mutableListOf<Pair<String, ChatChangesBaseline>>()

  override suspend fun captureBaseline(repositoryRoot: String): ChatBaselineCapture {
    captureCalls += repositoryRoot
    return captureResult
  }

  override suspend fun read(repositoryRoot: String, baseline: ChatChangesBaseline): ChatChangesRead {
    readCalls += repositoryRoot to baseline
    return readResult
  }
}
