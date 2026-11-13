package skillbill.infrastructure.skills.install

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.install.reconcile.ReconcileContext
import skillbill.infrastructure.skills.install.reconcile.ReconcileInvocation
import skillbill.infrastructure.skills.install.reconcile.ReconcileSourceRoots
import skillbill.infrastructure.skills.install.reconcile.applyReconciliation
import skillbill.infrastructure.skills.install.reconcile.computeReconciliationPlan
import skillbill.infrastructure.skills.install.reconcile.installedStagingSourceReader
import skillbill.install.model.BaselineManifest
import skillbill.install.model.BaselineManifest.Companion.sourceKeyFor
import skillbill.install.model.SkillReconciliationOutcome
import skillbill.testing.seedConformingPlatformPack
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallReconcileApplyTest : InstallApplyTestSupport() {
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

  private fun home(): Path = Files.createTempDirectory("skillbill-apply-home").also(tempDirs::add)

  private fun baselineFromUpstream(
    upstream: Path,
    home: Path,
  ): BaselineManifest =
    BaselineManifest.of(
      BaselineManifest.CONTRACT_VERSION,
      computeReconciliationPlan(
        ReconcileInvocation(roots(upstream), roots(upstream), home),
        ReconcileContext(BaselineManifest.empty()),
      ).baselineOverlay,
    )

  private fun reviewContent(repoRoot: Path): Path = repoRoot.resolve("skills/bill-code-review/content.md")

  @Test
  fun `upgrade preserves an edited skill and updates an unedited skill`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val upstreamReviewBytes = content("bill-code-review") + "\nUPSTREAM REVIEW\n"
    Files.writeString(reviewContent(upstream), upstreamReviewBytes)
    val localReviewBytes = content("bill-code-review") + "\nLOCAL REVIEW\n"
    Files.writeString(reviewContent(local), localReviewBytes)
    val upstreamCheckBytes = content("bill-code-check") + "\nUPSTREAM CHECK\n"
    Files.writeString(
      upstream.resolve("skills/bill-code-check/content.md"),
      upstreamCheckBytes,
    )

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertEquals(localReviewBytes, Files.readString(reviewContent(local)))
    assertEquals(upstreamCheckBytes, Files.readString(local.resolve("skills/bill-code-check/content.md")))
    assertFalse(output.installedPaths.contains("skills/bill-code-review"))
    assertTrue(output.installedPaths.contains("skills/bill-code-check"))
    assertFalse(output.plan.baselineOverlay.containsKey("skills/bill-code-review"))
    assertEquals(
      baselineFromUpstream(upstream, home).hashFor("skills/bill-code-check"),
      baseline.withEntries(output.plan.baselineOverlay).hashFor("skills/bill-code-check"),
    )
  }

  @Test
  fun `failed publication and restoration retain and report the previous skill backup`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    val oldBytes = Files.readString(reviewContent(local))
    Files.writeString(reviewContent(upstream), oldBytes + "\nUPSTREAM CHANGE\n")

    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        applyReconciliation(
          ReconcileInvocation(roots(upstream), roots(local), home),
          ReconcileContext(baseline),
          moveDirectory = { source, target ->
            if (source.parent.fileName.toString().startsWith(".reconcile-stage-") ||
              source.fileName.toString().startsWith(".reconcile-backup-")
            ) {
              throw IOException("synthetic move failure")
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
          },
        )
      }

    assertEquals(InstallFailureCode.RECONCILIATION_CONFLICT, failure.code)
    assertContains(failure.message.orEmpty(), "publication and restoration failed")
    val backupPath = failure.message.orEmpty().substringAfter("previous source remains at '").substringBefore("'.")
    val backup = Path.of(backupPath)
    assertTrue(Files.isRegularFile(backup.resolve("content.md")))
    assertEquals(oldBytes, Files.readString(backup.resolve("content.md")))
    assertEquals(1, failure.suppressed.size)
    assertContains(failure.cause?.message.orEmpty(), "synthetic move failure")
    assertContains(failure.suppressed.single().message.orEmpty(), "synthetic move failure")
  }

  @Test
  fun `upgrade adopts dispatcher rename when only pack specialists were edited`() {
    val upstream = seedRepo("apply-dispatcher-adopt-upstream")
    val local = seedRepo("apply-dispatcher-adopt-local")
    seedConformingPlatformPack(upstream, "generic", areaNames = listOf("architecture", "security"))
    seedConformingPlatformPack(local, "generic", areaNames = listOf("architecture", "security"))
    val home = home()
    val legacyBaseline =
      BaselineManifest.of(
        BaselineManifest.CONTRACT_VERSION,
        computeReconciliationPlan(
          ReconcileInvocation(roots(upstream), roots(upstream), home),
          ReconcileContext(BaselineManifest.empty()),
        ).baselineOverlay
          .filterKeys { key -> !key.endsWith("#source") },
      )
    val specialist = local.resolve("platform-packs/generic/code-review/bill-generic-code-review-security/content.md")
    Files.writeString(specialist, Files.readString(specialist) + "\nLOCAL SECURITY EDIT\n")
    val upstreamSona = content("sona")
    Files.writeString(upstream.resolve("skills/skill-bill/content.md"), upstreamSona)
    val dispatcherDir = local.resolve("skills/skill-bill")
    seedPublishedSkillStaging(home, dispatcherDir, listOf(dispatcherDir.resolve("content.md")))

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(legacyBaseline, publishedSource = installedStagingSourceReader(home)),
      )

    assertTrue(output.installedPaths.contains("skills/skill-bill"))
    assertEquals(upstreamSona, Files.readString(dispatcherDir.resolve("content.md")))
    assertIs<SkillReconciliationOutcome.LocallyAuthored>(
      output.plan.outcomes.single { it.skillRelativePath.contains("bill-generic-code-review-security") },
    )
    assertTrue(output.plan.baselineOverlay.containsKey(sourceKeyFor("skills/skill-bill")))
  }

  @Test
  fun `upgrade preserves local dispatcher guidance for compatibility rendering`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    val dispatcher = local.resolve("skills/skill-bill/content.md")
    val locallyAuthored = Files.readString(dispatcher) + "\nUse the team's local launch checklist.\n"
    Files.writeString(dispatcher, locallyAuthored)

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertEquals(locallyAuthored, Files.readString(dispatcher))
    assertFalse(output.installedPaths.contains("skills/skill-bill"))
  }

  @Test
  fun `a skill upstream no longer ships is deleted from the live tree`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    seedBaseSkill(upstream, "bill-local-only")
    seedBaseSkill(local, "bill-local-only")
    val baseline = baselineFromUpstream(upstream, home)
    Files.walk(upstream.resolve("skills/bill-local-only")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertFalse(
      Files.exists(local.resolve("skills/bill-local-only")),
      "a skill with no upstream counterpart must be pruned",
    )
    assertEquals(listOf("skills/bill-local-only"), output.prunedPaths)
    assertFalse(output.plan.baselineOverlay.containsKey("skills/bill-local-only"))
  }

  @Test
  fun `pruning is refused when the upstream tree enumerated no skills at all`() {
    val upstream = Files.createTempDirectory("apply-upstream-empty").also(tempDirs::add)
    Files.createDirectories(upstream.resolve("platform-packs"))
    val local = seedRepo("apply-local")
    val home = home()
    val liveBytes = Files.readString(reviewContent(local))

    assertFailsWith<SkillBillRuntimeException> {
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(BaselineManifest.empty()),
      )
    }.also { assertEquals(InstallFailureCode.RECONCILIATION_CONFLICT, it.code) }
    assertEquals(liveBytes, Files.readString(reviewContent(local)), "a refused prune must change nothing")
  }

  @Test
  fun `reconciliation preserves edited and locally authored agent addons`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedAgentAddon(upstream, "review-helper", "UPSTREAM\n")
    seedAgentAddon(local, "review-helper", "UPSTREAM\n")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    Files.writeString(local.resolve("agent-addons/review-helper/content.md"), "LOCAL EDIT\n")
    seedAgentAddon(local, "local-helper", "LOCAL ONLY\n")

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertEquals("LOCAL EDIT\n", Files.readString(local.resolve("agent-addons/review-helper/content.md")))
    assertEquals("LOCAL ONLY\n", Files.readString(local.resolve("agent-addons/local-helper/content.md")))
    assertFalse(output.installedPaths.contains("agent-addons/review-helper"))
    assertFalse(output.installedPaths.contains("agent-addons/local-helper"))
    assertFalse(output.prunedPaths.contains("agent-addons/local-helper"))
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

  @Test
  fun `first install materializes every upstream skill into a missing live tree`() {
    val upstream = seedRepo("apply-upstream")
    val localRepo = Files.createTempDirectory("apply-local-empty").also(tempDirs::add)
    val home = home()

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(localRepo), home),
        ReconcileContext(BaselineManifest.empty()),
      )

    assertTrue(Files.isRegularFile(reviewContent(localRepo)), "first install must materialize the skill")
    assertEquals(content("bill-code-review"), Files.readString(reviewContent(localRepo)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    assertTrue(output.installedPaths.contains("skills/bill-code-check"))
  }

  @Test
  fun `a skill deleted from the live tree is restored from upstream`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    Files.walk(local.resolve("skills/bill-code-review")).use { stream ->
      stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertTrue(Files.isRegularFile(reviewContent(local)), "missing live skill must be restored from upstream")
    assertEquals(content("bill-code-review"), Files.readString(reviewContent(local)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
  }

  @Test
  fun `edited platform skill survives while shipped pack metadata is updated`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedPlatformPack(upstream, "kotlin")
    seedPlatformPack(local, "kotlin")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val packSkill = local.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md")
    val upstreamBytes = Files.readString(packSkill)
    Files.writeString(packSkill, upstreamBytes + "\nUSER PACK EDIT\n")
    Files.writeString(local.resolve("platform-packs/kotlin/ADDON.md"), "# old addon\n")
    Files.writeString(upstream.resolve("platform-packs/kotlin/ADDON.md"), "# upstream addon\n")

    val output =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(baseline),
      )

    assertEquals(upstreamBytes + "\nUSER PACK EDIT\n", Files.readString(packSkill))
    assertFalse(output.installedPaths.contains("platform-packs/kotlin/code-review/bill-kotlin-code-review"))
    assertContains(Files.readString(local.resolve("platform-packs/kotlin/ADDON.md")), "upstream addon")
  }

  @Test
  fun `apply is idempotent - second run installs nothing and leaves bytes identical`() {
    val upstream = seedRepo("apply-upstream")
    val local = Files.createTempDirectory("apply-local-fresh").also(tempDirs::add)
    Files.createDirectories(local.resolve("platform-packs"))
    val home = home()
    val first =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(BaselineManifest.empty()),
      )
    assertTrue(first.installedPaths.isNotEmpty())
    val refreshed = BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, first.plan.baselineOverlay)
    val reviewBytesAfterFirst = Files.readString(reviewContent(local))

    val second =
      applyReconciliation(
        ReconcileInvocation(roots(upstream), roots(local), home),
        ReconcileContext(refreshed),
      )

    assertTrue(second.installedPaths.isEmpty(), "idempotent apply must perform zero replacements")
    assertEquals(refreshed.entries, refreshed.withEntries(second.plan.baselineOverlay).entries)
    assertEquals(reviewBytesAfterFirst, Files.readString(reviewContent(local)))
  }
}
