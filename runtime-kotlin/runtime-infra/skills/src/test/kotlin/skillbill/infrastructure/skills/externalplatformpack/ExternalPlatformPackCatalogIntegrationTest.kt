package skillbill.infrastructure.skills.externalplatformpack

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.contracts.config.ExternalPlatformPackTelemetryPayloadKeys
import skillbill.error.core.AmbiguousExternalPlatformPackError
import skillbill.error.core.ExternalPlatformPackConfigError
import skillbill.error.core.ExternalPlatformPackPublishError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.error.shellcontent.SkillStagingFailureCode
import skillbill.infrastructure.skills.install.nativeagent.installNativeAgentCompositionContext
import skillbill.infrastructure.skills.install.nativeagent.link.InstallNativeAgentOperations
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkOverrides
import skillbill.infrastructure.skills.install.nativeagent.link.NativeAgentLinkRequest
import skillbill.infrastructure.skills.install.nativeagent.link.ProviderMutationJournal
import skillbill.infrastructure.skills.install.nativeagent.link.effectivePackRootsForInstall
import skillbill.infrastructure.skills.install.nativeagent.link.publishInstalledReviewCatalog
import skillbill.infrastructure.skills.install.nativeagent.link.uninstallNativeAgentFiles
import skillbill.infrastructure.skills.install.plan.buildInstallStagingIntent
import skillbill.infrastructure.skills.install.plan.discoverPlatformManifests
import skillbill.infrastructure.skills.install.reconcile.ReconcileSourceRoots
import skillbill.infrastructure.skills.install.reconcile.reconcileEnumerationRequest
import skillbill.infrastructure.skills.install.reconcile.skillRelativePath
import skillbill.infrastructure.skills.install.staging.content.InstallContentHashInputs
import skillbill.infrastructure.skills.install.staging.content.computeInstallContentHash
import skillbill.infrastructure.skills.nativeagent.platformpack.InstallNativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderOverrides
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentInstallRenderRequest
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentOperations
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.infrastructure.skills.scaffold.authoring.AuthoringOperations
import skillbill.infrastructure.skills.scaffold.authoring.resolveTarget
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackCatalogLoader
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadCompositionClosure
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.install.model.InstallAgentSelection
import skillbill.install.model.InstallAgentSelectionMode
import skillbill.install.model.InstallPlanRequest
import skillbill.install.model.InstallPlanSkill
import skillbill.install.model.InstallPlanSkillKind
import skillbill.install.model.InstallTelemetryLevel
import skillbill.install.model.InstallationTargetPaths
import skillbill.install.model.McpRegistrationChoice
import skillbill.install.model.PlatformPackSelection
import skillbill.install.model.PlatformPackSelectionMode
import skillbill.install.model.RuntimeDistributionInputs
import skillbill.install.model.WindowsSymlinkDecision
import skillbill.install.model.WindowsSymlinkPreflight
import skillbill.install.model.WindowsSymlinkPreflightState
import skillbill.model.toPath
import skillbill.ports.install.platformpack.model.ExternalPlatformPackRootRequest
import skillbill.ports.install.platformpack.model.PlatformPackCatalogRequest
import skillbill.ports.repository.toFileLocation
import skillbill.review.plan.ReviewStackRouting
import skillbill.review.plan.model.ReviewRoutingChangedFile
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.policy.platformpack.model.PlatformPackSourceKind
import skillbill.telemetry.CONFIG_ENVIRONMENT_KEY

class ExternalPlatformPackCatalogIntegrationTest {
  @Test
  fun `external kotlin replaces bundled for catalog routing and install discovery`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val external = root.resolve("external/kotlin")
    val acme = root.resolve("external/acme")
    seedExternalKotlinReplacement(root, repo, config)

    val context = context(repo, home, config)
    val catalog = loader().loadEffectiveCatalog(context)
    val kotlin = catalog.entryForSlug("kotlin")
    assertEquals(PlatformPackSourceKind.EXTERNAL, kotlin?.loaded?.sourceKind)
    assertEquals(listOf(".kt"), kotlin?.loaded?.manifest?.routingSignals?.strong)
    assertEquals("kotlin", kotlin?.shadowedBundledSlug)
    assertEquals(listOf("external-gate-collect"), kotlin?.loaded?.manifest?.validationGate?.collectAllFullGateCommand)
    assertEquals(listOf("external-gate-collect-build"), kotlin?.loaded?.manifest?.validationGate?.buildCommand)
    assertTrue(
      Files.readString(
        external.resolve("code-review/bill-kotlin-code-review/content.md"),
      ).contains("EXTERNAL_BASELINE_MARKER"),
    )
    assertEquals(PlatformPackSourceKind.EXTERNAL, catalog.entryForSlug("acme")?.loaded?.sourceKind)
    assertEquals(null, catalog.entryForSlug("acme")?.shadowedBundledSlug)

    val discovered = discoverPlatformManifests(installRequest(repo, home, config), catalogLoader = loader())
    assertEquals(
      external.toAbsolutePath().normalize(),
      discovered.single { it.slug == "kotlin" }.packRoot.toPath().toAbsolutePath().normalize(),
    )

