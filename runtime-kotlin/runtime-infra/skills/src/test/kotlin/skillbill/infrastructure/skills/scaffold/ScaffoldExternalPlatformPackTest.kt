package skillbill.infrastructure.skills.scaffold

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ScaffoldFailureCode
import skillbill.infrastructure.skills.externalplatformpack.FileExternalPlatformPackSourceConfigStore
import skillbill.infrastructure.skills.install.scaffold.performScaffoldInstall
import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldAdapterSeams
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldPlan
import skillbill.infrastructure.skills.scaffold.runtime.service.ScaffoldRuntimeContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.performInstall
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffoldWithAdapters
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifest
import skillbill.testsupport.SkillClassFixtures

class ScaffoldExternalPlatformPackTest {
  @Test
  fun `external create writes the pack outside the repo and registers it`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/acme")
      val result =
        scaffoldExternal(
          payload(repo, destination.toString(), "create"),
          dryRun = false,
          seams(validate = { _, _ -> }),
        )
      assertTrue(Files.isRegularFile(destination.resolve("platform.yaml")))
      assertTrue(Files.isRegularFile(destination.resolve("code-review/bill-acme-code-review/content.md")))
      assertTrue(Files.readString(configPath()).contains(destination.toAbsolutePath().normalize().toString()))
      assertFalse(Files.exists(repo.resolve("platform-packs/acme"), LinkOption.NOFOLLOW_LINKS))
      assertTrue(result.createdFiles.isNotEmpty())
    }

  @Test
  fun `external dry-run writes no pack files and no config`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/acme")
      val result =
        scaffoldExternal(
          payload(repo, destination.toString(), "create"),
          dryRun = true,
          seams(validate = { _, _ -> }),
        )
      assertTrue(result.notes.any { note -> note.contains("Dry run") })
      assertTrue(result.notes.any { note -> note.contains("Planned external platform pack registration") })
      assertFalse(Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      assertFalse(Files.exists(configPath(), LinkOption.NOFOLLOW_LINKS))
    }

  @Test
  fun `create against an existing destination leaves that directory and writes no config`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/acme")
      Files.createDirectories(destination)
      Files.writeString(destination.resolve("keep.txt"), "user-bytes")
      assertFailsWith<SkillBillRuntimeException> {
        scaffoldExternal(
          payload(repo, destination.toString(), "create"),
          dryRun = false,
          seams(validate = { _, _ -> }),
        )
      }.also { assertEquals(ScaffoldFailureCode.SKILL_ALREADY_EXISTS, it.code) }
      assertEquals("user-bytes", Files.readString(destination.resolve("keep.txt")))
      assertFalse(Files.exists(configPath(), LinkOption.NOFOLLOW_LINKS))
    }

  @Test
  fun `register existing pack records the canonical path and does not rewrite platform yaml`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/acme")
      val manifest = destination.resolve("platform.yaml")
      writeConformingPack(destination)
      val before = Files.readAllBytes(manifest)
      val validation = FileSystemScaffoldRepoValidation()
      val result =
        scaffoldExternal(
          payload(repo, destination.toString(), "register"),
          dryRun = false,
          ScaffoldAdapterSeams(
            validateScaffold = { plan, repoRoot -> validation.validateScaffold(plan, repoRoot) },
            optionalBaselineLayers = { payload, repoRoot, platform ->
              validation.optionalBaselineLayers(payload, repoRoot, platform)
            },
            resolveAddonConsumerSkillDirs = { _, _, _ -> emptyList() },
            performInstall = { txn, plan, repoRoot -> performScaffoldInstall(txn, plan, repoRoot) },
            rollbackInstallTargets = { _, _ -> },
          ),
        )
      assertEquals(before.toList(), Files.readAllBytes(manifest).toList())
      assertTrue(result.notes.any { note -> note.contains("without rewriting pack files") })
      assertTrue(Files.readString(configPath()).contains(destination.toAbsolutePath().normalize().toString()))
      assertFalse(Files.exists(repo.resolve("platform-packs/acme"), LinkOption.NOFOLLOW_LINKS))
    }

  @Test
  fun `validation failure after create removes the new tree and the added config entry`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/java")
      Files.createDirectories(configPath().parent)
      Files.writeString(configPath(), JsonCodec.mapToJsonString(mapOf("install_id" to "stable-id")) + "\n")
      assertFailsWith<IllegalStateException> {
        scaffoldExternal(
          payload(repo, destination.toString(), "create", platform = "java"),
          dryRun = false,
          seams(validate = { _, _ -> throw IllegalStateException("injected validation failure") }),
        )
      }
      assertFalse(Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
      val config = Files.readString(configPath())
      assertTrue(config.contains("stable-id"))
      assertFalse(config.contains(destination.toAbsolutePath().normalize().toString()))
    }

  @Test
  fun `register existing pack ignores a sibling directory that is not a pack`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val destination = Path.of(System.getProperty("user.home")).resolve("packs/acme")
      writeConformingPack(destination)
      Files.createDirectories(destination.parent.resolve("notes"))
      val result =
        scaffoldExternal(
          payload(repo, destination.toString(), "register"),
          dryRun = false,
          seams(validate = { _, _ -> }),
        )
      assertTrue(result.notes.any { note -> note.contains("without rewriting pack files") })
      assertTrue(Files.readString(configPath()).contains(destination.toAbsolutePath().normalize().toString()))
    }

  private fun externalRuntime() =
    ScaffoldRuntimeContext(
      userHome = Path.of(System.getProperty("user.home")),
      catalogLoader = PlatformPackCatalogLoader(FileExternalPlatformPackSourceConfigStore()),
      packSourceConfig = FileExternalPlatformPackSourceConfigStore(),
    )

  private fun scaffoldExternal(
    payload: Map<String, Any?>,
    dryRun: Boolean,
    adapters: ScaffoldAdapterSeams,
  ) = scaffoldWithAdapters(payload, dryRun, adapters, runtime = externalRuntime())

  private fun seams(validate: (ScaffoldPlan, Path) -> Unit): ScaffoldAdapterSeams =
    ScaffoldAdapterSeams(
      validateScaffold = validate,
      optionalBaselineLayers = { _, _, _ -> emptyList() },
      resolveAddonConsumerSkillDirs = { _, _, _ -> emptyList() },
      performInstall = { _, _, _ -> emptyList<Path>() to emptyList<String>() },
      rollbackInstallTargets = { _, _ -> },
    )

  private fun payload(
    repo: Path,
    packLocation: String,
    registration: String,
    platform: String = "acme",
  ): Map<String, Any?> =
    mapOf(
      "scaffold_payload_version" to "1.0",
      "kind" to "platform-pack",
      "repo_root" to repo.toString(),
      "platform" to platform,
      "display_name" to platform,
      "description" to "$platform review pack",
      "routing_signals" to mapOf("strong" to listOf(".$platform")),
      "pack_location_path" to packLocation,
      "pack_registration" to registration,
    )

  private fun writeConformingPack(packRoot: Path) {
    val baseline = packRoot.resolve("code-review/bill-acme-code-review")
    Files.createDirectories(baseline)
    val context = TemplateContext("bill-acme-code-review", "code-review", "acme", "", "Acme")
    Files.writeString(
      packRoot.resolve("platform.yaml"),
      renderPlatformPackManifest(
        PlatformPackManifestRenderRequest(
          platform = "acme",
          displayName = "Acme",
          strongSignals = listOf(".acme"),
          baselineContentPath = "code-review/bill-acme-code-review/content.md",
        ),
      ),
    )
    Files.writeString(baseline.resolve("content.md"), renderContentBody(context, inferSkillDescription(context)))
  }

  private fun seedRepo(): Path {
    val repo = Files.createTempDirectory("skillbill-external-pack-scaffold")
    SkillClassFixtures.seedShippedSkillClasses(repo)
    supportingFileTargets(repo).values.forEach { target ->
      Files.createDirectories(target.parent)
      Files.writeString(target, "# ${target.fileName}\n")
    }
    return repo
  }

  private fun configPath(): Path = Path.of(System.getProperty("user.home"), ".config", "skill-bill", "config.json")

  private fun withIsolatedUserHome(block: () -> Unit) {
    val originalHome = System.getProperty("user.home")
    val tempHome = Files.createTempDirectory("skillbill-external-pack-home")
    try {
      System.setProperty("user.home", tempHome.toString())
      block()
    } finally {
      System.setProperty("user.home", originalHome)
    }
  }
}
