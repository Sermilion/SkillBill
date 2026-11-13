package skillbill.infrastructure.skills.install

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.install.reconcile.PublishedSourceReader
import skillbill.infrastructure.skills.install.reconcile.ReconcileContext
import skillbill.infrastructure.skills.install.reconcile.ReconcileInvocation
import skillbill.infrastructure.skills.install.reconcile.ReconcileSourceRoots
import skillbill.infrastructure.skills.install.reconcile.computeReconciliationPlan
import skillbill.infrastructure.skills.install.reconcile.installedStagingSourceReader
import skillbill.infrastructure.skills.install.reconcile.skillRelativePath
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.platformPackSchemaLog
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.install.model.BaselineManifest
import skillbill.install.model.BaselineManifest.Companion.sourceKeyFor
import skillbill.install.model.ReconciliationPlan
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.ports.install.baseline.model.ReadBaselineManifestRequest
import skillbill.ports.install.baseline.model.WriteBaselineManifestRequest
import skillbill.testing.seedConformingPlatformPack
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val STALE_CONTRACT_VERSION: String = "0.9"

class InstallReconcileTest : InstallApplyTestSupport() {
  private fun roots(repoRoot: Path) =
    ReconcileSourceRoots(
      repoRoot = repoRoot,
      skillsRoot = repoRoot.resolve("skills"),
      platformPacksRoot = repoRoot.resolve("platform-packs"),
    )

  private fun seedRepo(name: String): Path {
    val repoRoot = Files.createTempDirectory(name).also(tempDirs::add)
    seedBaseSkill(repoRoot, "bill-code-review")
    seedBaseSkill(repoRoot, "bill-code-check")
    seedBaseSkill(repoRoot, "skill-bill")
    Files.createDirectories(repoRoot.resolve("platform-packs"))
    return repoRoot
  }

  private fun home(): Path = Files.createTempDirectory("skillbill-reconcile-home").also(tempDirs::add)

  private fun planWith(
    upstream: Path,
    local: Path,
    home: Path,
    baseline: BaselineManifest,
    publishedStaging: Boolean = false,
  ) = computeReconciliationPlan(
    ReconcileInvocation(roots(upstream), roots(local), home),
    ReconcileContext(
      baseline,
      publishedSource = if (publishedStaging) installedStagingSourceReader(home) else PublishedSourceReader.NONE,
    ),
  )

  private fun outcomeFor(
    plan: ReconciliationPlan,
    path: String,
  ) = plan.outcomes.single { it.skillRelativePath == path }

  private fun baselineFromUpstream(
    upstream: Path,
    local: Path,
    home: Path,
  ): BaselineManifest =
    BaselineManifest.of(
      BaselineManifest.CONTRACT_VERSION,
      planWith(upstream, local, home, BaselineManifest.empty()).baselineOverlay,
    )

  @Test
  fun `reconciliation preserves local edits with and without a baseline`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    Files.writeString(local.resolve("skills/bill-code-review/content.md"), content("bill-code-review") + "\nlocal\n")

    Files.writeString(local.resolve("skills/bill-code-check/content.md"), content("bill-code-check") + "\nlocal\n")
    Files.writeString(
      upstream.resolve("skills/bill-code-check/content.md"),
      content("bill-code-check") + "\nupstream\n",
    )

    val withBaseline = planWith(upstream, local, home, baseline)
    val withoutBaseline = planWith(upstream, local, home, BaselineManifest.empty())

