package dev.skillbill.tray.infrastructure

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryWebLinksTest {
  @Test
  fun `SSH origins become web links and branch names remain one encoded ref`() {
    assertEquals("https://github.com/Sermilion/ZeroAccount", RepositoryWebLinks.url("git@github.com:Sermilion/ZeroAccount.git").toString())
    assertEquals(
      "https://github.com/Sermilion/ZeroAccount/tree/fix%2Flogin%20%23one",
      RepositoryWebLinks.url("ssh://git@github.com:2222/Sermilion/ZeroAccount.git", "fix/login #one").toString(),
    )
    assertEquals("https://gitlab.example.com/group/repo/-/tree/main", RepositoryWebLinks.url("git@gitlab.example.com:group/repo.git", "main").toString())
    assertEquals("https://bitbucket.org/team/repo/src/main", RepositoryWebLinks.url("https://bitbucket.org/team/repo.git", "main").toString())
    assertEquals("https://github.com/team/repo", RepositoryWebLinks.url("https://secret@github.com/team/repo.git").toString())
    assertFailsWith<IllegalStateException> { RepositoryWebLinks.url("/local/repo") }
    assertFailsWith<IllegalArgumentException> { RepositoryWebLinks.url("file:///local/repo") }
  }
}
