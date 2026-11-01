package dev.skillbill.desktop.feature.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatResumeTest {
  // The highlight tells the operator Enter runs a command; marking an ordinary message or a path would mislead them.
  @Test
  fun `only a leading resume token or its start counts as the command`() {
    assertEquals(7, commandTokenLength("/resume"))
    assertEquals(3, commandTokenLength("/re"))
    assertEquals(7, commandTokenLength("/resume now"))
    assertNull(commandTokenLength("/usr/bin is missing"))
    assertNull(commandTokenLength("please /resume"))
    assertEquals(6, commandTokenLength("/clear"))
    assertEquals(2, commandTokenLength("/c"))
    assertNull(commandTokenLength("/clearly not a command"))
  }

  // A bare slash offers every command; a longer prefix offers only the commands it still spells.
  @Test
  fun `a prefix lists the commands it can still become`() {
    assertEquals(listOf("/resume", "/clear"), matchingCommands("/"))
    assertEquals(listOf("/clear"), matchingCommands("/cl"))
    assertEquals(emptyList(), matchingCommands("/x"))
  }
}
