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

  @Test
  fun `project import-specs copies legacy bundles into the store and reports them`() {
    val checkout = Files.createTempDirectory("project-import-checkout")
    val legacy = Files.createDirectories(checkout.resolve(".feature-specs/done/SKILL-5-archived"))
    Files.writeString(legacy.resolve("spec.md"), "# SKILL-5\n")
    val context =
      CliRuntimeContext(
        dbPathOverride = Files.createTempDirectory("project-import-db").resolve("metrics.db").toString(),
        environment = isolatedProjectStoreEnvironment(),
        repositoryRoot = checkout,
      )
    val storeRoot = Path.of(CliRuntime.run(listOf("project", "path"), context).stdout.trim())

    val result = CliRuntime.run(listOf("project", "import-specs"), context)

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "Imported 1 bundle(s)")
    assertEquals("# SKILL-5\n", Files.readString(storeRoot.resolve("specs/SKILL-5-archived/spec.md")))
    assertEquals("# SKILL-5\n", Files.readString(legacy.resolve("spec.md")))
  }
}
