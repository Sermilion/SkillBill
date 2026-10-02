package skillbill.infrastructure.skills.scaffold

import skillbill.error.shellcontent.InvalidScaffoldPayloadError
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformPack
import skillbill.infrastructure.skills.scaffold.rendering.inferSkillDescription
import skillbill.infrastructure.skills.scaffold.rendering.renderContentBody
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.TemplateContext
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.supportingFileTargets
import skillbill.infrastructure.skills.scaffold.runtime.service.scaffold
import skillbill.install.model.PACK_SIDECAR_PARENT_SKILL
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest
import skillbill.scaffold.policy.platformpack.renderPlatformPackManifest
import skillbill.testsupport.SkillClassFixtures
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ScaffoldBaselineLayerPayloadTest {
  @Test
  fun `platform pack payload writes baseline layer composition to manifest`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val result =
        scaffold(
          payload(repo, "platform-pack", "platform" to "androidx") +
            mapOf(
              "routing_signals" to mapOf("strong" to listOf("androidx")),
              "baseline_layers" to kotlinBaselinePayload(),
            ),
        )
      val manifest = Files.readString(repo.resolve("platform-packs/androidx/platform.yaml"))
      val pack = loadPlatformPack(repo.resolve("platform-packs/androidx"))

      assertEquals("platform-pack", result.kind)
      assertContains(manifest, "code_review_composition:")
      assertContains(manifest, "baseline_layers:")
      assertContains(manifest, "platform: \"kotlin\"")
      assertContains(manifest, "skill: \"bill-kotlin-code-review\"")
      assertContains(manifest, "scope: \"same-review-scope\"")
      assertContains(manifest, "required: true")
      assertContains(manifest, "mode: \"kmp-baseline\"")
      assertEquals("kotlin", pack.codeReviewComposition?.baselineLayers?.single()?.platform)
    }

  @Test
  fun `legacy platform pack payload without baseline layers omits composition section`() =
    withIsolatedUserHome {
      val repo = seedRepo()

      scaffold(
        payload(repo, "platform-pack", "platform" to "legacy") +
          mapOf("routing_signals" to mapOf("strong" to listOf("legacy.marker"))),
      )

      val manifest = Files.readString(repo.resolve("platform-packs/legacy/platform.yaml"))
      assertFalse("code_review_composition:" in manifest)
      assertEquals(null, loadPlatformPack(repo.resolve("platform-packs/legacy")).codeReviewComposition)
    }

  @Test
  fun `platform pack dry run previews the same composition manifest execute writes`() =
    withIsolatedUserHome {
      val dryRunRepo = seedRepo()
      val executeRepo = seedRepo()
      val payload =
        mapOf(
          "scaffold_payload_version" to "1.0",
          "kind" to "platform-pack",
          "platform" to "androidx",
          "routing_signals" to mapOf("strong" to listOf("androidx")),
          "baseline_layers" to kotlinBaselinePayload(),
        )

      val dryRun = scaffold(payload + ("repo_root" to dryRunRepo.toString()), dryRun = true)
      val execute = scaffold(payload + ("repo_root" to executeRepo.toString()), dryRun = false)
      val executeManifest = Files.readString(executeRepo.resolve("platform-packs/androidx/platform.yaml"))

      assertEquals(
        listOf(dryRunRepo.resolve("platform-packs/androidx/platform.yaml")),
        dryRun.manifestEdits.map {
          it.toPath()
        },
      )
      assertFalse(Files.exists(dryRunRepo.resolve("platform-packs/androidx/platform.yaml")))
      assertEquals(
        executeManifest,
        dryRun.manifestPreviews.getValue(dryRunRepo.resolve("platform-packs/androidx/platform.yaml").toFileLocation()),
      )
      assertContains(executeManifest, "code_review_composition:")
      assertContains(
        execute.manifestEdits.map { it.toPath() },
        executeRepo.resolve("platform-packs/androidx/platform.yaml"),
      )
    }

  @Test
  fun `invalid baseline layer payloads fail before mutation and preserve repo bytes`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val invalidPayloads =
        listOf(
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("platform" to "missing"))) to
            "missing platform pack",
          mapOf(
            "baseline_layers" to listOf(kotlinBaselinePayloadEntry("skill" to "bill-kotlin-code-review-missing")),
          ) to
            "missing code-review skill",
          selfReferencePayload() to "self-references",
          mapOf("baseline_layers" to kotlinBaselinePayload() + kotlinBaselinePayload()) to "duplicate layer",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("scope" to "other-scope"))) to
            "unsupported value",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("mode" to "other-mode"))) to
            "unsupported value",
        )

      invalidPayloads.forEachIndexed { index, (extraPayload, expectedMessage) ->
        val before = snapshotTree(repo)
        val platform = extraPayload["platform"] as? String ?: "androidx-$index"
        val error =
          assertFailsWith<InvalidScaffoldPayloadError> {
            scaffold(
              payload(repo, "platform-pack", "platform" to platform) +
                mapOf("routing_signals" to mapOf("strong" to listOf("marker-$index"))) +
                extraPayload,
            )
          }

        assertContains(error.message.orEmpty(), expectedMessage)
        assertEquals(before, snapshotTree(repo))
        assertFalse(Files.exists(repo.resolve("platform-packs/$platform")))
      }
    }

  @Test
  fun `structurally invalid baseline layer payloads fail before mutation and preserve repo bytes`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      val invalidPayloads =
        listOf(
          mapOf("baseline_layers" to "not-a-list") to "must be a list",
          mapOf("baseline_layers" to emptyList<Map<String, Any?>>()) to "at least one layer",
          mapOf("baseline_layers" to listOf("not-an-object")) to "must be an object",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry() - "platform")) to
            "baseline_layers[0].platform",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("platform" to ""))) to
            "baseline_layers[0].platform",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry() - "skill")) to
            "baseline_layers[0].skill",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("skill" to " "))) to
            "baseline_layers[0].skill",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry() - "scope")) to
            "baseline_layers[0].scope",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("scope" to ""))) to
            "baseline_layers[0].scope",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry() - "mode")) to
            "baseline_layers[0].mode",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("mode" to " "))) to
            "baseline_layers[0].mode",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry() - "required")) to
            "baseline_layers[0].required",
          mapOf("baseline_layers" to listOf(kotlinBaselinePayloadEntry("required" to "true"))) to
            "baseline_layers[0].required",
        )

      invalidPayloads.forEachIndexed { index, (extraPayload, expectedMessage) ->
        val before = snapshotTree(repo)
        val platform = "androidx-structural-$index"
        val error =
          assertFailsWith<InvalidScaffoldPayloadError> {
            scaffold(
              payload(repo, "platform-pack", "platform" to platform) +
                mapOf("routing_signals" to mapOf("strong" to listOf("marker-$index"))) +
                extraPayload,
            )
          }

        assertContains(error.message.orEmpty(), expectedMessage)
        assertEquals(before, snapshotTree(repo))
        assertFalse(Files.exists(repo.resolve("platform-packs/$platform")))
      }
    }

  @Test
  fun `baseline layers are rejected for non platform pack payloads before mutation`() =
    withIsolatedUserHome {
      val repo = seedRepo()
      listOf(
        payload(repo, "horizontal", "name" to "bill-not-a-pack"),
        payload(repo, "add-on", "platform" to "kotlin", "name" to "review-helper"),
      ).forEach { basePayload ->
        val before = snapshotTree(repo)
        val kind = basePayload.getValue("kind")
        val error =
          assertFailsWith<InvalidScaffoldPayloadError> {
            scaffold(basePayload + mapOf("baseline_layers" to kotlinBaselinePayload()))
          }

        assertContains(error.message.orEmpty(), "only supported for kind 'platform-pack'")
        assertContains(error.message.orEmpty(), "got '$kind'")
        assertEquals(before, snapshotTree(repo))
      }
    }
}