    Files.writeString(
      external.resolve("code-review/bill-kotlin-code-review/content.md"),
      reviewBody("bill-kotlin-code-review", "kotlin", "EDITED_EXTERNAL_MARKER"),
    )
    val edited = loader().loadEffectiveCatalog(context)
    assertTrue(
      Files.readString(edited.entryForSlug("kotlin")!!.loaded.manifest.declaredFiles.baseline!!.toPath())
        .contains("EDITED_EXTERNAL_MARKER"),
    )

    writeSources(config)
    val restored = loader().loadEffectiveCatalog(context)
    assertEquals(PlatformPackSourceKind.BUNDLED, restored.entryForSlug("kotlin")?.loaded?.sourceKind)
    assertEquals(null, restored.entryForSlug("kotlin")?.shadowedBundledSlug)
    assertEquals(null, restored.entryForSlug("acme"))
    assertTrue(
      Files.readString(restored.entryForSlug("kotlin")!!.loaded.manifest.declaredFiles.baseline!!.toPath())
        .contains("BUNDLED_BASELINE_MARKER"),
    )
    assertFalse(
      Files.readString(restored.entryForSlug("kotlin")!!.loaded.manifest.declaredFiles.baseline!!.toPath())
        .contains("EDITED_EXTERNAL_MARKER"),
    )
  }

  @Test
  fun `native agent generation reads the effective external pack source`(
    @TempDir root: Path,
  ) {
    val fixture = nativeAgentFixture(root)
    assertNativeAgentMarker(fixture, "EXTERNAL_AGENT_MARKER", "BUNDLED_AGENT_MARKER")

    Files.writeString(
      fixture.externalAgents.resolve("agents.yaml"),
      nativeAgentBundle("EDITED_AGENT_MARKER"),
    )
    assertNativeAgentMarker(fixture, "EDITED_AGENT_MARKER", "EXTERNAL_AGENT_MARKER")

    writeSources(fixture.config)
    assertNativeAgentMarker(fixture, "BUNDLED_AGENT_MARKER", "EDITED_AGENT_MARKER")
  }

  @Test
  fun `invalid external kotlin is not promoted over the bundled pack`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val bundled = repo.resolve("platform-packs/kotlin")
    val external = root.resolve("external/kotlin")
    writePack(bundled, "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate-collect")
    Files.createDirectories(external)
    Files.writeString(external.resolve("platform.yaml"), "platform: [\n")
    writeSources(config, external)

    assertFailsWith<SkillBillRuntimeException> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }.also { failure ->
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
    }
  }

  @Test
  fun `two external roots for one slug publish no catalog`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val first = root.resolve("external/first/kotlin")
    val second = root.resolve("external/second/kotlin")
    writePack(first, "kotlin", "FIRST_MARKER", listOf(".kt"), "first-gate")
    writePack(second, "kotlin", "SECOND_MARKER", listOf(".kt"), "second-gate")
    writeSources(config, first, second)

    assertFailsWith<AmbiguousExternalPlatformPackError> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }
  }

  @Test
  fun `missing root slug mismatch bad contract and missing content fail before promotion`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    writePack(repo.resolve("platform-packs/kotlin"), "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate")
    writeSources(config, root.resolve("missing/kotlin"))
    assertFailsWith<ExternalPlatformPackConfigError> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }

    val mismatched = root.resolve("external/kotlin")
    writePack(mismatched, "kotlin", "MISMATCH_MARKER", listOf(".kt"), "mismatch-gate")
    Files.writeString(
      mismatched.resolve("platform.yaml"),
      Files.readString(mismatched.resolve("platform.yaml")).replace("platform: kotlin", "platform: acme"),
    )
    writeSources(config, mismatched)
    assertFailsWith<SkillBillRuntimeException> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }.also { failure ->
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
    }

    val wrongVersion = root.resolve("external/version/kotlin")
    writePack(
      wrongVersion,
      "kotlin",
      "VERSION_MARKER",
      listOf(".kt"),
      PackFixtureOptions(gate = "version-gate", contractVersion = "0.0"),
    )
    writeSources(config, wrongVersion)
    assertFailsWith<ContractVersionMismatchError> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }

    val missingContent = root.resolve("external/empty/kotlin")
    writePack(missingContent, "kotlin", "MISSING_MARKER", listOf(".kt"), "missing-gate")
    Files.delete(missingContent.resolve("code-review/bill-kotlin-code-review/content.md"))
    writeSources(config, missingContent)
    assertFailsWith<SkillBillRuntimeException> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }.also { failure ->
      assertEquals(SkillStagingFailureCode.MISSING_CONTENT_FILE, failure.code)
    }
  }

  @Test
  fun `kmp composition reads the effective kotlin baseline and a missing area fails closed`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val bundled = repo.resolve("platform-packs/kotlin")
    val external = root.resolve("external/kotlin")
    val kmp = repo.resolve("platform-packs/kmp")
    writePack(
      bundled,
      "kotlin",
      "BUNDLED_BASELINE_MARKER",
      listOf(".kt", "settings.gradle.kts"),
      PackFixtureOptions(gate = "bundled-gate", areaMarker = "BUNDLED_AREA_MARKER"),
    )
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    writePack(
      kmp,
      "kmp",
      "KMP_BASELINE_MARKER",
      listOf("kmp-marker"),
      PackFixtureOptions(gate = "kmp-gate", compositionSkill = "bill-kotlin-code-review"),
    )
    writeSources(config, external)

    val catalog = loader().loadEffectiveCatalog(context(repo, home, config))
    val closure = loadCompositionClosure(catalog.entryForSlug("kmp")!!.loaded.manifest, catalog.manifestsBySlug)
    val effectiveKotlin = closure.single { it.slug == "kotlin" }
    val baseline = Files.readString(effectiveKotlin.declaredFiles.baseline!!.toPath())
    assertTrue(baseline.contains("EXTERNAL_BASELINE_MARKER"))
    assertFalse(baseline.contains("BUNDLED_BASELINE_MARKER"))
    assertFalse(baseline.contains("BUNDLED_AREA_MARKER"))

    Files.writeString(
      kmp.resolve("platform.yaml"),
      Files.readString(kmp.resolve("platform.yaml"))
        .replace("skill: bill-kotlin-code-review", "skill: bill-kotlin-code-review-architecture"),
    )
    val error =
      assertFailsWith<SkillBillRuntimeException> {
        loader().loadEffectiveCatalog(context(repo, home, config))
      }.also { failure ->
        assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      }
    assertFalse(error.message.orEmpty().contains("BUNDLED_AREA_MARKER"))
  }

  @Test
  fun `registration accepts external kotlin when bundled kotlin is absent and kmp requires it`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val external = root.resolve("external/kotlin")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    writePack(
      repo.resolve("platform-packs/kmp"),
      "kmp",
      "KMP_BASELINE_MARKER",
      listOf("kmp-marker"),
      PackFixtureOptions(gate = "kmp-gate", compositionSkill = "bill-kotlin-code-review"),
    )
    writeSources(config)

    val slug =
      loader().assertRegistrableExternalPack(
        ExternalPlatformPackRootRequest(
          packRoot = external,
          catalog =
            PlatformPackCatalogRequest(
              repoRoot = repo,
              userHome = home,
              environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
            ),
        ),
      ).slug

    assertEquals("kotlin", slug)
    assertFalse(Files.isDirectory(repo.resolve("platform-packs/kotlin")))

    val kmpRoot = repo.resolve("platform-packs/kmp")
    InstallNativeAgentPlatformPackLoader.loadPlatformPack(
      kmpRoot,
      listOf(external, kmpRoot),
    )
    assertFailsWith<SkillBillRuntimeException> {
      InstallNativeAgentPlatformPackLoader.loadPlatformPack(kmpRoot, emptyList())
    }.also { failure ->
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
    }
  }

  @Test
  fun `review uses generic when kotlin exists neither externally nor as a bundled pack`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    writePack(
      repo.resolve("platform-packs/generic"),
      "generic",
      "GENERIC_BASELINE_MARKER",
      listOf("manifest-declared code-review fallback"),
      PackFixtureOptions(fallback = true),
    )
    writePack(
      repo.resolve("platform-packs/kmp"),
      "kmp",
      "KMP_BASELINE_MARKER",
      listOf(".kt"),
      PackFixtureOptions(gate = "kmp-gate", compositionSkill = "bill-kotlin-code-review"),
    )
    writeSources(config)

    val absentNative = loader().loadEffectiveCatalog(context(repo, home, config))
    assertEquals(null, absentNative.entryForSlug("kotlin"))
    assertEquals(setOf("generic"), reviewRoute(absentNative.manifests))

    writeSources(config, root.resolve("missing/kotlin"))
    val absentExternal = loader().loadEffectiveCatalog(context(repo, home, config))
    assertEquals(null, absentExternal.entryForSlug("kotlin"))
    assertEquals(PlatformPackSourceKind.BUNDLED, absentExternal.entryForSlug("generic")?.loaded?.sourceKind)
    assertEquals(setOf("generic"), reviewRoute(absentExternal.manifests))
  }

  @Test
  fun `missing validation gate is not filled from the shadowed bundled pack`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    writePack(
      repo.resolve("platform-packs/kotlin"),
      "kotlin",
      "BUNDLED_BASELINE_MARKER",
      listOf(".kt"),
      "bundled-gate-collect",
    )
    writePack(root.resolve("external/kotlin"), "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), gate = null)
    writeSources(config, root.resolve("external/kotlin"))

    val kotlin = loader().loadEffectiveCatalog(context(repo, home, config)).entryForSlug("kotlin")
    assertEquals(PlatformPackSourceKind.EXTERNAL, kotlin?.loaded?.sourceKind)
    assertEquals(null, kotlin?.loaded?.manifest?.validationGate)
  }

  @Test
  fun `symlink escape keeps the outside file and the previous catalog`(
    @TempDir root: Path,
  ) {
    val pack = root.resolve("external/kotlin")
    writePack(pack, "kotlin", "OLD_CATALOG_MARKER", listOf(".kt"), "external-gate")
    val cache = root.resolve("cache")
    val outside = root.resolve("secret-user-file.txt")
    val authorFile = pack.resolve("author-notes.md")
    Files.writeString(outside, "SECRET_USER_BYTES")
    Files.writeString(authorFile, "KEEP_AUTHOR_BYTES")
    val bundledRoot = root.resolve("repo/platform-packs")
    publishInstalledReviewCatalog(bundledRoot, null, cache, ProviderMutationJournal(), listOf(pack))
    val published = cache.resolve("review-catalog/platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md")
    assertTrue(Files.readString(published).contains("OLD_CATALOG_MARKER"))

    val content = pack.resolve("code-review/bill-kotlin-code-review/content.md")
    Files.delete(content)
    Files.createSymbolicLink(content, outside)
    val error =
      assertFailsWith<ExternalPlatformPackPublishError> {
        publishInstalledReviewCatalog(bundledRoot, null, cache, ProviderMutationJournal(), listOf(pack))
      }
    assertEquals("previous_catalog_retained", error.remotePayload[ExternalPlatformPackTelemetryPayloadKeys.RECOVERY])
    assertEquals("kotlin", error.remotePayload[ExternalPlatformPackTelemetryPayloadKeys.PLATFORM_SLUG])
    assertEquals("external", error.remotePayload[ExternalPlatformPackTelemetryPayloadKeys.SOURCE_KIND])
    assertFalse(error.remotePayload.values.joinToString(" ").contains(outside.toString()))
    assertEquals("SECRET_USER_BYTES", Files.readString(outside))
    assertEquals("KEEP_AUTHOR_BYTES", Files.readString(authorFile))
    assertTrue(Files.readString(published).contains("OLD_CATALOG_MARKER"))
  }

  @Test
  fun `directory symlink escape fails before the outside bytes are accepted`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val pack = root.resolve("external/kotlin")
    writePack(pack, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    val outside = root.resolve("outside-review")
    Files.move(pack.resolve("code-review"), outside)
    Files.createSymbolicLink(pack.resolve("code-review"), outside)
    writeSources(config, pack)
    val outsideBytes = Files.readString(outside.resolve("bill-kotlin-code-review/content.md"))

    assertFailsWith<ExternalPlatformPackConfigError> {
      loader().loadEffectiveCatalog(context(repo, home, config))
    }
    assertEquals(outsideBytes, Files.readString(outside.resolve("bill-kotlin-code-review/content.md")))
  }

  @Test
  fun `external reconcile identity stays under one slug directory`(
    @TempDir root: Path,
  ) {
    val external = root.resolve("external/kotlin")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    val skillDir = external.resolve("code-review/bill-kotlin-code-review")
    val relative =
      skillRelativePath(
        ReconcileSourceRoots(
          repoRoot = root.resolve("repo"),
          skillsRoot = root.resolve("repo/skills"),
          platformPacksRoot = root.resolve("repo/platform-packs"),
        ),
        InstallPlanSkill(
          name = "bill-kotlin-code-review",
          sourceDir = skillDir.toFileLocation(),
          kind = InstallPlanSkillKind.PLATFORM_PACK,
          platformSlug = "kotlin",
        ),
      )
    assertEquals("platform-packs/kotlin/code-review/bill-kotlin-code-review", relative)
  }

  @Test
  fun `external pack pointer outside the checkout still stages`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val external = root.resolve("external/kotlin")
    val playbook = repo.resolve("orchestration/review-orchestrator/PLAYBOOK.md")
    Files.createDirectories(playbook.parent)
    Files.writeString(playbook, "# Playbook\n")
    writePack(repo.resolve("platform-packs/kotlin"), "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    Files.writeString(
      external.resolve("platform.yaml"),
      Files.readString(external.resolve("platform.yaml")) +
        """
        pointers:
          code-review/bill-kotlin-code-review:
            - name: review-orchestrator.md
              target: orchestration/review-orchestrator/PLAYBOOK.md

        """.trimIndent(),
    )
    writeSources(config, external)
    val request = installRequest(repo, home, config)
    val loader = loader()
    val manifests = discoverPlatformManifests(request, catalogLoader = loader)
    val kotlinManifest = manifests.single { manifest -> manifest.slug == "kotlin" }
    assertEquals(external.toAbsolutePath().normalize(), kotlinManifest.packRoot.toPath())
    val skillDir = external.resolve("code-review/bill-kotlin-code-review")
    val staging =
      buildInstallStagingIntent(
        request,
        listOf(
          InstallPlanSkill(
            name = "bill-kotlin-code-review",
            sourceDir = skillDir.toFileLocation(),
            kind = InstallPlanSkillKind.PLATFORM_PACK,
            platformSlug = "kotlin",
          ),
        ),
        manifests,
        loader,
      )
    assertEquals(skillDir.toAbsolutePath().normalize(), staging.skillPaths.single().sourceDir.toPath())
  }

  @Test
  fun `source switch drops the external sidecar and an external-only slug`(
    @TempDir root: Path,
  ) {
    val bundled = root.resolve("repo/platform-packs/kotlin")
    val external = root.resolve("external/kotlin")
    val acme = root.resolve("external/acme")
    writePack(bundled, "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate")
    writePack(acme, "acme", "ACME_BASELINE_MARKER", listOf(".acme"), "acme-gate")
    val cache = root.resolve("cache")
    publishInstalledReviewCatalog(
      root.resolve("external"),
      null,
      cache,
      ProviderMutationJournal(),
      listOf(external, acme),
    )
    val catalogRoot = cache.resolve("review-catalog/platform-packs")
    Files.writeString(catalogRoot.resolve("kotlin/stale-sidecar.md"), "STALE_SIDECAR")
    publishInstalledReviewCatalog(
      root.resolve("repo/platform-packs"),
      null,
      cache,
      ProviderMutationJournal(),
      listOf(bundled),
    )
    val published = catalogRoot.resolve("kotlin/code-review/bill-kotlin-code-review/content.md")
    assertTrue(Files.readString(published).contains("BUNDLED_BASELINE_MARKER"))
    assertFalse(Files.readString(published).contains("EXTERNAL_BASELINE_MARKER"))
    assertFalse(Files.exists(catalogRoot.resolve("kotlin/stale-sidecar.md"), LinkOption.NOFOLLOW_LINKS))
    assertFalse(Files.exists(catalogRoot.resolve("acme"), LinkOption.NOFOLLOW_LINKS))
  }

  @Test
  fun `identical bytes at different source paths do not share an install hash`(
    @TempDir root: Path,
  ) {
    val left = Files.createDirectories(root.resolve("left"))
    val right = Files.createDirectories(root.resolve("right"))
    Files.writeString(left.resolve("content.md"), "same-bytes\n")
    Files.writeString(right.resolve("content.md"), "same-bytes\n")
    assertNotEquals(
      computeInstallContentHash(left, listOf(left.resolve("content.md")), emptyList()),
      computeInstallContentHash(right, listOf(right.resolve("content.md")), emptyList()),
    )
  }

  @Test
  fun `uninstall removes a native-agent link to the previous source and keeps user files`(
    @TempDir root: Path,
  ) {
    val oldSource = Files.createDirectories(root.resolve("old-cache")).resolve("review.md")
    Files.writeString(oldSource, "old-agent\n")
    val agentDir = Files.createDirectories(root.resolve("agent"))
    val link = agentDir.resolve("review.md")
    Files.createSymbolicLink(link, oldSource)
    Files.writeString(agentDir.resolve("user-notes.md"), "keep-user\n")

    val removed = uninstallNativeAgentFiles(listOf(oldSource), listOf(agentDir))

    assertEquals(listOf(link), removed)
    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    assertEquals("keep-user\n", Files.readString(agentDir.resolve("user-notes.md")))
    assertEquals("old-agent\n", Files.readString(oldSource))
  }

  @Test
  fun `pinned config path selects the external pack for reconcile and native-agent roots`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("pinned-config.json")
    val bundled = repo.resolve("platform-packs/kotlin")
    val external = root.resolve("external/kotlin")
    val xdg = home.resolve(".config/skill-bill/config.json")
    writePack(bundled, "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate-collect")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate-collect")
    Files.createDirectories(xdg.parent)
    Files.writeString(xdg, "{}\n")
    writeSources(config, external)
    val environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString())
    val roots =
      ReconcileSourceRoots(
        repoRoot = repo,
        skillsRoot = repo.resolve("skills"),
        platformPacksRoot = repo.resolve("platform-packs"),
      )

    val pinned =
      discoverPlatformManifests(
        reconcileEnumerationRequest(roots, home, environment),
        catalogLoader = loader(),
      )
    val ignored =
      discoverPlatformManifests(
        reconcileEnumerationRequest(roots, home),
        catalogLoader = loader(),
      )
    assertEquals(external.toAbsolutePath().normalize(), pinned.single { it.slug == "kotlin" }.packRoot.toPath())
    assertEquals(bundled.toAbsolutePath().normalize(), ignored.single { it.slug == "kotlin" }.packRoot.toPath())
    assertTrue(
      Files.readString(pinned.single { it.slug == "kotlin" }.declaredFiles.baseline!!.toPath())
        .contains("EXTERNAL_BASELINE_MARKER"),
    )
    assertFalse(
      Files.readString(ignored.single { it.slug == "kotlin" }.declaredFiles.baseline!!.toPath())
        .contains("EXTERNAL_BASELINE_MARKER"),
    )

    val rootsForLink =
      effectivePackRootsForInstall(
        platformPacksRoot = repo.resolve("platform-packs"),
        userHome = home,
        environment = environment,
        selectedPlatforms = null,
        catalogLoader = loader(),
      )
    assertTrue(rootsForLink.any { it == external.toAbsolutePath().normalize() })
    assertFalse(rootsForLink.any { it == bundled.toAbsolutePath().normalize() })
  }

  @Test
  fun `validate addresses the external content file and leaves the bundled file unchanged`(
    @TempDir root: Path,
  ) {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    val bundled = repo.resolve("platform-packs/kotlin")
    val external = root.resolve("external/kotlin")
    writePack(bundled, "kotlin", "BUNDLED_BASELINE_MARKER", listOf(".kt"), "bundled-gate-collect")
    writePack(external, "kotlin", "EXTERNAL_BASELINE_MARKER", listOf(".kt"), "external-gate-collect")
    writeSources(config, external)
    val discovery = context(repo, home, config)
    val bundledContent = bundled.resolve("code-review/bill-kotlin-code-review/content.md")
    val externalContent = external.resolve("code-review/bill-kotlin-code-review/content.md")
    val bundledBefore = Files.readString(bundledContent)

    val target = resolveTarget(repo, "bill-kotlin-code-review", discovery)
    assertEquals(externalContent.toAbsolutePath().normalize(), target.contentFile.toAbsolutePath().normalize())

    val validation = AuthoringOperations.validate(repo, listOf("bill-kotlin-code-review"), discovery)
    assertTrue(validation.issues.none { issue -> bundledContent.toString() in issue })
    assertEquals(bundledBefore, Files.readString(bundledContent))
    assertTrue(Files.readString(externalContent).contains("EXTERNAL_BASELINE_MARKER"))
  }

  @Test
  fun `external pointer hash reads the checkout target and leaves an outside file unread`(
    @TempDir root: Path,
  ) {
    val checkout = root.resolve("checkout")
    val playbook = checkout.resolve("orchestration/review-orchestrator/PLAYBOOK.md")
    Files.createDirectories(playbook.parent)
    Files.writeString(playbook, "CHECKOUT_POINTER_MARKER\n")
    val external = root.resolve("company/packs/kotlin")
    val skill = external.resolve("code-review/bill-kotlin-code-review")
    Files.createDirectories(skill)
    Files.writeString(skill.resolve("content.md"), "external-body\n")
    val decoy = root.resolve("company/orchestration/review-orchestrator/PLAYBOOK.md")
    Files.createDirectories(decoy.parent)
    Files.writeString(decoy, "DECOY_POINTER_MARKER\n")
    val outside = root.resolve("secret-playbook.md")
    Files.writeString(outside, "SECRET_POINTER_BYTES\n")
    val pointer =
      PointerSpec(
        skillRelativeDir = "code-review/bill-kotlin-code-review",
        name = "review-orchestrator.md",
        target = "orchestration/review-orchestrator/PLAYBOOK.md",
      )
    val manifest =
      PlatformManifest(
        slug = "kotlin",
        packRoot = external.toFileLocation(),
        contractVersion = "1",
        routingSignals = RoutingSignals(emptyList(), emptyList()),
        declaredCodeReviewAreas = emptyList(),
        declaredFiles = DeclaredFiles(baseline = null, areas = emptyMap()),
        areaMetadata = emptyMap(),
        pointers = listOf(pointer),
      )

    val hashed = pointerHash(skill, manifest, pointer, checkout)
    Files.writeString(decoy, "DECOY_POINTER_MARKER_CHANGED\n")
    assertEquals(hashed, pointerHash(skill, manifest, pointer, checkout))
    Files.writeString(playbook, "CHECKOUT_POINTER_MARKER_CHANGED\n")
    assertNotEquals(hashed, pointerHash(skill, manifest, pointer, checkout))

    assertFailsWith<IllegalArgumentException> {
      pointerHash(
        skill,
        manifest,
        pointer.copy(target = "../secret-playbook.md"),
        checkout,
      )
    }
    assertEquals("SECRET_POINTER_BYTES\n", Files.readString(outside))
  }

  private fun pointerHash(
    skill: Path,
    manifest: PlatformManifest,
    pointer: PointerSpec,
    checkout: Path,
  ): String =
    computeInstallContentHash(
      InstallContentHashInputs(
        sourceSkillDir = skill,
        authored = listOf(skill.resolve("content.md")),
        applicablePointers = listOf(manifest to pointer),
        checkoutRepoRoot = checkout,
      ),
    )

  private fun loader() = PlatformPackCatalogLoader(FileExternalPlatformPackSourceConfigStore())

  private fun context(
    repo: Path,
    home: Path,
    config: Path,
  ) = PlatformPackDiscoveryContext(
    repoRoot = repo,
    userHome = home,
    environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
    catalogLoader = loader(),
  )

  private fun installRequest(
    repo: Path,
    home: Path,
    config: Path,
  ): InstallPlanRequest {
    val skills = Files.createDirectories(repo.resolve("skills"))
    val packs = Files.createDirectories(repo.resolve("platform-packs"))
    return InstallPlanRequest(
      repoRoot = repo.toFileLocation(),
      home = home.toFileLocation(),
      agentSelection = InstallAgentSelection(mode = InstallAgentSelectionMode.DETECTED),
      platformPackSelection = PlatformPackSelection(mode = PlatformPackSelectionMode.ALL),
      telemetryLevel = InstallTelemetryLevel.OFF,
      mcpRegistrationChoice = McpRegistrationChoice(register = false),
      runtimeDistributionInputs = RuntimeDistributionInputs(runtimeInstallRoot = repo.toFileLocation()),
      targetPaths =
        InstallationTargetPaths(
          skillsRoot = skills.toFileLocation(),
          platformPacksRoot = packs.toFileLocation(),
        ),
      windowsSymlinkPreflight =
        WindowsSymlinkPreflight(
          state = WindowsSymlinkPreflightState.NOT_WINDOWS,
          decision = WindowsSymlinkDecision.NOT_REQUIRED,
        ),
      environment = mapOf(CONFIG_ENVIRONMENT_KEY to config.toString()),
    )
  }

  private fun reviewRoute(manifests: List<PlatformManifest>): Set<String> =
    ReviewStackRouting.route(
      manifests,
      listOf(ReviewRoutingChangedFile("src/Main.kt", "class Main")),
    ).routedSlugs

  private fun writeSources(
    config: Path,
    vararg packs: Path,
  ) {
    Files.createDirectories(config.parent)
    Files.writeString(
      config,
      JsonCodec.mapToJsonString(
        mapOf(
          "install_id" to "stable-id",
          ExternalPlatformPackConfigKeys.EXTERNAL_PLATFORM_PACK_SOURCES to
            packs.map { pack ->
              mapOf("path" to pack.toAbsolutePath().normalize().toString())
            },
        ),
      ) + "\n",
    )
  }

  private data class NativeAgentFixture(
    val root: Path,
    val home: Path,
    val repo: Path,
    val config: Path,
    val externalAgents: Path,
  )

  private fun nativeAgentFixture(root: Path): NativeAgentFixture {
    val home = Files.createDirectories(root.resolve("home"))
    val repo = Files.createDirectories(root.resolve("repo"))
    val config = home.resolve("config.json")
    seedExternalKotlinReplacement(root, repo, config)
    Files.createDirectories(repo.resolve("skills"))
    val bundledAgents = repo.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/native-agents")
    val externalAgents = root.resolve("external/kotlin/code-review/bill-kotlin-code-review/native-agents")
    Files.createDirectories(bundledAgents)
    Files.createDirectories(externalAgents)
    Files.writeString(
      bundledAgents.resolve("agents.yaml"),
      nativeAgentBundle("BUNDLED_AGENT_MARKER"),
    )
    Files.writeString(
      externalAgents.resolve("agents.yaml"),
      nativeAgentBundle("EXTERNAL_AGENT_MARKER"),
    )
    Files.createDirectories(home.resolve(".claude"))
    return NativeAgentFixture(root, home, repo, config, externalAgents)
  }

  private fun assertNativeAgentMarker(
    fixture: NativeAgentFixture,
    expected: String,
    unexpected: String,
  ) {
    val roots =
      effectivePackRootsForInstall(
        platformPacksRoot = fixture.repo.resolve("platform-packs"),
        userHome = fixture.home,
        environment = mapOf(CONFIG_ENVIRONMENT_KEY to fixture.config.toString()),
        selectedPlatforms = listOf("kotlin"),
        catalogLoader = loader(),
      )
    val rendered = renderNativeAgent(fixture, roots)
    assertTrue(Files.readString(rendered).contains(expected))
    assertFalse(Files.readString(rendered).contains(unexpected))
    val linked = linkNativeAgent(fixture, roots)
    assertTrue(Files.readString(linked).contains(expected))
    assertFalse(Files.readString(linked).contains(unexpected))
  }

  private fun renderNativeAgent(
    fixture: NativeAgentFixture,
    roots: List<Path>,
  ): Path {
    val rendered =
      NativeAgentOperations.renderInstallArtifacts(
        NativeAgentInstallRenderRequest(
          platformPacksRoot = fixture.repo.resolve("platform-packs"),
          skillsRoot = fixture.repo.resolve("skills"),
          selectedPlatforms = listOf("kotlin"),
          provider = NativeAgentProvider.Claude,
          home = fixture.home,
          compositionContext = installNativeAgentCompositionContext(roots),
          overrides =
            NativeAgentInstallRenderOverrides(
              cacheRoot = fixture.root.resolve("native-agent-cache"),
              sourceRoots = roots,
            ),
        ),
      )
    return rendered.generatedFiles.single { path ->
      path.fileName.toString() == "bill-kotlin-code-review.md"
    }
  }

  private fun linkNativeAgent(
    fixture: NativeAgentFixture,
    roots: List<Path>,
  ): Path {
    val outcome =
      InstallNativeAgentOperations.linkClaudeAgents(
        NativeAgentLinkRequest(
          platformPacksRoot = fixture.repo.resolve("platform-packs"),
          skillsRoot = fixture.repo.resolve("skills"),
          home = fixture.home,
          selectedPlatforms = listOf("kotlin"),
          overrides =
            NativeAgentLinkOverrides(
              installCacheRoot = fixture.root.resolve("native-agent-link-cache"),
              sourceRoots = roots,
            ),
          environment = mapOf(CONFIG_ENVIRONMENT_KEY to fixture.config.toString()),
          catalogLoader = loader(),
        ),
      )
    return (outcome.linked + outcome.skipped.map { skipped -> skipped.path })
      .single { path -> path.fileName.toString() == "bill-kotlin-code-review.md" }
  }

  private fun seedExternalKotlinReplacement(
    root: Path,
    repo: Path,
    config: Path,
  ) {
    writePack(
      repo.resolve("platform-packs/kotlin"),
      "kotlin",
      "BUNDLED_BASELINE_MARKER",
      listOf(".kt", "settings.gradle.kts"),
      "bundled-gate-collect",
    )
    writePack(
      root.resolve("external/kotlin"),
      "kotlin",
      "EXTERNAL_BASELINE_MARKER",
      listOf(".kt"),
      "external-gate-collect",
    )
    writePack(
      repo.resolve("platform-packs/node"),
      "node",
      "NODE_BASELINE_MARKER",
      listOf("package.json"),
      "node-gate-collect",
    )
    writePack(root.resolve("external/acme"), "acme", "ACME_BASELINE_MARKER", listOf(".acme"), "acme-gate-collect")
    writeSources(config, root.resolve("external/kotlin"), root.resolve("external/acme"))
  }

  private fun writePack(
    packRoot: Path,
    slug: String,
    marker: String,
    strong: List<String>,
    gate: String?,
  ) {
    writePack(packRoot, slug, marker, strong, PackFixtureOptions(gate = gate))
  }

  private fun writePack(
    packRoot: Path,
    slug: String,
    marker: String,
    strong: List<String>,
    options: PackFixtureOptions,
  ) {
    writePackBaseline(packRoot, slug, marker)
    writePackArea(packRoot, slug, options.areaMarker)
    Files.writeString(packRoot.resolve("platform.yaml"), packManifest(slug, strong, options))
  }

  private fun writePackBaseline(
    packRoot: Path,
    slug: String,
    marker: String,
  ) {
    val baseline = packRoot.resolve("code-review/bill-$slug-code-review/content.md")
    Files.createDirectories(baseline.parent)
    Files.writeString(baseline, reviewBody("bill-$slug-code-review", slug, marker))
  }

  private fun writePackArea(
    packRoot: Path,
    slug: String,
    areaMarker: String?,
  ) {
    if (areaMarker == null) return
    val area = packRoot.resolve("code-review/bill-$slug-code-review-architecture/content.md")
    Files.createDirectories(area.parent)
    Files.writeString(area, reviewBody("bill-$slug-code-review-architecture", slug, areaMarker))
  }

  private fun packManifest(
    slug: String,
    strong: List<String>,
    options: PackFixtureOptions,
  ): String {
    val signals = strong.joinToString("\n") { signal -> "    - \"$signal\"" }
    return buildString {
      appendLine("platform: $slug")
      appendLine("contract_version: \"${options.contractVersion}\"")
      appendLine("display_name: \"$slug\"")
      appendLine()
      appendLine("routing_signals:")
      appendLine("  strong:")
      appendLine(signals)
      appendLine("  tie_breakers: []")
      if (options.fallback) {
        appendLine("fallback_capabilities:")
        appendLine("  - code-review")
      }
      appendLine()
      append(packAreaYaml(options.areaMarker))
      appendLine("declared_files:")
      appendLine("  baseline: \"code-review/bill-$slug-code-review/content.md\"")
      append(packAreaFilesYaml(slug, options.areaMarker))
      append(packGateYaml(options.gate))
      append(packCompositionYaml(options.compositionSkill))
    }
  }

  private fun packAreaYaml(areaMarker: String?): String =
    if (areaMarker == null) {
      "declared_code_review_areas: []\n"
    } else {
      """
      declared_code_review_areas:
        - architecture
      """.trimIndent() + "\n"
    }

  private fun packAreaFilesYaml(
    slug: String,
    areaMarker: String?,
  ): String =
    if (areaMarker == null) {
      ""
    } else {
      "  areas:\n    architecture: \"code-review/bill-$slug-code-review-architecture/content.md\"\n"
    }

  private fun packGateYaml(gate: String?): String {
    if (gate == null) return ""
    return "\n" +
      """
      validation_gate:
        full_gate_command: ["$gate"]
        cache_bypassing_full_gate_command: ["$gate-nocache"]
        collect_all_full_gate_command: ["$gate"]
        cache_bypassing_collect_all_full_gate_command: ["$gate-nocache"]
        build_command: ["$gate-build"]
        cache_bypassing_build_command: ["$gate-build-nocache"]
        findings:
          format: junit_xml
          artifact_globs: ["build/test-results/**/*.xml"]
          compiler_diagnostics:
            format: gradle_kotlin_compiler_stdout
      """.trimIndent() + "\n"
  }

  private fun packCompositionYaml(compositionSkill: String?): String {
    if (compositionSkill == null) return ""
    return "\n" +
      """
      code_review_composition:
        baseline_layers:
          - platform: kotlin
            skill: $compositionSkill
            scope: same-review-scope
            required: true
            mode: kmp-baseline
      """.trimIndent() + "\n"
  }

  private fun reviewBody(
    skillName: String,
    platform: String,
    marker: String,
  ): String = renderContentBody(TemplateContext(skillName, "code-review", platform, "", platform), marker)

  private fun nativeAgentBundle(marker: String): String =
    """
    contract_version: "0.1"
    agents:
      - name: bill-kotlin-code-review
        description: "$marker"
        compose: governed-content
    """.trimIndent() + "\n"
}

private data class PackFixtureOptions(
  val gate: String? = null,
  val contractVersion: String = SHELL_CONTRACT_VERSION,
  val areaMarker: String? = null,
  val compositionSkill: String? = null,
  val fallback: Boolean = false,
)
