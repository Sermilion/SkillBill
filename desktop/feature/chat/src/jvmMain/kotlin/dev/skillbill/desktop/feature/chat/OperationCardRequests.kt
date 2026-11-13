package dev.skillbill.desktop.feature.chat

import dev.skillbill.desktop.core.domain.model.NewTaskStart
import java.util.concurrent.ConcurrentHashMap

/** The start request behind each parked card, by launch id, shared by the window's chats so a run chat can Adjust a card another chat started. */
internal class OperationCardRequests {
  private val requests = ConcurrentHashMap<String, NewTaskStart>()

  fun remember(launchId: String, request: NewTaskStart) {
    requests[launchId] = request.copy(confirmation = null)
  }

  operator fun get(launchId: String): NewTaskStart? = requests[launchId]

  operator fun contains(launchId: String): Boolean = requests.containsKey(launchId)
}
