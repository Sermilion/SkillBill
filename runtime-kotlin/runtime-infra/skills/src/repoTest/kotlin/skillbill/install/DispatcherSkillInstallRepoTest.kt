package skillbill.install

import skillbill.infrastructure.skills.install.ApplyFixture
import skillbill.infrastructure.skills.install.InstallApplyTestSupport
import skillbill.infrastructure.skills.install.applyInstallForTest
import skillbill.infrastructure.skills.install.planInstallForTest
import skillbill.install.model.InstallApplyStatus
import skillbill.install.model.SupportedAgent
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DispatcherSkillInstallRepoTest : InstallApplyTestSupport() {
  @Test
  fun `the install catalog lists only skill-bill with its feature class pointers and docs name no retired entry`() {
    val repoRoot = repoRootFromTest()
    val home = Files.createTempDirectory("skillbill-dispatcher-install").also(tempDirs::add)
    val agents = setOf(SupportedAgent.CLAUDE, SupportedAgent.CODEX)

    val result = applyInstallForTest(planInstallForTest(ApplyFixture(repoRoot, home).request(agents = agents)))

    assertEquals(InstallApplyStatus.SUCCESS, result.status, "install failures: ${result.failures}")
    agents.forEach { agent ->
      val targetDir = home.resolve("agent-skill-targets/${agent.id}")
      val listed =
        Files.list(targetDir).use { entries ->
          entries.filter {
              entry ->
            Files.isRegularFile(entry.resolve("SKILL.md"))
          }.map { entry -> entry.name }.toList()
        }
      assertEquals(setOf("skill-bill"), listed.toSet(), "listed catalog for ${agent.id}")
      val installedSkillBill = readSymlinkTarget(targetDir.resolve("skill-bill"))
      FEATURE_CLASS_POINTERS.forEach { pointer ->
        assertTrue(Files.isRegularFile(installedSkillBill.resolve(pointer)), "${agent.id} skill-bill/$pointer")
      }
    }
    listOf("skills/bill-feature", "skills/bill-monitor").forEach { retired ->
      assertFalse(Files.exists(repoRoot.resolve(retired), LinkOption.NOFOLLOW_LINKS), "$retired must stay deleted")
    }
    val retiredEntryMentions =
      userFacingDocs(repoRoot).flatMap { doc ->
        Files.readAllLines(doc)
          .withIndex()
          .filter { (_, line) -> RETIRED_ENTRY_INVOCATION.containsMatchIn(line) }
          .map { (index, line) -> "${repoRoot.relativize(doc)}:${index + 1}: $line" }
      }
    assertEquals(emptyList<String>(), retiredEntryMentions, "docs must not name a retired entry skill")
  }

  @Test
  fun `skill-bill routes both operations to the operation subcommand and states the relay rule`() {
    val content = Files.readString(repoRootFromTest().resolve("skills/skill-bill/content.md"))
    val routingRows = content.lines().filter { line -> line.startsWith("| `/skill-bill") }

    listOf(
      "operation:update-check [--include-prereleases] [--format json]`" to
        "`skill-bill operation update-check [--include-prereleases] [--format json]`",
      "standalone quality check: run checks, lint, format, or quality validation" to
        "`skill-bill phase validation [<intake>] --agent <currently-executing-agent>`",
      "operation:release" to "`skill-bill operation release bump:<value>",
      "operation:pr-review-fix" to "`skill-bill operation pr-review-fix [<pr>] [<tokens>]",
      "operation:verify" to "`skill-bill operation verify [spec:<value>] [target:<value>] [mode:inline\\|delegated]",
    ).forEach { (token, command) ->
      assertTrue(
        routingRows.any { row -> token in row && command in row },
        "no routing row sends $token to $command",
      )
    }
    val prose = content.replace(Regex("\\s+"), " ")
    assertTrue(
      "For `operation:update-check`, forward `--include-prereleases` and `--format json` verbatim" in prose,
      "update-check must pass its flags through",
    )
    assertTrue("status: awaiting_confirmation confirm:<token>" in prose)
    assertTrue(
      "ask the operator once whether to proceed. On yes, run the same operation with `confirm:<token>`." in prose,
    )
    assertTrue("Never pass `confirm:` without an operator answer." in content)
    assertTrue("`confirm:<token>` and that `select:`" in prose, "pr-review-fix must re-run with the operator's select:")
    assertTrue("Never pass `confirm:` or `select:` without an operator answer." in prose)
    assertTrue("For `operation:verify`, forward `mode:` verbatim" in prose, "verify must forward mode: verbatim")
    assertTrue("ask the operator once to confirm or adjust them." in prose)
    assertTrue("`rehydrate-needed:`, run Rehydrate for that spec path, then run the same operation once more." in prose)
    assertTrue(
      "`mode:` or `target:` outside `phase:review` and `operation:verify`" in prose,
      "the token-rejection rule must admit verify's mode: and target:",
    )
    assertTrue("the caller passes `phase:` together with `operation:`: report a usage error." in content)
    assertTrue("SKILL-382" !in content, "the pre-SKILL-382 operation refusal must be gone")
  }

  private fun userFacingDocs(repoRoot: Path): List<Path> {
    val docsTree =
      Files.walk(repoRoot.resolve("docs")).use { paths ->
        paths.filter { path -> Files.isRegularFile(path) && path.name.endsWith(".md") }.sorted().toList()
      }
    return listOf(
      "AGENTS.md",
      "README.md",
      "CONTRIBUTING.md",
      "RELEASING.md",
      "runtime-kotlin/ARCHITECTURE.md",
      "install.sh",
    ).map(repoRoot::resolve) + docsTree
  }

  private companion object {
    val FEATURE_CLASS_POINTERS =
      listOf(
        "peak-hours-warner.md",
        "shell-ceremony.md",
        "telemetry-contract.md",
        "agent-addon-execution-budget.md",
      )
    val RETIRED_ENTRY_INVOCATION = Regex("""/bill-monitor|/bill-feature(?!-)""")
  }
}
