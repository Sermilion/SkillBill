package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliProjectPathRuntimeTest {
  @Test
  fun `project path prints the store bundle directory and names the specs root when none matches`() {
    val context =
      CliRuntimeContext(
        environment = isolatedProjectStoreEnvironment(),
        repositoryRoot = Files.createTempDirectory("project-path-checkout"),
      )
    val storeRoot = Path.of(CliRuntime.run(listOf("project", "path"), context).stdout.trim())
    val bundle = storeRoot.resolve("specs/SKILL-77-sample").also { Files.createDirectories(it) }
    Files.writeString(bundle.resolve("spec.md"), "# SKILL-77\n")

    val found = CliRuntime.run(listOf("project", "path", "SKILL-77"), context)
    val missing = CliRuntime.run(listOf("project", "path", "SKILL-78"), context)

    assertEquals(0, found.exitCode, found.stdout)
    assertEquals(bundle.toString(), found.stdout.trim())
    assertEquals(1, missing.exitCode, missing.stdout)
    assertContains(missing.stdout, storeRoot.resolve("specs").toString())
  }
}
