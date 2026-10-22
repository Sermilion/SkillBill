package skillbill.infrastructure.contracts.locator

import skillbill.contracts.workflow.identity.blockincident.BLOCK_INCIDENT_CONTRACT_VERSION
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlockIncidentContractVersionTest {
  @Test
  fun `schema pins the Kotlin version and immutable occurrence fields`() {
    val schema = Files.readString(blockIncidentRepoRoot().resolve(BlockIncidentSchemaPaths.REPOSITORY_PATH))
    val version = Regex("""const:\s*"([^"]+)"""").find(schema)?.groupValues?.get(1)

    assertEquals(BLOCK_INCIDENT_CONTRACT_VERSION, version)
    assertTrue("additionalProperties: false" in schema)
    assertTrue("occurrence_token:" in schema)
    assertTrue("generation:" in schema)
    assertTrue("aliases:" in schema)
    assertTrue("diagnosis:" in schema)
  }
}

private fun blockIncidentRepoRoot(): Path {
  var current = Path.of("").toAbsolutePath().normalize()
  while (current.parent != null) {
    if (
      Files.isRegularFile(current.resolve("runtime-kotlin/settings.gradle.kts")) &&
      Files.isDirectory(current.resolve("orchestration/contracts"))
    ) {
      return current
    }
    current = current.parent
  }
  error("Could not locate skill-bill repo root from ${Path.of("").toAbsolutePath().normalize()}")
}
