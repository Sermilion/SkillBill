package dev.skillbill.desktop.core.data.runtime

import java.nio.file.Path
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

class RuntimeInstallRootTest {
  @Test
  fun `a blank runtime directory variable falls back to the home default`() {
    assertEquals(Path.of("/home/user", ".skill-bill", "runtime"), runtimeInstallRoot(mapOf("SKILL_BILL_RUNTIME_DIR" to "  "), "/home/user"))
  }
}
