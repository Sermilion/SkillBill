package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliConfigResolveSpecTypeRuntimeTest {
  @Test
  fun `config resolve-spec-type is registered and shows help`() {
    val result = CliRuntime.run(listOf("config", "resolve-spec-type", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "--arg")
    assertContains(result.stdout, "--repo-root")
  }

  @Test
  fun `arg linear overrides config and resolves linear`() {
    val repoRoot = repoRootWithConfig("spec_type: local")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--arg", "linear", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("linear", result.stdout.trim())
  }

  @Test
  fun `arg local overrides config and resolves local`() {
    val repoRoot = repoRootWithConfig("spec_type: linear")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--arg", "local", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("local", result.stdout.trim())
  }

  @Test
  fun `blank arg defers to config spec_type`() {
    val repoRoot = repoRootWithConfig("spec_type: linear")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("linear", result.stdout.trim())
  }

  @Test
  fun `default arg sentinel defers to config spec_type`() {
    val repoRoot = repoRootWithConfig("spec_type: linear")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--arg", "default", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("linear", result.stdout.trim())
  }

  @Test
  fun `no config file resolves to local`() {
    val repoRoot = Files.createTempDirectory("config-resolve-no-config")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(0, result.exitCode, result.stdout)
    assertEquals("local", result.stdout.trim())
  }

  @Test
  fun `malformed config value exits non-zero with named error`() {
    val repoRoot = repoRootWithConfig("spec_type: nonsense")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "is malformed")
    assertContains(result.stdout, "spec_type")
  }

  @Test
  fun `unrecognized service arg exits non-zero`() {
    val repoRoot = Files.createTempDirectory("config-resolve-bad-arg")

    val result =
      CliRuntime.run(
        listOf("config", "resolve-spec-type", "--arg", "bogus", "--repo-root", repoRoot.toString()),
        CliRuntimeContext(environment = isolatedProjectStoreEnvironment()),
      )

    assertEquals(1, result.exitCode, result.stdout)
    assertContains(result.stdout, "Unrecognized service value")
  }
}

private fun repoRootWithConfig(configBody: String): Path {
  val repoRoot = Files.createTempDirectory("config-resolve-spec-type")
  val configDir = Files.createDirectories(repoRoot.resolve(".skill-bill"))
  Files.writeString(configDir.resolve("config.yaml"), configBody)
  return repoRoot
}
