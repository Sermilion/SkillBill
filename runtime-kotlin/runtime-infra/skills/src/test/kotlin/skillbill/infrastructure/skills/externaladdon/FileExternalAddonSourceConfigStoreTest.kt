package skillbill.infrastructure.skills.externaladdon

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.error.core.ExternalAddonFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.ExternalAddonSource
import skillbill.model.toPath
import skillbill.ports.install.addon.model.ExternalAddonSourceConfigRequest
import skillbill.ports.install.addon.model.ExternalAddonSourceRegistrationRequest
import skillbill.ports.repository.toFileLocation
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileExternalAddonSourceConfigStoreTest {
  private val store = FileExternalAddonSourceConfigStore()

  @Test
  fun `absent config returns empty`(
    @TempDir home: Path,
  ) {
    val sources = store.readExternalAddonSources(request(home, configPath(home))).sources

    assertTrue(sources.isEmpty())
  }

  @Test
  fun `empty array returns empty`(
    @TempDir home: Path,
  ) {
    writeConfig(home, mapOf("external_addon_sources" to emptyList<String>()))

    val sources = store.readExternalAddonSources(request(home, configPath(home))).sources

    assertTrue(sources.isEmpty())
  }

  @Test
  fun `valid sources are returned in declared order`(
    @TempDir home: Path,
  ) {
    val ios = Files.createDirectories(home.resolve("private/ios"))
    val kotlin = Files.createDirectories(home.resolve("private/kotlin"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(
            mapOf("path" to kotlin.toString(), "platform" to "kotlin"),
            mapOf("path" to ios.toString(), "platform" to " ios "),
          ),
      ),
    )

    val sources = store.readExternalAddonSources(request(home, configPath(home))).sources

    assertEquals(2, sources.size)
    assertEquals(kotlin, sources[0].path.toPath())
    assertEquals("kotlin", sources[0].platform)
    assertEquals(ios, sources[1].path.toPath())
    assertEquals("ios", sources[1].platform)
  }

  @Test
  fun `tilde path expands against request user home`(
    @TempDir home: Path,
  ) {
    Files.createDirectories(home.resolve("private/ios"))
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(mapOf("path" to "~/private/ios", "platform" to "ios")),
      ),
    )

    val sources = store.readExternalAddonSources(request(home, configPath(home))).sources

    assertEquals(home.resolve("private/ios"), sources[0].path.toPath())
  }

  @Test
  fun `register external source creates config and returns registered source`(
    @TempDir home: Path,
  ) {
    val sourceDir = Files.createDirectories(home.resolve("private/ios"))

    val sources =
      store.registerExternalAddonSource(
        registrationRequest(home, configPath(home), ExternalAddonSource(sourceDir.toFileLocation(), "ios")),
      ).sources

    assertEquals(listOf(ExternalAddonSource(sourceDir.toAbsolutePath().normalize().toFileLocation(), "ios")), sources)
    val payload = JsonCodec.parseObjectOrNull(Files.readString(configPath(home))).toString()
    assertTrue("external_addon_sources" in payload)
    assertEquals(
      sourceDir,
      store.readExternalAddonSources(request(home, configPath(home))).sources.single().path.toPath(),
    )
  }

  @Test
  fun `register external source is idempotent and preserves existing config fields`(
    @TempDir home: Path,
  ) {
    val sourceDir = Files.createDirectories(home.resolve("private/kotlin"))
    writeConfig(
      home,
      mapOf(
        "install_id" to "stable-id",
        "external_addon_sources" to listOf(mapOf("path" to sourceDir.toString(), "platform" to "kotlin")),
      ),
    )

    val sources =
      store.registerExternalAddonSource(
        registrationRequest(home, configPath(home), ExternalAddonSource(sourceDir.toFileLocation(), "kotlin")),
      ).sources

    assertEquals(listOf(ExternalAddonSource(sourceDir.toFileLocation(), "kotlin")), sources)
    val config = Files.readString(configPath(home))
    assertEquals(1, Regex("\"platform\":\"kotlin\"").findAll(config).count())
    assertTrue("\"install_id\":\"stable-id\"" in config)
  }

  @Test
  fun `malformed json loud-fails`(
    @TempDir home: Path,
  ) {
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.writeString(configPath(home), "{ not valid json")

    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalAddonSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalAddonFailureCode.CONFIG, it.code) }
  }

  @Test
  fun `external_addon_sources not a list loud-fails`(
    @TempDir home: Path,
  ) {
    writeConfig(home, mapOf("external_addon_sources" to "nope"))

    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalAddonSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalAddonFailureCode.CONFIG, it.code) }
  }

  @Test
  fun `element missing path loud-fails`(
    @TempDir home: Path,
  ) {
    writeConfig(
      home,
      mapOf("external_addon_sources" to listOf(mapOf("platform" to "ios"))),
    )

    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalAddonSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalAddonFailureCode.CONFIG, it.code) }
  }

  @Test
  fun `non-existent path loud-fails`(
    @TempDir home: Path,
  ) {
    writeConfig(
      home,
      mapOf(
        "external_addon_sources" to
          listOf(mapOf("path" to home.resolve("missing").toString(), "platform" to "ios")),
      ),
    )

    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalAddonSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalAddonFailureCode.CONFIG, it.code) }
  }

  private fun configPath(home: Path): Path = home.resolve(".skill-bill").resolve("config.json")

  private fun request(
    home: Path,
    configPath: Path,
  ): ExternalAddonSourceConfigRequest =
    ExternalAddonSourceConfigRequest(
      userHome = home,
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
    )

  private fun registrationRequest(
    home: Path,
    configPath: Path,
    source: ExternalAddonSource,
  ): ExternalAddonSourceRegistrationRequest =
    ExternalAddonSourceRegistrationRequest(
      userHome = home,
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
      source = source,
    )

  private fun writeConfig(
    home: Path,
    payload: Map<String, Any?>,
  ) {
    Files.createDirectories(home.resolve(".skill-bill"))
    Files.writeString(configPath(home), JsonCodec.mapToJsonString(payload) + "\n")
  }
}
