package skillbill.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.RESERVED_TEST_INSTALL_ID
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliConfigAgentsRuntimeTest {
  @Test
  fun `config agents is registered and shows help`() {
    val result = CliRuntime.run(listOf("config", "agents", "--help"), CliRuntimeContext())

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "show")
    assertContains(result.stdout, "set")
    assertContains(result.stdout, "unset")
  }

  @Test
  fun `show reports authored routing and inherited phases without an invoker`() {
    val configPath =
      configInHome(
        """{"execution_matrix":{"phase_tiers":{"review":"reasoning"},""" +
          """"tier_agents":{"implementation":"cursor"},"phase_agents":{"review":"codex"},""" +
          """"agents":{"claude":{"reasoning":{"model":"m","effort":"high"}}}}}""",
      )

    val result = run(configPath.parent, "config", "agents", "show")

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "implementation: cursor")
    assertContains(result.stdout, "review: codex")
    assertContains(result.stdout, "review (reasoning): codex (phase_agents)")
    assertContains(result.stdout, "inherited (invoking agent)")
  }

  @Test
  fun `set tier writes routing while preserving phase tiers and models`() {
    val configPath =
      configInHome(
        """{"execution_matrix":{"phase_tiers":{"review":"reasoning"},""" +
          """"agents":{"claude":{"reasoning":{"model":"m","effort":"high"}}}},"telemetry":{"level":"full"}}""",
      )

    val result = run(configPath.parent, "config", "agents", "set", "--tier", "reasoning", "--agent", "claude")

    assertEquals(0, result.exitCode, result.stdout)
    val doc = Json.parseToJsonElement(Files.readString(configPath)).jsonObject
    val matrix = doc.getValue("execution_matrix").jsonObject
    assertEquals(
      "claude",
      matrix.getValue("tier_agents").jsonObject.getValue("reasoning").jsonPrimitive.content,
    )
    assertEquals(
      "reasoning",
      matrix.getValue("phase_tiers").jsonObject.getValue("review").jsonPrimitive.content,
    )
    assertTrue("agents" in matrix)
    assertTrue("telemetry" in doc)
  }

  @Test
  fun `set phase then unset phase restores inheritance`() {
    val configPath = configInHome("""{"execution_matrix":{"agents":{"claude":{}}}}""")

    val setResult =
      run(configPath.parent, "config", "agents", "set", "--phase", "review", "--agent", "codex")
    assertEquals(0, setResult.exitCode, setResult.stdout)
    var matrix =
      Json.parseToJsonElement(Files.readString(configPath))
        .jsonObject.getValue("execution_matrix").jsonObject
    assertEquals("codex", matrix.getValue("phase_agents").jsonObject.getValue("review").jsonPrimitive.content)

    val unsetResult = run(configPath.parent, "config", "agents", "unset", "--phase", "review")
    assertEquals(0, unsetResult.exitCode, unsetResult.stdout)
    matrix = Json.parseToJsonElement(Files.readString(configPath)).jsonObject.getValue("execution_matrix").jsonObject
    assertFalse("review" in (matrix["phase_agents"]?.jsonObject ?: emptyMap()))
  }

  @Test
  fun `set rejects selecting both tier and phase`() {
    val configPath = configInHome("""{"execution_matrix":{}}""")
    val before = Files.readString(configPath)

    val result =
      run(configPath.parent, "config", "agents", "set", "--tier", "reasoning", "--phase", "plan", "--agent", "claude")

    assertTrue(result.exitCode != 0)
    assertEquals(before, Files.readString(configPath))
  }

  @Test
  fun `set rejects an unknown provider without writing`() {
    val configPath = configInHome("""{"execution_matrix":{}}""")
    val before = Files.readString(configPath)

    val result = run(configPath.parent, "config", "agents", "set", "--tier", "reasoning", "--agent", "notanagent")

    assertTrue(result.exitCode != 0)
    assertEquals(before, Files.readString(configPath))
  }

  @Test
  fun `set rejects an unknown phase without writing`() {
    val configPath = configInHome("""{"execution_matrix":{}}""")
    val before = Files.readString(configPath)

    val result = run(configPath.parent, "config", "agents", "set", "--phase", "notaphase", "--agent", "claude")

    assertTrue(result.exitCode != 0)
    assertEquals(before, Files.readString(configPath))
  }

  @Test
  fun `unreadable machine config exits non-zero without writing`() {
    val home = Files.createTempDirectory("config-agents-unreadable")
    val configPath = home.resolve("config.json")
    Files.writeString(configPath, "not json at all {{{")

    val result = run(home, "config", "agents", "set", "--tier", "reasoning", "--agent", "claude")

    assertTrue(result.exitCode != 0)
    assertEquals("not json at all {{{", Files.readString(configPath))
  }

  @Test
  fun `show on absent config reports all phases inherited`() {
    val home = Files.createTempDirectory("config-agents-absent")

    val result = run(home, "config", "agents", "show")

    assertEquals(0, result.exitCode, result.stdout)
    assertContains(result.stdout, "(none authored)")
    assertContains(result.stdout, "inherited (invoking agent)")
  }

  private fun run(
    home: Path,
    vararg args: String,
  ) = CliRuntime.run(
    args.toList(),
    CliRuntimeContext(
      userHome = home,
      environment =
        isolatedCliEnvironment() +
          (CONFIG_ENVIRONMENT_KEY to home.resolve("config.json").toString()) +
          (INSTALL_ID_ENVIRONMENT_KEY to RESERVED_TEST_INSTALL_ID),
    ),
  )

  private fun configInHome(json: String): Path {
    val home = Files.createTempDirectory("config-agents")
    val config = home.resolve("config.json")
    Files.writeString(config, json)
    return config
  }
}
