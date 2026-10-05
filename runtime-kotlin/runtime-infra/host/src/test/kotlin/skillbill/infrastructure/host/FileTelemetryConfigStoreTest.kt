package skillbill.infrastructure.host

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.model.EnvironmentContext
import skillbill.ports.telemetry.model.TelemetryConfigRead
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import skillbill.telemetry.INSTALL_ID_ENVIRONMENT_KEY
import skillbill.telemetry.STATE_DIR_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileTelemetryConfigStoreTest {
  @Test
  fun `config reads distinguish absence malformed text and non-object roots while edge reads keep throwing`(
    @TempDir tempDir: Path,
  ) {
    val path = tempDir.resolve("config.json")

    assertEquals(TelemetryConfigRead.Absent, readTelemetryConfigFileRead(path))

    Files.writeString(path, "{not json")
    val malformedText = assertIs<TelemetryConfigRead.Malformed>(readTelemetryConfigFileRead(path))
    assertEquals("Telemetry config at '$path' is not valid JSON.", malformedText.reason)
    assertEquals(
      malformedText.reason,
      assertFailsWith<IllegalArgumentException> { readTelemetryConfigFile(path) }.message,
    )
    assertEquals(
      malformedText.reason,
      assertFailsWith<IllegalArgumentException> { ensureTelemetryConfigFile(path) }.message,
    )

    Files.writeString(path, "[]")
    val nonObject = assertIs<TelemetryConfigRead.Malformed>(readTelemetryConfigFileRead(path))
    assertEquals("Telemetry config at '$path' is not valid JSON.", nonObject.reason)

    Files.writeString(path, "{}")
    val present = assertIs<TelemetryConfigRead.Present>(readTelemetryConfigFileRead(path))
    assertEquals(emptyMap(), present.document.payload)
  }

  @Test
  fun `telemetry state dir expands user home at filesystem adapter seam`() {
    val home = Path.of("build/tmp/skill-bill-home").toAbsolutePath().normalize()

    assertEquals(
      home,
      resolveTelemetryStateDir(mapOf(STATE_DIR_ENVIRONMENT_KEY to "~"), home),
    )
    assertEquals(
      home.resolve("state").toAbsolutePath().normalize(),
      resolveTelemetryStateDir(mapOf(STATE_DIR_ENVIRONMENT_KEY to "~/state"), home),
    )
  }

  @Test
  fun `telemetry config path expands user home at filesystem adapter seam`() {
    val home = Path.of("build/tmp/skill-bill-home").toAbsolutePath().normalize()

    assertEquals(
      home,
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to "~"), home),
    )
    assertEquals(
      home.resolve("config.json").toAbsolutePath().normalize(),
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to "~/config.json"), home),
    )
  }

  @Test
  fun `config path defaults to durable home-relative config dir for fresh installs`(
    @TempDir tempDir: Path,
  ) {
    val resolved = resolveTelemetryConfigPath(emptyMap(), tempDir)

    assertEquals(
      tempDir.resolve(".config/skill-bill/config.json").toAbsolutePath().normalize(),
      resolved,
    )
  }

  @Test
  fun `config path prefers existing durable config over legacy`(
    @TempDir tempDir: Path,
  ) {
    val durable = tempDir.resolve(".config/skill-bill/config.json")
    Files.createDirectories(durable.parent)
    Files.writeString(durable, "{}\n")
    Files.createDirectories(tempDir.resolve(".skill-bill"))
    Files.writeString(tempDir.resolve(".skill-bill/config.json"), "{}\n")

    assertEquals(durable.toAbsolutePath().normalize(), resolveTelemetryConfigPath(emptyMap(), tempDir))
  }

  @Test
  fun `config path falls back to legacy path when only legacy exists`(
    @TempDir tempDir: Path,
  ) {
    val legacy = tempDir.resolve(".skill-bill/config.json")
    Files.createDirectories(legacy.parent)
    Files.writeString(legacy, "{}\n")

    assertEquals(legacy.toAbsolutePath().normalize(), resolveTelemetryConfigPath(emptyMap(), tempDir))
  }

  @Test
  fun `config path ignores XDG_CONFIG_HOME and stays home-relative`(
    @TempDir tempDir: Path,
  ) {
    val resolved = resolveTelemetryConfigPath(mapOf("XDG_CONFIG_HOME" to "/some/global/xdg"), tempDir)

    assertEquals(
      tempDir.resolve(".config/skill-bill/config.json").toAbsolutePath().normalize(),
      resolved,
    )
  }

  @Test
  fun `explicit config env override wins over durable and legacy paths`(
    @TempDir tempDir: Path,
  ) {
    Files.createDirectories(tempDir.resolve(".config/skill-bill"))
    Files.writeString(tempDir.resolve(".config/skill-bill/config.json"), "{}\n")
    val pinned = tempDir.resolve("pinned/config.json")

    assertEquals(
      pinned.toAbsolutePath().normalize(),
      resolveTelemetryConfigPath(mapOf(CONFIG_ENVIRONMENT_KEY to pinned.toString()), tempDir),
    )
  }

  @Test
  fun `ensure prefers the injected install id env value when none is persisted`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")

    val document =
      ensureTelemetryConfigFile(
        configPath,
        mapOf(INSTALL_ID_ENVIRONMENT_KEY to "  env-install-id  "),
      )

    assertEquals("env-install-id", document.payload["install_id"])
  }

  @Test
  fun `ensure mints a fresh uuid when neither env nor persisted install id exist`(
    @TempDir tempDir: Path,
  ) {
    val first =
      ensureTelemetryConfigFile(tempDir.resolve("first.json"), emptyMap())
        .payload["install_id"] as String
    val second =
      ensureTelemetryConfigFile(tempDir.resolve("second.json"), emptyMap())
        .payload["install_id"] as String

    assertTrue(first.isNotBlank(), "Minted install id must be non-blank.")
    assertNotEquals(first, second, "Each fresh install id must be distinct.")
  }

  @Test
  fun `ensure prefers an existing persisted install id over the fallback`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      JsonCodec.mapToJsonString(mapOf("install_id" to "persisted-id")) + "\n",
    )
    val store = storeFor(tempDir, configPath, installIdEnv = "ignored-because-persisted-wins")

    val document = store.ensure()

    assertEquals("persisted-id", document.payload["install_id"])
  }

  @Test
  fun `ensure uses the env fallback install id when none is persisted`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")
    val store = storeFor(tempDir, configPath, installIdEnv = "env-fallback-id")

    val document = store.ensure()

    assertEquals("env-fallback-id", document.payload["install_id"])
    assertEquals(
      mapOf(
        "level" to "anonymous",
        "proxy_url" to "",
        "batch_size" to 50,
      ),
      document.payload["telemetry"],
    )
  }

  @Test
  fun `a machine config still carrying a retired experiments block loads and keeps its other keys`(
    @TempDir tempDir: Path,
  ) {
    val configPath = tempDir.resolve("config.json")
    Files.writeString(
      configPath,
      """{"install_id":"persisted-id","experiments":{"availability":"on_request"}}""",
    )
    val store = storeFor(tempDir, configPath, installIdEnv = "env-fallback-id")

    val document = store.ensure()

    assertEquals("persisted-id", document.payload["install_id"])
    assertEquals(mapOf("availability" to "on_request"), document.payload["experiments"])
  }

  private fun storeFor(
    tempDir: Path,
    configPath: Path,
    installIdEnv: String,
  ): FileTelemetryConfigStore =
    FileTelemetryConfigStore(
      EnvironmentContext(
        environment =
          mapOf(
            CONFIG_ENVIRONMENT_KEY to configPath.toString(),
            STATE_DIR_ENVIRONMENT_KEY to tempDir.toString(),
            INSTALL_ID_ENVIRONMENT_KEY to installIdEnv,
          ),
        userHome = tempDir,
      ),
    )
}
