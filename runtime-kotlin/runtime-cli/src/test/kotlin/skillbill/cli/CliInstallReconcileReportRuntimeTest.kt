package skillbill.cli

import skillbill.cli.core.CliRuntime
import skillbill.cli.model.CliRuntimeContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CliInstallReconcileReportRuntimeTest {
  private fun seedSkill(
    repoRoot: Path,
    name: String,
    body: String,
  ) {
    val skillDir = repoRoot.resolve("skills/$name")
    Files.createDirectories(skillDir)
    Files.writeString(
      skillDir.resolve("content.md"),
      "---\nname: $name\ndescription: Test skill.\n---\n\n$body\n",
    )
  }

  private fun context(home: Path): CliRuntimeContext = CliRuntimeContext(userHome = home, environment = emptyMap())

  private fun reconcileArgs(
    home: Path,
    local: Path,
    upstream: Path,
    apply: Boolean,
  ): List<String> =
    buildList {
      addAll(listOf("--home", home.toString(), "install", "reconcile"))
      if (apply) add("--apply")
      addAll(
        listOf(
          "--repo-root", local.toString(),
          "--skills", local.resolve("skills").toString(),
          "--platform-packs", local.resolve("platform-packs").toString(),
          "--upstream-repo-root", upstream.toString(),
          "--upstream-skills", upstream.resolve("skills").toString(),
          "--upstream-platform-packs", upstream.resolve("platform-packs").toString(),
        ),
      )
    }

  @Test
  fun `real reconcile command emits the stable line report for a first-install plan`() {
    val home = Files.createTempDirectory("skillbill-reconcile-report-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-report-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "Upstream body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    val result = CliRuntime.run(reconcileArgs(home, local, upstream, apply = false), context(home))

    assertEquals(0, result.exitCode, result.stdout)
    val outcomeLine = result.stdout.lines().single { it.startsWith("reconcile_outcome:") }
    assertTrue(outcomeLine.startsWith("reconcile_outcome: kind=adopt "), outcomeLine)
    assertContains(outcomeLine, "upstream_hash=")
    assertTrue(outcomeLine.endsWith(" path=skills/bill-sample"), outcomeLine)
    val summaryLine = result.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(summaryLine, "applied=false")
  }

  @Test
  fun `a locally diverged skill is reported as locally authored and preserved by apply`() {
    val home = Files.createTempDirectory("skillbill-reconcile-diverged-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-diverged-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "BASE body.")
    seedSkill(local, "bill-sample", "BASE body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    val seed = CliRuntime.run(reconcileArgs(home, local, upstream, apply = true), context(home))
    assertEquals(0, seed.exitCode, seed.stdout)

    seedSkill(upstream, "bill-sample", "UPSTREAM divergence.")
    seedSkill(local, "bill-sample", "LOCAL divergence.")

    val result = CliRuntime.run(reconcileArgs(home, local, upstream, apply = true), context(home))

    assertEquals(0, result.exitCode, result.stdout)
    val outcomeLine = result.stdout.lines().single { it.startsWith("reconcile_outcome:") }
    assertTrue(outcomeLine.startsWith("reconcile_outcome: kind=locally-authored "), outcomeLine)
    assertContains(
      Files.readString(local.resolve("skills/bill-sample/content.md")),
      "LOCAL divergence.",
    )
  }

  @Test
  fun `real reconcile --apply installs the upstream skill, refreshes the baseline, and is idempotent`() {
    val home = Files.createTempDirectory("skillbill-reconcile-apply-home")
    val upstream = Files.createTempDirectory("skillbill-reconcile-apply-upstream")
    val local = home.resolve(".skill-bill")
    seedSkill(upstream, "bill-sample", "Upstream body.")
    Files.createDirectories(upstream.resolve("platform-packs"))
    Files.createDirectories(local.resolve("platform-packs"))

    val result = CliRuntime.run(reconcileArgs(home, local, upstream, apply = true), context(home))

    assertEquals(0, result.exitCode, result.stdout)
    val summaryLine = result.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(summaryLine, "applied=true")
    assertContains(summaryLine, "installed_count=1")
    val liveContent = local.resolve("skills/bill-sample/content.md")
    assertTrue(Files.isRegularFile(liveContent), "apply must install the upstream skill into the live tree")
    assertContains(Files.readString(liveContent), "Upstream body.")
    assertTrue(Files.isRegularFile(home.resolve(".skill-bill/baseline-manifest.json")))

    val second = CliRuntime.run(reconcileArgs(home, local, upstream, apply = true), context(home))

    assertEquals(0, second.exitCode, second.stdout)
    val secondSummary = second.stdout.lines().single { it.startsWith("reconcile_summary:") }
    assertContains(secondSummary, "installed_count=0")
    assertFalse(secondSummary.contains("baseline_refreshed=true"))
    assertTrue(
      second.stdout.lines().single { it.startsWith("reconcile_outcome:") }
        .startsWith("reconcile_outcome: kind=unchanged "),
      second.stdout,
    )
  }
}
