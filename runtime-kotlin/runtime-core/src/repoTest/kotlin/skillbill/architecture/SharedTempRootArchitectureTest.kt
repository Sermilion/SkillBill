package skillbill.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedTempRootArchitectureTest {
  @Test
  fun `runtime production scan is clean and exemptions name scanned files`() {
    val sources = SharedTempRootScan.readSources()
    assertEquals(emptyList(), SharedTempRootScan.violations(sources))
    assertTrue(PrincipleEnforcementInventory.sharedTempRootExemptions.all { path -> sources.any { it.path == path } })
  }

  @Test
  fun `empty scan fails`() {
    assertTrue(SharedTempRootScan.violations(emptyList()).isNotEmpty())
  }

  @Test
  fun `shared temp forms reject aliases and qualified calls`() {
    val rejected =
      listOf(
        "System.getProperty(\"java.io.tmpdir\")",
        "val result = \"\"\"${'$'}{System.getProperty(\"java.io.tmpdir\")}\"\"\"",
        "System.getProperty(\"java.io.tmpdir\", fallback)",
        "Path.of(\"/tmp\")",
        "Paths.get(\"/tmp/x\")",
        "File(\"/tmp/x\")",
        "Files.createTempFile(\"x\", \".y\")",
        "Files.createTempDirectory(\"x\")",
        "Files.createTempFile(\n  \"x\", \".y\")",
        "Files.createTempDirectory(STAGING_PREFIX)",
        "File.createTempFile(\"x\", \".y\")",
        "createTempDirectory()",
        "createTempFile(\"x\")",
        "createTempDirectory(prefix = \"x\")",
        "createTempFile(null, \"x\")",
        "import java.nio.file.Files as F\nF.createTempFile(\"x\", \".y\")",
        "import java.lang.System as S\nS.getProperty(\"java.io.tmpdir\")",
        "import java.nio.file.Path as P\nP.of(\"/tmp/x\")",
        "import java.nio.file.Paths as P\nP.get(\"/tmp/x\")",
        "import java.io.File as F\nF(\"/tmp/x\")",
        "import kotlin.io.path.createTempDirectory as tempDir\ntempDir()",
        "import kotlin.io.path.createTempFile as tempFile\ntempFile(\"x\")",
        "java.nio.file.Files.createTempFile(\"x\", \".y\")",
        "java.lang.System.getProperty(\"java.io.tmpdir\")",
        "java.nio.file.Path.of(\"/tmp/x\")",
        "java.nio.file.Paths.get(\"/tmp/x\")",
        "java.io.File(\"/tmp/x\")",
        "java.io.File.createTempFile(\"x\", \".y\")",
        "kotlin.io.path.createTempFile(\"x\")",
      )
    rejected.forEach { code ->
      assertTrue(SharedTempRootScan.violations(listOf(source(code))).isNotEmpty(), code)
    }
  }

  @Test
  fun `host roots and non-code temp references remain allowed`() {
    val allowed =
      listOf(
        "Files.createTempFile(JdkHostPlatformPort.resolveTemporaryDirectory(), \"x\", \".y\")",
        "Files.createTempFile(directory, \"x\", \".y\")",
        "val option = \"-Djava.io.tmpdir=/state/scratch/jvm\"",
        "System.setProperty(\"java.io.tmpdir\", value)",
        "// System.getProperty(\"java.io.tmpdir\")",
        "Path.of(\"/tmpfs\")",
        "val example = \"\"\"System.getProperty(\"java.io.tmpdir\")\"\"\"",
      )
    allowed.forEach { code ->
      assertEquals(emptyList(), SharedTempRootScan.violations(listOf(source(code))), code)
    }
    assertEquals(
      emptyList(),
      SharedTempRootScan.violations(
        listOf(
          source(
            "System.getProperty(\"java.io.tmpdir\")",
            "runtime-kotlin/runtime-infra/host/src/main/kotlin/skillbill/infrastructure/host/jvm/" +
              "JdkHostPlatformPort.kt",
          ),
        ),
      ),
    )
  }

  private fun source(
    text: String,
    path: String = "runtime-kotlin/example/src/main/kotlin/Synthetic.kt",
  ) = SharedTempRootScan.Source(path, text)
}
