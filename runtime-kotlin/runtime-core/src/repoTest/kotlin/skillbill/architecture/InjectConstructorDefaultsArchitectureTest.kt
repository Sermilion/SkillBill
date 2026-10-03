package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class InjectConstructorDefaultsArchitectureTest {
  @Test
  fun `every declared module matches its inject constructor defaults baseline`() {
    assertEquals(
      emptySet(),
      baseline("inject-constructor-defaults-baseline.txt"),
      "The runtime-application inject-defaults rule is absolute; its baseline must stay empty.",
    )
    val drift = ArchitectureScanSupport.injectConstructorDefaultDrift()
    assertEquals(emptyList(), drift, drift.joinToString("\n"))
  }

  @Test
  fun `inject default rule reports a violation placed in runtime-engine`() {
    val root = Files.createTempDirectory("skillbill-inject-defaults-rejection")
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
      ArchitectureScanSupport.injectConstructorDefaultDrift(scanRoot = root, readBaseline = { "" })
    assertEquals(
      listOf(
        "runtime-engine: $SYNTHETIC_ENGINE_VIOLATION_PATH::SyntheticEngineService::clock " +
          "is not listed in runtime-engine-inject-constructor-defaults-baseline.txt.",
      ),
      drift,
    )
  }

  @Test
  fun `runtime-application inject classes expose no constructor property`() {
    val violations =
      ArchitectureScanSupport.injectConstructorPropertyViolations(
        baseline = emptySet(),
        scanRoot = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli inject classes expose no constructor property`() {
    val violations =
      ArchitectureScanSupport.injectConstructorPropertyViolations(
        baseline = emptySet(),
        scanRoot = PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-engine inject classes expose no constructor property`() {
    val violations =
      ArchitectureScanSupport.injectConstructorPropertyViolations(
        baseline = emptySet(),
        scanRoot = PrincipleEnforcementInventory.RUNTIME_ENGINE_MAIN,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `runtime-cli internal data classes carry no collaborator property`() {
    val violations =
      ArchitectureScanSupport.dataClassCollaboratorPropertyViolations(
        scanRoot = PrincipleEnforcementInventory.RUNTIME_CLI_MAIN,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `data class collaborator scanner reports only collaborator properties of internal data classes`() {
    val source =
      """


      internal data class SyntheticArgs(
        val state: CliRunState,
        val clock: java.time.Clock,
        val gateway: ScaffoldGateway,
        val overlay: ExternalAddonOverlayService? = null,
        val format: CliFormat,
        val transform: (CliRunState) -> String,
      )

      data class PublicArgs(val launcher: AgentLauncher)

      @Inject
      class SyntheticInjected(private val state: CliRunState)
      """.trimIndent()
    val sites =
      ArchitectureScanSupport.dataClassCollaboratorPropertySitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticArgs.kt",
        source = source,
      ).map { site -> "${site.symbol}::${site.parameter}" }
    assertEquals(
      listOf(
        "SyntheticArgs::state",
        "SyntheticArgs::clock",
        "SyntheticArgs::gateway",
        "SyntheticArgs::overlay",
      ),
      sites,
    )
  }

  @Test
  fun `inject constructor property scanner reports only non-private properties`() {
    val source =
      """


      @Inject
      class SyntheticExposedService(
        private val hidden: Clock,
        plain: RuntimeDiagnostics,
        val exposed: DatabaseSessionFactory,
        internal val shared: (String) -> ParallelReviewParseResult,
      )
      """.trimIndent()
    val sites =
      ArchitectureScanSupport.injectConstructorPropertySitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticExposedService.kt",
        source = source,
      ).map { site -> "${site.symbol}::${site.parameter}" }
    assertEquals(listOf("SyntheticExposedService::exposed", "SyntheticExposedService::shared"), sites)
  }

  @Test
  fun `inject constructor default scanner fires on synthetic default argument`() {
    val source =
      """


      @Inject
      data class SyntheticInjectBag(
        val reviewDriver: FeatureTaskRuntimeReviewDriver = FeatureTaskRuntimeReviewDriver { _ ->
          error("auto-approve")
        },
      )
      """.trimIndent()
    val violations =
      ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticInjectBag.kt",
        source = source,
      ).map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
        .filter { encoded -> encoded !in emptySet<String>() }
        .map { encoded -> "$encoded has a default argument on an @Inject constructor or dependency bag." }
    assertEquals(
      listOf(
        "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticInjectBag.kt::SyntheticInjectBag::reviewDriver " +
          "has a default argument on an @Inject constructor or dependency bag.",
      ),
      violations,
    )
  }

  @Test
  fun `inject constructor default scanner sees past a visibility modifier`() {
    val source =
      """


      @Inject
      public class ModifierShieldedInjectClass(
        private val diagnostics: RuntimeDiagnostics = NoopRuntimeDiagnostics,
      )
      """.trimIndent()
    val sites =
      ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/ModifierShieldedInjectClass.kt",
        source = source,
      ).map { site -> "${site.symbol}::${site.parameter}" }
    assertEquals(listOf("ModifierShieldedInjectClass::diagnostics"), sites)
  }

  @Test
  fun `inject scanner reports non-private property defaults of a class without a primary constructor`() {
    val source =
      """


      @Inject
      class SyntheticRunState {
        var dbOverride: String? = null
        var stdinText: String? = null
        var environment: Map<String, String> = emptyMap()
        var externalCommandRunner: CommandRunner = ProcessCommandRunner
        var userHome: Path = Path.of("/tmp")
        var liveStdout: (String) -> Unit = {}
        var liveStderr: (String) -> Unit = {}
        var result: ExecutionResult? = null
        private var stdinLineIterator: Iterator<String>? = null

        fun complete(payload: Map<String, Any?>, exitCode: Int = 0) {
          result = ExecutionResult(exitCode = exitCode)
        }
      }
      """.trimIndent()
    val parameters =
      ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/SyntheticRunState.kt",
        source = source,
      ).map { site -> site.parameter }
    assertEquals(
      listOf(
        "dbOverride",
        "stdinText",
        "environment",
        "externalCommandRunner",
        "userHome",
        "liveStdout",
        "liveStderr",
        "result",
      ),
      parameters,
    )
  }

  @Test
  fun `inject scanner keeps reading defaults past a literal holding an unbalanced delimiter`() {
    val source =
      """


      @Inject
      class LiteralDefaultRunState {
        var openBrace: String = "{"
        var closingParen: Char = ')'
        var stdinText: String? = null

        fun complete(exitCode: Int = 0) {
          val localOnly = exitCode
        }
      }
      """.trimIndent()
    val parameters =
      ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
        relativePath = "runtime-kotlin/runtime-example/src/main/kotlin/LiteralDefaultRunState.kt",
        source = source,
      ).map { site -> site.parameter }
    assertEquals(listOf("openBrace", "closingParen", "stdinText"), parameters)
  }

  private fun baseline(name: String): Set<String> =
    ArchitectureScanSupport.parseStringSetBaseline(ArchitectureBaselineSupport.readBaseline(name))
}
