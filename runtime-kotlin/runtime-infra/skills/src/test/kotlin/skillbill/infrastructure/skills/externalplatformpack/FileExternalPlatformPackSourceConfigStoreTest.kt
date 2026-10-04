package skillbill.infrastructure.skills.externalplatformpack

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.error.core.ExternalPlatformPackFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.install.model.ExternalPlatformPackSource
import skillbill.model.toPath
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceConfigRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceRegistrationRequest
import skillbill.ports.install.platformpack.model.ExternalPlatformPackSourceUnregisterRequest
import skillbill.ports.repository.toFileLocation
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileExternalPlatformPackSourceConfigStoreTest {
  private val store = FileExternalPlatformPackSourceConfigStore()

  @Test
  fun `absent config returns empty`(
    @TempDir home: Path,
  ) {
    val sources = store.readExternalPlatformPackSources(request(home, configPath(home))).sources
    assertTrue(sources.isEmpty())
  }

  @Test
  fun `valid sources are returned in declared order`(
    @TempDir home: Path,
  ) {
    val first = Files.createDirectories(home.resolve("packs/kotlin"))
    val second = Files.createDirectories(home.resolve("packs/ios"))
    writeConfig(
      home,
      mapOf(
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
          listOf(
            mapOf("path" to first.toString()),
            mapOf("path" to second.toString()),
          ),
        "external_addon_sources" to listOf(mapOf("path" to home.resolve("addons").toString(), "platform" to "kotlin")),
      ),
    )

    val sources = store.readExternalPlatformPackSources(request(home, configPath(home))).sources

    assertEquals(2, sources.size)
    assertEquals(first, sources[0].path.toPath())
    assertEquals(second, sources[1].path.toPath())
  }

  @Test
  fun `register is idempotent and preserves unrelated keys`(
    @TempDir home: Path,
  ) {
    val packDir = Files.createDirectories(home.resolve("packs/kotlin"))
    writeConfig(
      home,
      mapOf(
        "install_id" to "stable-id",
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to listOf(mapOf("path" to packDir.toString())),
        "external_addon_sources" to listOf(mapOf("path" to "/tmp/addons", "platform" to "kotlin")),
      ),
    )

    val sources =
      store.registerExternalPlatformPackSource(
        registrationRequest(home, configPath(home), ExternalPlatformPackSource(packDir.toFileLocation())),
      ).sources

    assertEquals(1, sources.size)
    val beforeSecondRegister = Files.readString(configPath(home))
    val second =
      store.registerExternalPlatformPackSource(
        registrationRequest(home, configPath(home), ExternalPlatformPackSource(packDir.toFileLocation())),
      ).sources
    assertEquals(1, second.size)
    val config = Files.readString(configPath(home))
    assertEquals(beforeSecondRegister, config)
    assertTrue("\"install_id\":\"stable-id\"" in config)
    assertTrue("\"external_addon_sources\"" in config)
  }

  @Test
  fun `unregister removes canonical path only`(
    @TempDir home: Path,
  ) {
    val packDir = Files.createDirectories(home.resolve("packs/kotlin"))
    writeConfig(
      home,
      mapOf(
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to listOf(mapOf("path" to packDir.toString())),
      ),
    )

    val sources =
      store.unregisterExternalPlatformPackSource(
        ExternalPlatformPackSourceUnregisterRequest(
          userHome = home,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString()),
          source = ExternalPlatformPackSource(packDir.toFileLocation()),
        ),
      ).sources

    assertTrue(sources.isEmpty())
    assertTrue(Files.isDirectory(packDir))
  }

  @Test
  fun `SKILL_BILL_CONFIG_PATH wins over xdg and legacy config`(
    @TempDir home: Path,
  ) {
    val packDir = Files.createDirectories(home.resolve("packs/kotlin"))
    val pinned = home.resolve("pinned-config.json")
    val xdg = home.resolve(".config").resolve("skill-bill").resolve("config.json")
    val legacy = configPath(home)
    writeConfigAt(xdg, mapOf("install_id" to "xdg-id"))
    writeConfigAt(legacy, mapOf("install_id" to "legacy-id"))
    writeConfigAt(
      pinned,
      mapOf(
        "install_id" to "pinned-id",
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to listOf(mapOf("path" to packDir.toString())),
      ),
    )
    val xdgBefore = Files.readString(xdg)
    val legacyBefore = Files.readString(legacy)

    val sources =
      store.readExternalPlatformPackSources(
        ExternalPlatformPackSourceConfigRequest(
          userHome = home,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to pinned.toString()),
        ),
      ).sources

    assertEquals(packDir.toAbsolutePath().normalize(), sources.single().path.toPath())
    store.registerExternalPlatformPackSource(
      registrationRequest(home, pinned, ExternalPlatformPackSource(packDir.toFileLocation())),
    )
    assertEquals(xdgBefore, Files.readString(xdg))
    assertEquals(legacyBefore, Files.readString(legacy))
    assertTrue(Files.readString(pinned).contains("pinned-id"))
    assertTrue(!Files.readString(pinned).contains("xdg-id"))
  }

  @Test
  fun `tilde path is stored once as a canonical absolute path`(
    @TempDir home: Path,
  ) {
    val packDir = Files.createDirectories(home.resolve("packs/kotlin"))
    writeConfig(
      home,
      mapOf(
        "install_id" to "stable-id",
        "external_addon_sources" to listOf(mapOf("path" to "/tmp/addons", "platform" to "kotlin")),
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to listOf(mapOf("path" to "~/packs/kotlin")),
      ),
    )

    val read = store.readExternalPlatformPackSources(request(home, configPath(home))).sources
    assertEquals(packDir.toAbsolutePath().normalize(), read.single().path.toPath())

    val canonical = resolveExternalPlatformPackSourcePath(home, "~/packs/kotlin")
    val beforeWrite = Files.readString(configPath(home))
    store.registerExternalPlatformPackSource(
      registrationRequest(home, configPath(home), ExternalPlatformPackSource(canonical.toFileLocation())),
    )
    val afterWrite = Files.readString(configPath(home))
    assertEquals(beforeWrite, afterWrite)
    assertTrue("\"install_id\":\"stable-id\"" in afterWrite)
    assertTrue("\"external_addon_sources\"" in afterWrite)
    assertTrue(canonical.toString() in afterWrite || "~/packs/kotlin" in afterWrite)
  }

  @Test
  fun `unregister removes a deleted pack directory and keeps the other registration`(
    @TempDir home: Path,
  ) {
    val kept = Files.createDirectories(home.resolve("kept"))
    val removed = Files.createDirectories(home.resolve("removed"))
    writeConfig(
      home,
      mapOf(
        "install_id" to "stable-id",
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
          listOf(
            mapOf(ExternalPlatformPackConfigKeys.PATH to kept.toString()),
            mapOf(ExternalPlatformPackConfigKeys.PATH to removed.toString(), "note" to "author"),
          ),
      ),
    )
    Files.delete(removed)
    val result =
      store.unregisterExternalPlatformPackSource(
        ExternalPlatformPackSourceUnregisterRequest(
          userHome = home,
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath(home).toString()),
          source = ExternalPlatformPackSource(removed.toAbsolutePath().normalize().toFileLocation()),
        ),
      )
    assertEquals(kept.toAbsolutePath().normalize(), result.sources.single().path.toPath())
    val stored = Files.readString(configPath(home))
    assertTrue("\"install_id\":\"stable-id\"" in stored)
    assertTrue(kept.toString() in stored)
    assertFalse(removed.toString() in stored)
  }

  @Test
  fun `missing pack directory stays in the source list for catalog resolution`(
    @TempDir home: Path,
  ) {
    val missing = home.resolve("missing-pack")
    writeConfig(
      home,
      mapOf(
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
          listOf(mapOf("path" to missing.toString())),
      ),
    )
    val sources = store.readExternalPlatformPackSources(request(home, configPath(home))).sources
    assertEquals(missing.toAbsolutePath().normalize(), sources.single().path.toPath())
  }

  @Test
  fun `malformed list loud-fails`(
    @TempDir home: Path,
  ) {
    writeConfig(home, mapOf(ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to "nope"))
    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalPlatformPackSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalPlatformPackFailureCode.CONFIG, it.code) }
  }

  @Test
  fun `malformed entries loud-fail before a source is returned`(
    @TempDir home: Path,
  ) {
    writeConfig(
      home,
      mapOf(
        ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
          listOf(mapOf("path" to "   ")),
      ),
    )
    assertFailsWith<SkillBillRuntimeException> {
      store.readExternalPlatformPackSources(request(home, configPath(home)))
    }.also { assertEquals(ExternalPlatformPackFailureCode.CONFIG, it.code) }
  }

  private fun configPath(home: Path): Path = home.resolve(".skill-bill").resolve("config.json")

  private fun request(
    home: Path,
    configPath: Path,
  ): ExternalPlatformPackSourceConfigRequest =
    ExternalPlatformPackSourceConfigRequest(
      userHome = home,
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
    )

  private fun registrationRequest(
    home: Path,
    configPath: Path,
    source: ExternalPlatformPackSource,
  ): ExternalPlatformPackSourceRegistrationRequest =
    ExternalPlatformPackSourceRegistrationRequest(
      userHome = home,
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to configPath.toString()),
      source = source,
    )

  private fun writeConfig(
    home: Path,
    payload: Map<String, Any?>,
  ) {
    Files.createDirectories(home.resolve(".skill-bill"))
    writeConfigAt(configPath(home), payload)
  }

  private fun writeConfigAt(
    path: Path,
    payload: Map<String, Any?>,
  ) {
    path.parent?.let(Files::createDirectories)
    Files.writeString(path, JsonCodec.mapToJsonString(payload) + "\n")
  }
}
