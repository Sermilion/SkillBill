package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertTrue

class FeatureTaskAuditRemainingCriteriaPackIndependenceArchitectureTest {
  private val runtimeRoot: Path =
    Path.of("").toAbsolutePath().normalize().let { workingDir ->
      if (workingDir.fileName.toString().startsWith("runtime-")) {
        workingDir.parent
      } else {
        workingDir
      }
    }
  private val repoRoot: Path = runtimeRoot.parent

  @Test
  fun `platform packs do not author remaining-criteria settlement`() {
    val packsRoot = repoRoot.resolve("platform-packs")
    assertTrue(Files.isDirectory(packsRoot), "expected platform-packs/ at the repository root")
    val packSlugs =
      Files.list(packsRoot).use { stream ->
        stream
          .filter { path -> Files.isDirectory(path) && Files.isRegularFile(path.resolve("platform.yaml")) }
          .map { path -> path.fileName.toString() }
          .sorted()
          .toList()
      }
    assertTrue(packSlugs.isNotEmpty(), "expected at least one shipped platform pack")
    val hits =
      Files.walk(packsRoot).use { paths ->
        paths
          .filter { path -> path.isRegularFile() && packSourceSuffixes.any { path.fileName.toString().endsWith(it) } }
          .flatMap { path ->
            val text = Files.readString(path)
            remainingCriteriaSettlementPhrases.stream()
              .filter { phrase -> text.contains(phrase) }
              .map { phrase -> "${repoRoot.relativize(path)} authors remaining-criteria settlement via '$phrase'" }
          }
          .toList()
      }
    assertTrue(
      hits.isEmpty(),
      "remaining-criteria settlement is runtime-owned; packs must not fork it:\n" +
        hits.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `remaining-criteria settlement text has one owner under the acceptance-audit strategy`() {
    val featureTaskRoot = runtimeRoot.resolve("runtime-engine/src/main/kotlin/skillbill/engine/featuretask")
    val owners =
      Files.walk(featureTaskRoot).use { paths ->
        paths
          .filter { path -> path.isRegularFile() && path.fileName.toString().endsWith(".kt") }
          .filter { path -> Files.readString(path).contains(REMAINING_CRITERIA_SETTLEMENT_TEXT) }
          .map { path -> featureTaskRoot.relativize(path).toString().replace('\\', '/') }
          .sorted()
          .toList()
      }
    assertTrue(
      owners.size == 1 && owners.single().startsWith("slot/audit/"),
      "remaining-criteria settlement text has one owner under slot/audit; phase/prompt and runloop must not " +
        "restate it:\n" + owners.joinToString(separator = "\n"),
    )
  }

  private companion object {
    const val REMAINING_CRITERIA_SETTLEMENT_TEXT = "remaining acceptance criteria"
    val packSourceSuffixes = listOf(".md", ".yaml")
    val remainingCriteriaSettlementPhrases =
      listOf(
        "remaining-criteria",
        "remaining acceptance criteria",
        "up to three repair cycles",
        "The runtime does not validate remaining-list shape",
      )
  }
}
