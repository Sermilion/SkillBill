package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeApplicationAmbientClockArchitectureTest {
  @Test
  fun `every declared module matches its ambient clock baseline`() {
    val drift = ArchitectureScanSupport.ambientClockDrift()
    assertEquals(emptyList(), drift, drift.joinToString("\n"))
  }

  @Test
  fun `ambient clock rule reports a violation placed in runtime-engine`() {
    val root = Files.createTempDirectory("skillbill-ambient-clock-rejection")
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
    val drift = ArchitectureScanSupport.ambientClockDrift(scanRoot = root, readBaseline = { "" })
    assertEquals(
      listOf(
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH:Clock.systemUTC():1 " +
          "is not listed in runtime-engine-ambient-clock-baseline.txt.",
      ),
      drift,
    )
  }

  @Test
  fun `ambient process forms are reported outside the process adapters`() {
    val root = Files.createTempDirectory("skillbill-ambient-process-rejection")
    seedModuleScanTreeWithEngineViolation(
      root,
      """
      package skillbill.engine

      fun elapsed() = System.nanoTime()
      fun process() = ProcessHandle.current()
      """.trimIndent(),
    )
    val launcherCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == "runtime-infra:launcher" }
    val launcherPath = "${launcherCase.mainScanRoot}/SyntheticProcess.kt"
    val launcherFile = root.resolve(launcherPath)
    Files.createDirectories(launcherFile.parent)
    Files.writeString(
      launcherFile,
      """
      package skillbill.infrastructure.launcher

      fun elapsed() = System.nanoTime()
      fun process() = ProcessHandle.current()
      fun now() = Instant.now()
      """.trimIndent(),
    )

    val drift = ArchitectureScanSupport.ambientClockDrift(scanRoot = root, readBaseline = { "" })
    val engineCase =
      PrincipleEnforcementInventory.moduleArchitectureScanCases
        .single { scanCase -> scanCase.moduleName == "runtime-engine" }
    val expected =
      listOf(
        "ProcessHandle.",
        "System.nanoTime()",
      ).map { call ->
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH:$call:1 " +
          "is not listed in ${engineCase.ambientClockBaseline}."
      } +
        listOf(
          "runtime-infra:launcher: $launcherPath:Instant.now():1 " +
            "is not listed in ${launcherCase.ambientClockBaseline}.",
        )
    assertEquals(expected, drift)
  }

  @Test
  fun `ambient process rule reports aliased type and function imports in runtime-engine`() {
    val root = Files.createTempDirectory("skillbill-ambient-process-alias-rejection")
    seedModuleScanTreeWithEngineViolation(
      root,
      """
      package skillbill.engine

      import java.lang.System as HostSystem
      import java.lang.ProcessHandle as HostProcess
      import java.lang.System.nanoTime as readTicks

      fun elapsed() = HostSystem.nanoTime()
      fun process() = HostProcess.current()
      fun ticks() = readTicks()
      """.trimIndent(),
    )
    val engineCase = scanCasesFor(setOf("runtime-engine")).single()
    val drift = ArchitectureScanSupport.ambientClockDrift(scanRoot = root, readBaseline = { "" })
    assertEquals(
      listOf(
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH:ProcessHandle.:1 " +
          "is not listed in ${engineCase.ambientClockBaseline}.",
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH:System.nanoTime():2 " +
          "is not listed in ${engineCase.ambientClockBaseline}.",
      ),
      drift,
    )
  }

  @Test
  fun `ambient clock rows key on call and count, not line number`() {
    val onLineFive =
      """
      package skillbill.example

      import java.time.Instant

      fun nowMarker() = Instant.now()
      """.trimIndent()
    val movedToLineNine =
      """
      package skillbill.example

      import java.time.Instant

      fun unrelated() = 1

      fun alsoUnrelated() = 2

      fun nowMarker() = Instant.now()
      """.trimIndent()
    val twiceInOneFile = "$onLineFive\n\nfun secondMarker() = Instant.now()\n"
    assertEquals(listOf("$EXAMPLE_PATH:Instant.now():1"), encode(onLineFive))
    assertEquals(encode(onLineFive), encode(movedToLineNine))
    assertEquals(listOf("$EXAMPLE_PATH:Instant.now():2"), encode(twiceInOneFile))
  }

  @Test
  fun `ambient clock scanner fires on unlisted Instant now site`() {
    assertScannerReports("fun nowMarker() = Instant.now()", "Instant.now()")
  }

  @Test
  fun `ambient clock scanner fires on unlisted OffsetDateTime now site`() {
    assertScannerReports("fun nowMarker() = OffsetDateTime.now(ZoneOffset.UTC)", "OffsetDateTime.now()")
  }

  @Test
  fun `ambient clock scanner fires on unlisted ZonedDateTime now site`() {
    assertScannerReports("fun nowMarker() = ZonedDateTime.now()", "ZonedDateTime.now()")
  }

  @Test
  fun `ambient clock scanner fires on unlisted JvmSystemClock instant site`() {
    assertScannerReports("fun nowMarker() = JvmSystemClock.instant()", "JvmSystemClock.instant()")
  }

  @Test
  fun `ambient clock scanner fires on unlisted System currentTimeMillis site`() {
    assertScannerReports("fun nowMarker() = System.currentTimeMillis()", "System.currentTimeMillis()")
  }

  @Test
  fun `ambient clock scanner fires on unlisted LocalDate now site`() {
    assertScannerReports("fun todayMarker() = LocalDate.now()", "LocalDate.now()")
  }

  private fun assertScannerReports(
    declaration: String,
    expectedCall: String,
  ) {
    val violations =
      ArchitectureScanSupport.ambientClockViolationsInSource(
        moduleName = "runtime-engine",
        relativePath = EXAMPLE_PATH,
        source = "package skillbill.example\n\n$declaration\n",
        baseline = emptySet(),
      )
    assertEquals(
      listOf("$EXAMPLE_PATH:$expectedCall:1 is not listed in the ambient-clock baseline."),
      violations,
    )
  }

  private fun encode(source: String): List<String> =
    ArchitectureScanSupport.encodeAmbientClockSitesInSource("runtime-engine", EXAMPLE_PATH, source)

  private companion object {
    const val EXAMPLE_PATH = "runtime-kotlin/runtime-example/src/main/kotlin/Example.kt"
  }
}
