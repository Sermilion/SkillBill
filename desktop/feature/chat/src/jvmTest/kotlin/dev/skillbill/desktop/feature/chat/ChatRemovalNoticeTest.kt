package dev.skillbill.desktop.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ChatRemovalNoticeTest {
  @Test
  fun `current and legacy removal notices filter only recognized results`() {
    val goalPrefix = "$REMOVED_WORKTREE_CHAT_NOTICE /repo. "
    val executionPrefix = "This execution's worktree was removed when it finished. New turns run in /repo. "
    val accepted = listOf(
      SESSIONS_MOVED_SENTENCE,
      NOTHING_TO_MOVE_SENTENCE,
      MOVE_FAILED_SENTENCE,
      "Cursor and Retired chats start fresh.",
      "Earlier Claude and Codex chats were moved there and can be resumed; Cursor and Retired chats start fresh.",
    )
    val rejected = listOf(
      "An ordinary conversation line.",
      "Cursor and Two Words chats start fresh.",
      "Cursor and Retired chats start fresh. trailing text",
    )

    accepted.forEach { sentence ->
      assertEquals(true, isSerializedRemovalNotice(goalPrefix + sentence), sentence)
      assertEquals(true, isSerializedRemovalNotice(executionPrefix + sentence), sentence)
    }
    rejected.forEach { sentence ->
      assertEquals(false, isSerializedRemovalNotice(goalPrefix + sentence), sentence)
      assertEquals(false, isSerializedRemovalNotice(executionPrefix + sentence), sentence)
    }
    assertEquals(false, isSerializedRemovalNotice("$REMOVED_WORKTREE_CHAT_NOTICE . $NOTHING_TO_MOVE_SENTENCE"))
  }
}
