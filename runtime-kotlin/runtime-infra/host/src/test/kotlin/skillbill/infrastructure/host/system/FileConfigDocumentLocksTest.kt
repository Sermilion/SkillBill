package skillbill.infrastructure.host.system

import org.junit.jupiter.api.io.TempDir
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileConfigDocumentLocksTest {
  @Test
  fun `lock acquisition failure returns the typed error without running the action`(
    @TempDir tempDir: Path,
  ) {
    val config = tempDir.resolve("config.json")
    val lockPath = config.resolveSibling("${config.fileName}.lock")
    val lockTarget = tempDir.resolve("lock-target")
    Files.writeString(lockTarget, "lock")
    Files.createSymbolicLink(lockPath, lockTarget)
    val ran = AtomicBoolean(false)

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        FileConfigDocumentLocks.withLocks(listOf(config)) { ran.set(true) }
      }

    assertEquals(InstallFailureCode.REPO_LOCAL_CONFIG_FAILURE, failure.code)
    assertFalse(ran.get())
  }
}