private fun selfReferencePayload(): Map<String, Any?> =
  mapOf(
    "platform" to "androidx-self",
    "baseline_layers" to
      listOf(
        kotlinBaselinePayloadEntry(
          "platform" to "androidx-self",
          "skill" to "bill-androidx-self-code-review",
        ),
      ),
  )

private fun payload(
  repo: Path,
  kind: String,
  vararg pairs: Pair<String, Any?>,
): Map<String, Any?> =
  mapOf("scaffold_payload_version" to "1.0", "kind" to kind, "repo_root" to repo.toString()) + pairs

private fun kotlinBaselinePayload(): List<Map<String, Any?>> = listOf(kotlinBaselinePayloadEntry())

private fun kotlinBaselinePayloadEntry(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
  mapOf(
    "platform" to "kotlin",
    "skill" to "bill-kotlin-code-review",
    "scope" to "same-review-scope",
    "required" to true,
    "mode" to "kmp-baseline",
  ) + overrides

private fun seedRepo(): Path {
  val repo = Files.createTempDirectory("skillbill-baseline-layer-scaffold-repo")
  SkillClassFixtures.seedShippedSkillClasses(repo)
  supportingFileTargets(repo).values.forEach { target ->
    Files.createDirectories(target.parent)
    Files.writeString(target, "# ${target.fileName}\n")
  }
  seedBaseSkill(repo, "bill-code-check")
  seedBaseSkill(repo, PACK_SIDECAR_PARENT_SKILL)
  seedKotlinPack(repo)
  seedKmpPack(repo)
  return repo
}

private fun seedBaseSkill(
  repo: Path,
  name: String,
) {
  val context = TemplateContext(name, "advisor", "", "", "")
  val skillDir = repo.resolve("skills/$name")
  Files.createDirectories(skillDir)
  Files.writeString(skillDir.resolve("content.md"), renderContentBody(context, inferSkillDescription(context)))
}

private fun seedKotlinPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kotlin")
  val baseline = packRoot.resolve("code-review/bill-kotlin-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kotlin-code-review", "code-review", "kotlin", "", "Kotlin")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      PlatformPackManifestRenderRequest(
        platform = "kotlin",
        displayName = "Kotlin",
        strongSignals = listOf(".kt"),
        baselineContentPath = "code-review/bill-kotlin-code-review/content.md",
      ),
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun seedKmpPack(repo: Path) {
  val packRoot = repo.resolve("platform-packs/kmp")
  val baseline = packRoot.resolve("code-review/bill-kmp-code-review")
  Files.createDirectories(baseline)
  val context = TemplateContext("bill-kmp-code-review", "code-review", "kmp", "", "KMP")
  Files.writeString(
    packRoot.resolve("platform.yaml"),
    renderPlatformPackManifest(
      PlatformPackManifestRenderRequest(
        platform = "kmp",
        displayName = "KMP",
        strongSignals = listOf(".kt"),
        baselineContentPath = "code-review/bill-kmp-code-review/content.md",
      ),
    ),
  )
  Files.writeString(
    baseline.resolve("content.md"),
    renderContentBody(context, inferSkillDescription(context)),
  )
}

private fun withIsolatedUserHome(block: () -> Unit) {
  val originalHome = System.getProperty("user.home")
  val tempHome = Files.createTempDirectory("skillbill-baseline-layer-no-agents-home")
  try {
    System.setProperty("user.home", tempHome.toString())
    block()
  } finally {
    System.setProperty("user.home", originalHome)
  }
}

private fun snapshotTree(root: Path): Map<String, String> {
  if (!Files.exists(root)) {
    return emptyMap()
  }
  return Files.walk(root).use { stream ->
    stream
      .filter { path -> path != root }
      .sorted()
      .toList()
      .associate { path ->
        val key = root.relativize(path).toString()
        val value =
          when {
            Files.isSymbolicLink(path) -> "symlink:${Files.readSymbolicLink(path)}"
            Files.isDirectory(path) -> "dir"
            else -> "file:${Files.readString(path)}"
          }
        key to value
      }
  }
}
