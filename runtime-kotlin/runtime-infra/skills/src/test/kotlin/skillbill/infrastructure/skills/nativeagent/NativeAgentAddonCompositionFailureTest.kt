package skillbill.infrastructure.skills.nativeagent

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.infrastructure.skills.nativeagent.discovery.discoverNativeAgentSourceEntries
import skillbill.infrastructure.skills.nativeagent.platformpack.toNativeAgentPlatformPack
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.nativeagent.rendering.composeGovernedAgentBody
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.scaffold.model.PlatformManifest
import skillbill.testing.HARBOR_ADDON_SLUG
import skillbill.testing.HARBOR_ARCHITECTURE_DIR
import skillbill.testing.HARBOR_ARCHITECTURE_WORKER
import skillbill.testing.HARBOR_AREA_MARKER
import skillbill.testing.HARBOR_COMPANION_NAME
import skillbill.testing.HARBOR_PACK_SLUG
import skillbill.testing.HarborAddonPack
import skillbill.testing.seedHarborAddonPack
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeAgentAddonCompositionFailureTest {
  @Test
  fun `missing entrypoint target names slug slot and absolute path and writes no agent`() {
    val pack = seedHarborAddonPack()
    val missingPath = pack.entrypointPath.toAbsolutePath().normalize()
    Files.delete(pack.entrypointPath)
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.MISSING_CONTENT_FILE, failure.code)
      }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'entrypoint'")
    assertContains(error.message.orEmpty(), missingPath.toString())
    assertNoRenderedAgent(pack.repoRoot)
  }

  @Test
  fun `undeclared companion pointer names slug slot and writes no agent`() {
    val pack = seedHarborAddonPack()
    val mutated = withGhostCompanion(loadPlatformPack(pack.packRoot))
    val manifestPath = pack.packRoot.resolve("platform.yaml").toAbsolutePath().normalize()
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        composeGovernedAgentBody(
          pack.repoRoot,
          NativeAgentCompositionTarget(
            contentPath = pack.architectureContent,
            source = NativeAgentCompositionTargetSource.PlatformManifest,
            manifest = mutated.toNativeAgentPlatformPack(),
          ),
          HARBOR_AREA_MARKER,
        )
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.MISSING_CONTENT_FILE, failure.code)
      }
    assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
    assertContains(error.message.orEmpty(), "slot 'ghost-companion.md'")
    assertContains(error.message.orEmpty(), manifestPath.toString())
    assertGhostCompanionRejectedAtLoadAndRender(pack)
  }

  @Test
  fun `unreadable entrypoint target names slug slot and absolute path and writes no agent`() {
    val pack = seedHarborAddonPack()
    val unreadable = pack.entrypointPath.toAbsolutePath().normalize()
    Files.setPosixFilePermissions(pack.entrypointPath, emptySet())
    try {
      val error =
        assertFailsWith<SkillBillRuntimeException> {
          testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
        }.also { failure ->
          assertEquals(SkillStagingFailureCode.MISSING_CONTENT_FILE, failure.code)
        }
      assertContains(error.message.orEmpty(), "add-on '$HARBOR_ADDON_SLUG'")
      assertContains(error.message.orEmpty(), "slot 'entrypoint'")
      assertContains(error.message.orEmpty(), unreadable.toString())
      assertNoRenderedAgent(pack.repoRoot)
    } finally {
      Files.setPosixFilePermissions(pack.entrypointPath, setOf(PosixFilePermission.OWNER_READ))
    }
  }

  @Test
  fun `over-budget composition names pack skill directory and byte total and writes nothing`() {
    val pack = seedHarborAddonPack()
    Files.writeString(pack.entrypointPath, "x".repeat(8_000))
    Files.createDirectories(pack.repoRoot.resolve(".skill-bill"))
    Files.writeString(
      pack.repoRoot.resolve(".skill-bill/config.yaml"),
      """
      review_context_budget:
        max_parent_packet_bytes: 2000
        max_lane_launch_bytes: 2000
      """.trimIndent() + "\n",
    )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        testComposeNativeAgentSource(pack.repoRoot, architectureSource(pack.repoRoot))
      }.also { failure ->
        assertEquals(SkillStagingFailureCode.COMPOSED_NATIVE_AGENT_BUDGET_EXCEEDED, failure.code)
      }
    val message = error.message.orEmpty()
    assertContains(message, "pack '$HARBOR_PACK_SLUG'")
    assertContains(message, "skill directory '$HARBOR_ARCHITECTURE_DIR'")
    assertContains(message, "bytes")
    val total = Regex("""is (\d+) bytes""").find(message)?.groupValues?.get(1)?.toInt()
    assertNotNull(total)
    assertTrue(total > 2000)
    assertNoRenderedAgent(pack.repoRoot)
  }

  private fun architectureSource(repoRoot: Path) =
    discoverNativeAgentSourceEntries(
      repoRoot.resolve("platform-packs"),
      null,
      listOf(HARBOR_PACK_SLUG),
    ).single { source -> source.name == HARBOR_ARCHITECTURE_WORKER }

  private fun assertNoRenderedAgent(repoRoot: Path) {
    val home = Files.createTempDirectory("skillbill-harbor-fail-home")
    assertTrue(
      runCatching {
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = repoRoot.resolve("platform-packs"),
            skillsRoot = null,
            selectedPlatforms = listOf(HARBOR_PACK_SLUG),
            provider = NativeAgentProvider.Claude,
            home = home,
            compositionContext = testNativeAgentCompositionContext(repoRoot),
          ),
        )
      }.isFailure,
    )
    NativeAgentProvider.entries.forEach { provider ->
      val cacheRoot =
        NativeAgentOperations.installCacheRoot(
          home,
          repoRoot.resolve("platform-packs"),
          null,
        )
      assertFalse(
        Files.exists(provider.cacheArtifactPath(cacheRoot, HARBOR_ARCHITECTURE_WORKER)),
        "${provider.directoryName} wrote ${HARBOR_ARCHITECTURE_WORKER} after a composition failure",
      )
    }
  }

  private fun withGhostCompanion(loaded: PlatformManifest): PlatformManifest =
    loaded.copy(
      addonUsage =
        loaded.addonUsage.map { usage ->
          usage.copy(
            addons =
              usage.addons.map { addon ->
                addon.copy(companionPointers = addon.companionPointers + "ghost-companion.md")
              },
          )
        },
    )

  private fun assertGhostCompanionRejectedAtLoadAndRender(pack: HarborAddonPack) {
    val manifest = pack.packRoot.resolve("platform.yaml")
    Files.writeString(
      manifest,
      Files.readString(manifest).replace(
        """
        |      companion_pointers:
        |        - $HARBOR_COMPANION_NAME
        """.trimMargin(),
        """
        |      companion_pointers:
        |        - $HARBOR_COMPANION_NAME
        |        - ghost-companion.md
        """.trimMargin(),
      ),
    )
    val schemaError =
      assertFailsWith<SkillBillRuntimeException> {
        loadPlatformPack(pack.packRoot)
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertContains(schemaError.message.orEmpty(), "ghost-companion.md")
    NativeAgentProvider.entries.forEach { provider ->
      assertProviderRejectsGhostCompanion(pack, provider)
    }
  }

  private fun assertProviderRejectsGhostCompanion(
    pack: HarborAddonPack,
    provider: NativeAgentProvider,
  ) {
    val home = Files.createTempDirectory("skillbill-harbor-undeclared-home")
    val thrown =
      runCatching {
        NativeAgentOperations.renderInstallArtifacts(
          NativeAgentInstallRenderRequest(
            platformPacksRoot = pack.repoRoot.resolve("platform-packs"),
            skillsRoot = null,
            selectedPlatforms = listOf(HARBOR_PACK_SLUG),
            provider = provider,
            home = home,
            compositionContext = testNativeAgentCompositionContext(pack.repoRoot),
          ),
        )
      }.exceptionOrNull()
    assertTrue(
      (thrown as? SkillBillRuntimeException)?.code == ManifestFailureCode.INVALID_MANIFEST_SCHEMA ||
        (thrown as? SkillBillRuntimeException)?.code == SkillStagingFailureCode.MISSING_CONTENT_FILE ||
        (thrown is IllegalArgumentException && thrown.message.orEmpty().contains("ghost-companion.md")),
      "${provider.directoryName} failed with ${thrown?.javaClass?.name}: ${thrown?.message}",
    )
    val cacheRoot =
      NativeAgentOperations.installCacheRoot(
        home,
        pack.repoRoot.resolve("platform-packs"),
        null,
      )
    assertFalse(
      Files.exists(provider.cacheArtifactPath(cacheRoot, HARBOR_ARCHITECTURE_WORKER)),
      "${provider.directoryName} wrote ${HARBOR_ARCHITECTURE_WORKER} after an undeclared companion",
    )
  }
}
