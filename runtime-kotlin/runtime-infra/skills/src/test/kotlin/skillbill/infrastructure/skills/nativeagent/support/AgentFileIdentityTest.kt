package skillbill.infrastructure.skills.nativeagent.support

import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFileIdentityTest {
  @Test
  fun `creation time identifies paths when the filesystem has no file key`() {
    val creationTime = FileTime.fromMillis(1234)
    val attributes =
      object : BasicFileAttributes {
        override fun lastModifiedTime(): FileTime = creationTime

        override fun lastAccessTime(): FileTime = creationTime

        override fun creationTime(): FileTime = creationTime

        override fun isRegularFile(): Boolean = false

        override fun isDirectory(): Boolean = true

        override fun isSymbolicLink(): Boolean = false

        override fun isOther(): Boolean = false

        override fun size(): Long = 0

        override fun fileKey(): Any? = null
      }

    assertEquals(creationTime, agentFileIdentity(attributes))
  }
}
