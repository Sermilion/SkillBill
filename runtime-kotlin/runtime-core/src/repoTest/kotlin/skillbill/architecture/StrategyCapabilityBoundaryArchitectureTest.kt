package skillbill.architecture

import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StrategyCapabilityBoundaryArchitectureTest {
  @Test
  fun `strategy capabilities stay closed through helpers and extensions`() {
    val root = ArchitectureScanSupport.runtimeRoot
    val sourceRoot = root.resolve("runtime-kotlin/runtime-engine/src/main/kotlin")
    val sources =
      ArchitectureScanSupport.kotlinFilesUnder(sourceRoot).map { path ->
        CapabilitySource(root.relativize(path).toString().replace('\\', '/'), path.readText())
      }
    val consumers =
      sources
        .map { it.path }
        .filter { path ->
          "/featuretask/slot/" in path &&
            "/slot/attempt/" !in path &&
            "/slot/state/" !in path &&
            "/slot/runner/" !in path &&
            !path.endsWith("/slot/PhaseRunner.kt") &&
            !path.endsWith("/slot/PhaseStrategyRegistry.kt") &&
            !path.endsWith("/slot/PhaseStrategyLookup.kt") &&
            !path.endsWith("/slot/PhaseStrategySelection.kt") &&
            !path.endsWith("/PhaseStepHooks.kt") &&
            !path.endsWith("/PhaseLoopRules.kt") &&
            !path.endsWith("/SkeletonStrategyBindings.kt")
        }.toSet()
    assertTrue(consumers.isNotEmpty(), "Capability scan found no strategy consumers.")
    val violations = StrategyCapabilityTransitiveGraph.violations(sources, consumers)
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `an audit cannot mutate progress through a helper in the attempt directory`() {
    val consumer =
      CapabilitySource(
        "audit/Probe.kt",
        """
        package skillbill.engine.featuretask.slot.audit
        import skillbill.engine.featuretask.slot.attempt.PrivatelyNamedBridge
        internal class Probe(private val bridge: PrivatelyNamedBridge) {
          fun begin() = bridge.begin()
        }
        """.trimIndent(),
      )
    val bridge =
      CapabilitySource(
        "attempt/Bridge.kt",
        """
        package skillbill.engine.featuretask.slot.attempt
        import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
        internal class PrivatelyNamedBridge(private val progress: FeatureTaskRuntimeRunState) {
          fun begin() = progress.recordPhaseLaunched("audit")
        }
        """.trimIndent(),
      )
    val findings = StrategyCapabilityTransitiveGraph.violations(listOf(consumer, bridge), setOf(consumer.path))
    assertTrue(findings.any { "PrivatelyNamedBridge" in it && "FeatureTaskRuntimeRunState" in it }, findings.toString())
  }

  @Test
  fun `unreferenced runtime helpers cannot write coupled progress outside its owner`() {
    val helper =
      CapabilitySource(
        "runloop/LeakedWriter.kt",
        """
        package skillbill.engine.featuretask.runloop
        import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
        internal fun leak(progress: FeatureTaskRuntimeRunState) {
          val alias = progress
          alias.restartAttemptBudget("audit")
        }
        """.trimIndent(),
      )
    val findings = StrategyCapabilityTransitiveGraph.violations(listOf(helper), emptySet())
    assertTrue(findings.any { "restartAttemptBudget" in it && "outside its transition owner" in it })
  }

  @Test
  fun `aliases and extension casts cannot expose review authority to a gate`() {
    val consumer =
      CapabilitySource(
        "qualitygate/Gate.kt",
        """
        package skillbill.engine.featuretask.slot.qualitygate
        import skillbill.engine.featuretask.slot.attempt.openReceipt
        internal class Gate {
          fun reserve(input: Any) = input.openReceipt().reserveReviewPass()
        }
        """.trimIndent(),
      )
    val helper =
      CapabilitySource(
        "attempt/Receipt.kt",
        """
        package skillbill.engine.featuretask.slot.attempt
        import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
        internal typealias Receipt = PhaseReviewPassState
        internal fun Any.openReceipt(): Receipt = this as Receipt
        """.trimIndent(),
      )
    val findings = StrategyCapabilityTransitiveGraph.violations(listOf(consumer, helper), setOf(consumer.path))
    assertTrue(findings.any { "openReceipt" in it && "PhaseReviewPassState" in it }, findings.toString())
  }

  @Test
  fun `qualified and wildcard helpers cannot hide review authority`() {
    val helper =
      CapabilitySource(
        "attempt/Receipt.kt",
        """
        package skillbill.engine.featuretask.slot.attempt
        import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
        internal fun obtain(input: Any) = input as PhaseReviewPassState
        internal fun Any.openReceipt() = obtain(this)
        internal object Bridge {
          fun expose(input: Any) = hidden(input)
          private fun hidden(input: Any) = obtain(input)
        }
        """.trimIndent(),
      )
    val paths =
      listOf(
        "" to "skillbill.engine.featuretask.slot.attempt.obtain(input)",
        "" to "skillbill.engine.featuretask.slot.attempt.Bridge.expose(input)",
        "import skillbill.engine.featuretask.slot.attempt.Bridge as Alias" to "Alias.expose(input)",
        "import skillbill.engine.featuretask.slot.attempt.*" to "obtain(input)",
        "import skillbill.engine.featuretask.slot.attempt.*" to "input.openReceipt()",
      )
    paths.forEach { (imports, invocation) ->
      val consumer =
        CapabilitySource(
          "audit/Probe.kt",
          """
          package skillbill.engine.featuretask.slot.audit
          $imports
          internal fun probe(input: Any) = $invocation.reserveReviewPass()
          """.trimIndent(),
        )
      val findings = StrategyCapabilityTransitiveGraph.violations(listOf(consumer, helper), setOf(consumer.path))
      assertTrue(findings.any { "PhaseReviewPassState" in it }, "$invocation: $findings")
    }
  }

  @Test
  fun `unresolved governed helpers fail the capability guard`() {
    val consumer =
      CapabilitySource(
        "audit/Unknown.kt",
        """
        package skillbill.engine.featuretask.slot.audit
        import skillbill.engine.featuretask.slot.attempt.UnindexedBridge
        internal class Unknown(private val bridge: UnindexedBridge)
        """.trimIndent(),
      )
    val findings = StrategyCapabilityTransitiveGraph.violations(listOf(consumer), setOf(consumer.path))
    assertTrue(findings.any { "unresolved governed authority edge" in it && "UnindexedBridge" in it })
    val qualified =
      CapabilitySource(
        "audit/QualifiedUnknown.kt",
        """
        package skillbill.engine.featuretask.slot.audit
        internal fun probe(input: Any) = skillbill.engine.featuretask.slot.attempt.unindexed(input)
        """.trimIndent(),
      )
    val qualifiedFindings = StrategyCapabilityTransitiveGraph.violations(listOf(qualified), setOf(qualified.path))
    assertTrue(qualifiedFindings.any { "unresolved governed authority edge" in it && "unindexed" in it })
  }

  @Test
  fun `detached observation helpers and declared review consumers remain allowed`() {
    val observations =
      CapabilitySource(
        "runloop/Observations.kt",
        """
        package skillbill.engine.featuretask.runloop.state
        internal interface FeatureTaskRuntimeProgressSnapshotAccess {
          fun nextIteration(step: String): Int
        }
        """.trimIndent(),
      )
    val bridge =
      CapabilitySource(
        "attempt/ObservationBridge.kt",
        """
        package skillbill.engine.featuretask.slot.attempt
        import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess
        internal class ObservationBridge(private val observations: FeatureTaskRuntimeProgressSnapshotAccess) {
          fun next() = observations.phase("audit").nextIteration
        }
        """.trimIndent(),
      )
    val consumer =
      CapabilitySource(
        "audit/Allowed.kt",
        """
        package skillbill.engine.featuretask.slot.audit
        import skillbill.engine.featuretask.slot.attempt.ObservationBridge
        internal class Allowed(private val bridge: ObservationBridge) {
          fun next() = bridge.next()
          fun detached(observations: skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeProgressSnapshotAccess) =
            skillbill.engine.featuretask.slot.attempt.ObservationBridge(observations).next()
        }
        """.trimIndent(),
      )
    val review =
      CapabilitySource(
        "codereview/Allowed.kt",
        """
        package skillbill.engine.featuretask.slot.codereview
        import skillbill.engine.featuretask.slot.state.PhaseReviewPassState
        internal class Allowed(private val review: PhaseReviewPassState) { fun reserve() = review.reserveReviewPass() }
        """.trimIndent(),
      )
    val reviewContract =
      CapabilitySource(
        "state/Review.kt",
        """
        package skillbill.engine.featuretask.slot.state
        internal interface PhaseReviewPassState { fun reserveReviewPass(): Int }
        """.trimIndent(),
      )
    val findings =
      StrategyCapabilityTransitiveGraph.violations(
        listOf(observations, bridge, consumer, review, reviewContract),
        setOf(consumer.path, review.path),
      )
    assertEquals(emptyList(), findings, findings.joinToString("\n"))
  }
}
