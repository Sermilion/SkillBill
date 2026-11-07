package skillbill.infrastructure.workflow.filesystem

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.util.EnumSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileSystemChildFailureOutputStoreTest {
  @TempDir
  lateinit var root: Path

  @Test
  fun `ACL-only filesystem retains output with access limited to its owner`() {
    val configuration = Configuration.windows().toBuilder().setAttributeViews("basic", "owner", "acl").build()
    Jimfs.newFileSystem(configuration).use { fileSystem ->
      val evidenceRoot = fileSystem.getPath("C:\\evidence")
      val name = FileSystemChildFailureOutputStore().retain(evidenceRoot, "wftr-child", "private failure output")
      val file = evidenceRoot.resolve("wftr-child").resolve(name)

      assertEquals("private failure output", Files.readString(file))
      val entry = Files.getFileAttributeView(file, AclFileAttributeView::class.java).acl.single()
      assertEquals(Files.getOwner(file), entry.principal())
      assertEquals(AclEntryType.ALLOW, entry.type())
      assertEquals(EnumSet.allOf(AclEntryPermission::class.java), entry.permissions())
    }
  }

  @Test
  fun `filesystem without private permissions retains no output file`() {
    val configuration = Configuration.unix().toBuilder().setAttributeViews("basic").build()
    Jimfs.newFileSystem(configuration).use { fileSystem ->
      val evidenceRoot = fileSystem.getPath("/evidence")

      assertFailsWith<IOException> {
        FileSystemChildFailureOutputStore().retain(evidenceRoot, "wftr-child", "private failure output")
      }
      assertEquals(0L, Files.list(evidenceRoot.resolve("wftr-child")).use { it.count() })
    }
  }

  @Test
  fun `child workflow identity cannot escape the evidence root`() {
    assertFailsWith<IllegalArgumentException> {
      FileSystemChildFailureOutputStore().retain(root, "../other", "private")
    }
    assertEquals(0L, Files.list(root).use { it.count() })
  }
}