    listOf(withBaseline, withoutBaseline).forEach { plan ->
      listOf("skills/bill-code-review", "skills/bill-code-check").forEach { path ->
        assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, path))
        assertFalse(path in plan.baselineOverlay)
      }
    }
  }

  @Test
  fun `skill missing from the local copy is reinstalled from upstream`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    Files.walk(local.resolve("skills/bill-code-review")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val plan = planWith(upstream, local, home, baseline)

    val adopt = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "skills/bill-code-review"))
    assertEquals(null, adopt.localHash)
    assertEquals(adopt.upstreamHash, plan.baselineOverlay["skills/bill-code-review"])
  }

  @Test
  fun `a skill upstream no longer ships is pruned but a user-owned addon is preserved`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    seedBaseSkill(upstream, "bill-local-only")
    seedBaseSkill(local, "bill-local-only")
    val baseline = baselineFromUpstream(upstream, local, home)
    Files.walk(upstream.resolve("skills/bill-local-only")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
    seedAgentAddon(local, "local-helper", "LOCAL ONLY\n")

    val plan = planWith(upstream, local, home, baseline)

    val pruned = assertIs<SkillReconciliationOutcome.Prune>(outcomeFor(plan, "skills/bill-local-only"))
    val expectedLocalHash =
      planWith(local, local, home, BaselineManifest.empty())
        .baselineOverlay
        .getValue("skills/bill-local-only")
    assertEquals(expectedLocalHash, pruned.localHash)
    assertEquals(listOf("skills/bill-local-only"), plan.prunedPaths)
    assertFalse(plan.baselineOverlay.containsKey("skills/bill-local-only"))
    assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, "agent-addons/local-helper"))
  }

  @Test
  fun `identical inputs are unchanged with no baseline churn`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, local, home)

    val plan = planWith(upstream, local, home, baseline)

    assertTrue(plan.outcomes.isNotEmpty())
    assertTrue(plan.outcomes.all { it is SkillReconciliationOutcome.Unchanged })
    assertEquals(baseline.entries, baseline.withEntries(plan.baselineOverlay).entries)
  }

  @Test
  fun `agent addon reconcile hashes use baseline manifest hash width`() {
    val upstream = seedRepo("reconcile-upstream")
    val local = seedRepo("reconcile-local")
    val home = home()
    seedAgentAddon(upstream, "execution-budget", "UPSTREAM ADDON\n")

    val plan = planWith(upstream, local, home, BaselineManifest.empty())

    val addon = assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "agent-addons/execution-budget"))
    assertTrue(
      Regex("^[0-9a-f]{16}$").matches(addon.upstreamHash),
      "agent add-on baseline hashes must use the same 16-hex width as skill hashes: ${addon.upstreamHash}",
    )
  }

  private fun stalePackContractVersion(repoRoot: Path) {
    val manifest = repoRoot.resolve("platform-packs/generic/platform.yaml")
    val current = Files.readString(manifest)
    val stale =
      current.replace(
        "contract_version: \"$SHELL_CONTRACT_VERSION\"",
        "contract_version: \"$STALE_CONTRACT_VERSION\"",
      )
    assertTrue(stale != current, "fixture pack must declare contract_version $SHELL_CONTRACT_VERSION")
    Files.writeString(manifest, stale)
  }

  private fun <T> capturingSchemaRecords(block: () -> T): Pair<T, List<String>> {
    val records = mutableListOf<LogRecord>()
    val handler =
      object : Handler() {
        override fun publish(record: LogRecord) {
          records += record
        }

        override fun flush() = Unit

        override fun close() = Unit
      }
    platformPackSchemaLog.addHandler(handler)
    return try {
      block() to records.map { record -> record.message }
    } finally {
      platformPackSchemaLog.removeHandler(handler)
    }
  }

  @Test
  fun `stale local platform pack stays enumerable and preserves source edits`() {
    val upstream = seedRepo("reconcile-upstream-stale-local")
    val local = seedRepo("reconcile-local-stale")
    val home = home()
    seedPlatformPack(upstream, "generic")
    seedPlatformPack(local, "generic")
    stalePackContractVersion(local)
    Files.writeString(
      local.resolve("platform-packs/generic/code-review/bill-generic-code-review/content.md"),
      Files.readString(local.resolve("platform-packs/generic/code-review/bill-generic-code-review/content.md")) +
        "\nlocal stale copy\n",
    )

    val (plan, schemaRecords) =
      capturingSchemaRecords {
        planWith(upstream, local, home, BaselineManifest.empty())
      }

    val degradation = schemaRecords.first { it.contains("contract_version enforcement degraded") }
    assertTrue(degradation.contains("pack=generic"), degradation)
    assertTrue(degradation.contains("used=$STALE_CONTRACT_VERSION"), degradation)
    assertTrue(degradation.contains("expected=$SHELL_CONTRACT_VERSION"), degradation)

    val path = "platform-packs/generic/code-review/bill-generic-code-review"
    val authored = assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, path))
    assertTrue(authored.localHash.isNotBlank(), "the stale local pack must stay enumerable")
    assertFalse(path in plan.baselineOverlay)
  }

  @Test
  fun `stale upstream platform pack contract version fails the reconcile computation`() {
    val upstream = seedRepo("reconcile-upstream-stale-upstream")
    val local = seedRepo("reconcile-local-current")
    val home = home()
    seedPlatformPack(upstream, "generic")
    seedPlatformPack(local, "generic")
    stalePackContractVersion(upstream)

    assertFailsWith<SkillBillRuntimeException> {
      planWith(upstream, local, home, BaselineManifest.empty())
    }.also { assertEquals(InstallFailureCode.CONTRACT_VERSION_MISMATCH, it.code) }
  }

  @Test
  fun `edited pack specialists do not block dispatcher rename adoption on legacy baselines`() {
    val upstream = seedRepo("reconcile-dispatcher-adopt-upstream")
    val local = seedRepo("reconcile-dispatcher-adopt-local")
    seedConformingPlatformPack(upstream, "generic", areaNames = listOf("architecture", "security"))
    seedConformingPlatformPack(local, "generic", areaNames = listOf("architecture", "security"))
    val home = home()
    val legacyBaseline =
      BaselineManifest.of(
        BaselineManifest.CONTRACT_VERSION,
        planWith(upstream, local, home, BaselineManifest.empty()).baselineOverlay.filterKeys {
            key ->
          !key.endsWith("#source")
        },
      )

    val specialistPath = "platform-packs/generic/code-review/bill-generic-code-review-security"
    Files.writeString(
      local.resolve("$specialistPath/content.md"),
      Files.readString(local.resolve("$specialistPath/content.md")) + "\nLOCAL SECURITY EDIT\n",
    )
    Files.writeString(upstream.resolve("skills/skill-bill/content.md"), content("sona"))

    val dispatcherDir = local.resolve("skills/skill-bill")
    seedPublishedSkillStaging(home, dispatcherDir, listOf(dispatcherDir.resolve("content.md")))

    val plan = planWith(upstream, local, home, legacyBaseline, publishedStaging = true)

    assertIs<SkillReconciliationOutcome.Adopt>(outcomeFor(plan, "skills/skill-bill"))
    assertIs<SkillReconciliationOutcome.LocallyAuthored>(outcomeFor(plan, specialistPath))
    assertTrue(plan.baselineOverlay.containsKey(sourceKeyFor("skills/skill-bill")))
  }

  @Test
  fun `baseline persistence round-trips with sorted byte-stable writes`() {
    val home = home()
    val persistence = FileSystemBaselineManifestPersistence()

    val initial = persistence.readBaseline(ReadBaselineManifestRequest(home))
    assertFalse(initial.existed)
    assertTrue(initial.manifest.entries.isEmpty())

    val manifest =
      BaselineManifest.of(
        BaselineManifest.CONTRACT_VERSION,
        mapOf(
          "skills/bill-zebra" to "00112233aabbccdd",
          "skills/bill-alpha" to "ffeeddccbbaa9988",
        ),
      )
    val writeResult = persistence.writeBaseline(WriteBaselineManifestRequest(home, manifest))
    val firstBytes = Files.readAllBytes(writeResult.path)

    val readBack = persistence.readBaseline(ReadBaselineManifestRequest(home))
    assertTrue(readBack.existed)
    assertEquals(manifest.entries, readBack.manifest.entries)

    val text = String(firstBytes)
    assertTrue(text.indexOf("bill-alpha") < text.indexOf("bill-zebra"), "baseline keys must be sorted: $text")

    persistence.writeBaseline(WriteBaselineManifestRequest(home, readBack.manifest))
    val secondBytes = Files.readAllBytes(writeResult.path)
    assertTrue(firstBytes.contentEquals(secondBytes), "no-change rewrite must be byte-identical")
  }

  private fun seedAgentAddon(
    repo: Path,
    slug: String,
    body: String,
  ) {
    val root = repo.resolve("agent-addons/$slug")
    Files.createDirectories(root)
    Files.writeString(
      root.resolve("agent-addon.yaml"),
      """
      contract_version: "1.0"
      slug: $slug
      description: Test helper
      agent_ids:
        - codex
      consumers:
        - skill-bill
      """.trimIndent() + "\n",
    )
    Files.writeString(root.resolve("content.md"), body)
  }
}
