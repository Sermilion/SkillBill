package skillbill.infrastructure.skills.install

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.install.reconcile.ReconcileSourceRoots
import skillbill.infrastructure.skills.install.reconcile.applyReconciliation
import skillbill.infrastructure.skills.install.reconcile.computeReconciliationPlan
import skillbill.install.model.BaselineManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
      computeReconciliationPlan(roots(upstream), roots(upstream), home, BaselineManifest.empty()).baselineOverlay,
    )

  private fun reviewContent(repoRoot: Path): Path = repoRoot.resolve("skills/bill-code-review/content.md")

  @Test
  fun `upstream bytes overwrite the live skill even when the user edited it`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val upstreamReviewBytes = content("bill-code-review") + "\nUPSTREAM REVIEW\n"
    Files.writeString(reviewContent(upstream), upstreamReviewBytes)
    Files.writeString(reviewContent(local), content("bill-code-review") + "\nLOCAL REVIEW\n")
    Files.writeString(
      local.resolve("skills/bill-code-check/content.md"),
      content("bill-code-check") + "\nLOCAL CHECK EDIT\n",
    )

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline)

    assertEquals(upstreamReviewBytes, Files.readString(reviewContent(local)))
    assertEquals(content("bill-code-check"), Files.readString(local.resolve("skills/bill-code-check/content.md")))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
    assertTrue(output.installedPaths.contains("skills/bill-code-check"))
    assertEquals(
      baselineFromUpstream(upstream, home).entries,
      baseline.withEntries(output.plan.baselineOverlay).entries,
    )
  }

  @Test
  fun `a skill upstream no longer ships is deleted from the live tree`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    seedBaseSkill(local, "bill-local-only")

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline)

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
      applyReconciliation(roots(upstream), roots(local), home, BaselineManifest.empty())
    }.also { assertEquals(InstallFailureCode.RECONCILIATION_CONFLICT, it.code) }
    assertEquals(liveBytes, Files.readString(reviewContent(local)), "a refused prune must change nothing")
  }

  @Test
  fun `edited agent addon is replaced from upstream while a locally-authored one survives`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedAgentAddon(upstream, "review-helper", "UPSTREAM\n")
    seedAgentAddon(local, "review-helper", "UPSTREAM\n")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)
    Files.writeString(local.resolve("agent-addons/review-helper/content.md"), "LOCAL EDIT\n")
    seedAgentAddon(local, "local-helper", "LOCAL ONLY\n")

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline)

    assertEquals("UPSTREAM\n", Files.readString(local.resolve("agent-addons/review-helper/content.md")))
    assertEquals("LOCAL ONLY\n", Files.readString(local.resolve("agent-addons/local-helper/content.md")))
    assertTrue(output.installedPaths.contains("agent-addons/review-helper"))
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

    val output = applyReconciliation(roots(upstream), roots(localRepo), home, BaselineManifest.empty())

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

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline)

    assertTrue(Files.isRegularFile(reviewContent(local)), "missing live skill must be restored from upstream")
    assertEquals(content("bill-code-review"), Files.readString(reviewContent(local)))
    assertTrue(output.installedPaths.contains("skills/bill-code-review"))
  }

  @Test
  fun `edited platform-pack skill is replaced and pack-level metadata is adopted`() {
    val upstream = seedRepo("apply-upstream")
    val local = seedRepo("apply-local")
    seedPlatformPack(upstream, "kotlin")
    seedPlatformPack(local, "kotlin")
    val home = home()
    val baseline = baselineFromUpstream(upstream, home)

    val packSkill = local.resolve("platform-packs/kotlin/code-review/bill-kotlin-code-review/content.md")
    val upstreamBytes = Files.readString(packSkill)
    Files.writeString(packSkill, upstreamBytes + "\nUSER PACK EDIT\n")
    Files.writeString(upstream.resolve("platform-packs/kotlin/ADDON.md"), "# upstream addon\n")

    val output = applyReconciliation(roots(upstream), roots(local), home, baseline)

    assertEquals(upstreamBytes, Files.readString(packSkill), "an edited pack skill must be replaced from upstream")
    assertTrue(output.installedPaths.contains("platform-packs/kotlin/code-review/bill-kotlin-code-review"))
    assertContains(Files.readString(local.resolve("platform-packs/kotlin/ADDON.md")), "upstream addon")
  }

  @Test
  fun `apply is idempotent - second run installs nothing and leaves bytes identical`() {
    val upstream = seedRepo("apply-upstream")
    val local = Files.createTempDirectory("apply-local-fresh").also(tempDirs::add)
    Files.createDirectories(local.resolve("platform-packs"))
    val home = home()
    val first = applyReconciliation(roots(upstream), roots(local), home, BaselineManifest.empty())
    assertTrue(first.installedPaths.isNotEmpty())
    val refreshed = BaselineManifest.of(BaselineManifest.CONTRACT_VERSION, first.plan.baselineOverlay)
    val reviewBytesAfterFirst = Files.readString(reviewContent(local))

    val second = applyReconciliation(roots(upstream), roots(local), home, refreshed)

    assertTrue(second.installedPaths.isEmpty(), "idempotent apply must perform zero replacements")
    assertEquals(refreshed.entries, refreshed.withEntries(second.plan.baselineOverlay).entries)
    assertEquals(reviewBytesAfterFirst, Files.readString(reviewContent(local)))
  }
}
