package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArchitectureBaselineSupportTest {
  @Test
  fun `writing no rows removes an existing baseline`() {
    val directory = Files.createTempDirectory("skillbill-empty-baseline")
    val target = directory.resolve("x-baseline.txt")
    Files.writeString(target, "stale row\n")

    ArchitectureBaselineSupport.writeBaselineRows(target, emptyList())

    assertFalse(Files.exists(target))
  }

  @Test
  fun `missing module baseline rejects a new ambient clock violation`() {
    val root = Files.createTempDirectory("skillbill-missing-baseline")
    val missingBaselineDirectory = Files.createTempDirectory("skillbill-no-baselines")
    seedModuleScanTreeWithEngineViolation(
      root,
      """
      package skillbill.engine

      import java.time.Clock
      import me.tatarka.inject.annotations.Inject

      @Inject
      class SyntheticEngineService(private val clock: Clock = Clock.systemUTC())
      """.trimIndent(),
    )

    val drift =
      ArchitectureScanSupport.ambientClockDrift(
        scanRoot = root,
        readBaseline = { name -> ArchitectureBaselineSupport.readBaselineIn(missingBaselineDirectory, name) },
      )

    assertEquals(
      listOf(
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH:Clock.systemUTC():1 " +
          "is not listed in runtime-engine-ambient-clock-baseline.txt.",
      ),
      drift,
    )
  }
}
