package skillbill.infrastructure.skills.install.nativeagent.link

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class LinkProviderAgentsWithJournalTest {
  @Test
  fun `interruption restores provider files and propagates the same instance`(
    @TempDir directory: Path,
  ) {
    val file = directory.resolve("agent.md")
    Files.writeString(file, "original")
    val journal = ProviderMutationJournal()
    journal.beforeMutation(file)
    val interrupted = InterruptedException("interrupted")

    val propagated =
      assertFailsWith<InterruptedException> {
        linkProviderAgentsWithJournal(journal) {
          Files.writeString(file, "mutated")
          throw interrupted
        }
      }

    assertSame(interrupted, propagated)
    assertEquals("original", Files.readString(file))
  }
}
