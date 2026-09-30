package skillbill.nativeagent

import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.testing.repoRootFromTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.streams.toList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class NativeAgentCompositionValidatesExistingBundlesTest {
  @Test
  fun `every on-disk native-agent fixture validates clean against the canonical schema`() {
    val repoRoot: Path = repoRootFromTest()
    val scopes =
      listOf(
        repoRoot.resolve("skills"),
        repoRoot.resolve("platform-packs"),
      )
    val fixtures: List<Path> =
      scopes
        .filter { Files.isDirectory(it) }
        .flatMap { scope ->
          Files.walk(scope).use { stream ->
            stream
              .filter { Files.isRegularFile(it) }
              .filter { path -> path.parent?.fileName?.toString() == NATIVE_AGENT_SOURCE_DIR }
              .filter { path -> path.name == NATIVE_AGENT_BUNDLE_FILE || path.extension == "md" }
              .toList()
          }
        }

    assertTrue(
      fixtures.isNotEmpty(),
      "Expected at least one native-agent fixture under skills/ or platform-packs/; " +
        "found none. This test is meaningless if no fixtures exist.",
    )

    val failures = mutableListOf<String>()
    fixtures.forEach { fixture ->
      try {
        parseNativeAgentSourceFile(fixture)
      } catch (error: Throwable) {
        failures.add("$fixture: ${error.message}")
      }
    }

    if (failures.isNotEmpty()) {
      fail(
        "The following native-agent fixtures failed canonical schema validation:\n" +
          failures.joinToString("\n"),
      )
    }
  }
}
