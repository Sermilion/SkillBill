package skillbill.architecture

import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeDiagnosticsBestEffortGuardArchitectureTest {
  @Test
  fun `engine production sources delegate diagnostics warning guards to the single owner`() {
    val engineRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin",
      )
    val guardPattern =
      Regex(
        """(?:runCatching|onFailure|catch\s*\([^)]*\))[\s\S]{0,300}diagnostics\.warning""",
      )
    val owner =
      engineRoot.resolve(
        "skillbill/engine/diagnostics/RuntimeDiagnosticsBestEffortWarning.kt",
      )
    val violations =
      kotlinFilesUnderWithArchitectureAsserts(engineRoot)
        .filter { path -> path != owner && guardPattern.containsMatchIn(path.readText()) }
        .map { path -> engineRoot.relativize(path).toString() }
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }
}

class GoalRunnerAgentOutputScannerArchitectureTest {
  @Test
  fun `adapter-owned agent output scanner remains the single production scanner`() {
    val roots =
      listOf(
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-engine/src/main/kotlin"),
        ArchitectureScanSupport.runtimeRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin"),
      )
    val matches =
      roots.flatMap { root ->
        kotlinFilesUnderWithArchitectureAsserts(root).filter { path ->
          path.readText().contains("fun topLevelJsonObjectCandidates")
        }.map { path -> root.relativize(path).toString() }
      }
    assertEquals(
      listOf("skillbill/engine/agentoutput/AgentOutputJsonScan.kt"),
      matches.sorted(),
    )
  }
}

class RuntimeApplicationSharedEngineEdgeArchitectureTest {
  @Test
  fun `application main references only pinned engine inbound api types`() {
    val violations =
      engineInboundApiViolations(
        consumerSourceRoots = listOf(moduleMainKotlinRootRelative("runtime-application")),
        allowedTypes = RuntimeEngineInboundApiTest.PINNED_ENGINE_INBOUND_API_TYPES,
      )
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }
}

class RuntimeEnginePublicTopLevelDeclarationArchitectureTest {
  @Test
  fun `feature-task run-loop step declarations reference each other acyclically`() {
    val cycles = ArchitectureScanSupport.cyclicComponents(runLoopStepEdges(runLoopSources()))
    assertTrue(
      cycles.isEmpty(),
      cycles.joinToString("\n") { cycle -> "run-loop step cycle: ${cycle.joinToString(" -> ")}" },
    )
  }

  @Test
  fun `run-loop phase blocking leaf references no other run-loop step declaration`() {
    val edges = runLoopStepEdges(runLoopSources())
    assertEquals(
      emptySet(),
      edges.getValue(PHASE_BLOCKING_STEP),
      "$PHASE_BLOCKING_STEP must stay a leaf and reference only run-loop carriers.",
    )
  }

  @Test
  fun `run-loop step census reports a mutually referencing pair as one cycle naming both`() {
    val sources =
      mapOf(
        "core/FeatureTaskRuntimeRunLoopAlpha.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          object FeatureTaskRuntimeRunLoopAlpha {
            internal fun settle(): String = FeatureTaskRuntimeRunLoopBeta.settle()
          }
          """.trimIndent(),
        "core/FeatureTaskRuntimeRunLoopBeta.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          object FeatureTaskRuntimeRunLoopBeta {
            internal fun settle(): String = FeatureTaskRuntimeRunLoopAlpha.settle()
          }
          """.trimIndent(),
      )
    assertEquals(
      listOf(listOf("FeatureTaskRuntimeRunLoopAlpha", "FeatureTaskRuntimeRunLoopBeta")),
      ArchitectureScanSupport.cyclicComponents(runLoopStepEdges(sources)),
    )
  }

  @Test
  fun `run-loop top-level declarations call no run-loop step declaration`() {
    val calls = runLoopTopLevelStepCalls(runLoopSources())
    assertEquals(
      emptyList(),
      calls,
      "Top-level run-loop declarations are invisible to the step graph; move these into a step:\n" +
        calls.joinToString("\n"),
    )
  }

  @Test
  fun `run-loop top-level census reports helpers that hide a step edge`() {
    val sources =
      mapOf(
        "core/FeatureTaskRuntimeRunLoopAlpha.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          object FeatureTaskRuntimeRunLoopAlpha {
            internal fun settle(): String = relay()
          }
          """.trimIndent(),
        "core/FeatureTaskRuntimeRunLoopRelay.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          internal fun relay(): String =
            FeatureTaskRuntimeRunLoopBeta.settle()

          internal class RelayHolder {
            internal fun label(): String = "${'$'}{FeatureTaskRuntimeRunLoopGamma.settle()}"
          }
          """.trimIndent(),
        "core/FeatureTaskRuntimeRunLoopBeta.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          object FeatureTaskRuntimeRunLoopBeta {
            internal fun settle(): String = FeatureTaskRuntimeRunLoopAlpha.settle()
          }
          """.trimIndent(),
        "core/FeatureTaskRuntimeRunLoopGamma.kt" to
          """
          package skillbill.engine.featuretask.runloop.core

          @OptIn(ExperimentalStdlibApi::class) internal sealed class FeatureTaskRuntimeRunLoopGamma {
            internal fun settle(): String = "gamma"
          }
          """.trimIndent(),
      )
    assertEquals(
      listOf(
        "core/FeatureTaskRuntimeRunLoopRelay.kt: RelayHolder -> FeatureTaskRuntimeRunLoopGamma",
        "core/FeatureTaskRuntimeRunLoopRelay.kt: relay -> FeatureTaskRuntimeRunLoopBeta",
      ),
      runLoopTopLevelStepCalls(sources),
    )
  }

  @Test
  fun `unused step imports do not invent a run-loop dependency cycle`() {
    val sources =
      mapOf(
        "Alpha.kt" to
          """
          package example
          import example.FeatureTaskRuntimeRunLoopBeta
          object FeatureTaskRuntimeRunLoopAlpha { fun value() = "alpha" }
          """.trimIndent(),
        "Beta.kt" to
          """
          package example
          import example.FeatureTaskRuntimeRunLoopAlpha
          object FeatureTaskRuntimeRunLoopBeta { fun value() = "beta" }
          """.trimIndent(),
      )
    assertEquals(emptyList(), ArchitectureScanSupport.cyclicComponents(runLoopStepEdges(sources)))
  }

  @Test
  fun `multiline run-loop constructors keep their body edges in the step graph`() {
    val sources =
      mapOf(
        "Bindings.kt" to
          """
          package example
          open class FeatureTaskRuntimeRunLoopBase
          interface BoundRole
          class FeatureTaskRuntimeRunLoopBound(
            val value: String,
          ) : FeatureTaskRuntimeRunLoopBase(),
            BoundRole {
            fun value() = FeatureTaskRuntimeRunLoopLeaf.value()
          }
          object FeatureTaskRuntimeRunLoopLeaf { fun value() = "leaf" }
          """.trimIndent(),
      )
    assertEquals(
      setOf("FeatureTaskRuntimeRunLoopBase", "FeatureTaskRuntimeRunLoopLeaf"),
      runLoopStepEdges(sources).getValue("FeatureTaskRuntimeRunLoopBound"),
    )
    assertEquals(emptyList(), runLoopTopLevelStepCalls(sources))
  }

  private fun runLoopTopLevelStepCalls(sources: Map<String, String>): List<String> {
    val segmentsByPath =
      sources.mapValues { (_, source) -> runLoopStepSegments(strippedRunLoopSource(source)) }
    val stepNames = segmentsByPath.values.flatMap { (_, bodies) -> bodies.keys }.toSet() - RUN_LOOP_CARRIERS
    return segmentsByPath.flatMap { (path, segments) ->
      topLevelDeclarationSegments(segments.first).flatMap { (declaration, body) ->
        stepNames
          .filter { step -> Regex("""\b${Regex.escape(step)}\b(?!\s*\.\s*[A-Z])""").containsMatchIn(body) }
          .map { step -> "$path: $declaration -> $step" }
      }
    }.sorted()
  }

  private fun topLevelDeclarationSegments(fileScope: String): List<Pair<String, String>> {
    var braceDepth = 0
    var current: Pair<String, StringBuilder>? = null
    val segments = mutableListOf<Pair<String, StringBuilder>>()
    fileScope.lineSequence().forEach { line ->
      if (braceDepth == 0 && line.isNotBlank() && !line.first().isWhitespace() && line.first() !in ")]}") {
        current =
          topLevelDeclarationName(line)?.let { name ->
            (name to StringBuilder()).also(segments::add)
          }
      }
      current?.second?.appendLine(line)
      braceDepth += line.count { character -> character == '{' } - line.count { character -> character == '}' }
    }
    return segments.map { (declaration, body) -> declaration to body.toString() }
  }

  private fun topLevelDeclarationName(line: String): String? =
    TOP_LEVEL_FUNCTION.find(line)?.groupValues?.get(1)
      ?: TOP_LEVEL_TYPE.find(line)?.groupValues?.get(1)
      ?: TOP_LEVEL_PROPERTY.find(line)?.groupValues?.get(1)

  private fun runLoopSources(): Map<String, String> {
    val runLoopRoot =
      ArchitectureScanSupport.runtimeRoot.resolve(
        "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/runloop",
      )
    return kotlinFilesUnderWithArchitectureAsserts(runLoopRoot)
      .associate { path -> runLoopRoot.relativize(path).toString() to path.readText() }
  }

  private fun strippedRunLoopSource(source: String): String =
    CommentStripper(
      source,
      blankStringLiterals = true,
      preserveTemplateExpressions = true,
    ).strip()

  private fun runLoopStepEdges(sources: Map<String, String>): Map<String, Set<String>> {
    val segmentsByPath =
      sources.mapValues { (_, source) -> runLoopStepSegments(strippedRunLoopSource(source)) }
    val stepNames = segmentsByPath.values.flatMap { (_, bodies) -> bodies.keys }.toSet() - RUN_LOOP_CARRIERS
    return buildMap {
      segmentsByPath.values.forEach { (fileScope, bodies) ->
        bodies.filterKeys { name -> name in stepNames }.forEach { (name, body) ->
          put(
            name,
            stepNames.filterTo(mutableSetOf()) { candidate ->
              candidate != name && referencesDeclaration(candidate, fileScope + body)
            },
          )
        }
      }
    }
  }

  private fun runLoopStepSegments(source: String): Pair<String, Map<String, String>> {
    var braceDepth = 0
    var parenDepth = 0
    var current: String? = null
    var entered = false
    val bodies = linkedMapOf<String, StringBuilder>()
    val fileScope = StringBuilder()
    val lines = source.lineSequence().filterNot { it.trimStart().startsWith("import ") }.toList()
    lines.forEachIndexed { index, line ->
      if (current == null && braceDepth == 0) {
        STEP_DECLARATION.find(line)?.groupValues?.get(1)?.let { name ->
          current = name
          entered = false
          parenDepth = 0
          bodies[name] = StringBuilder()
        }
      }
      current?.let { name -> bodies.getValue(name).appendLine(line) } ?: fileScope.appendLine(line)
      braceDepth += line.count { character -> character == '{' }
      braceDepth -= line.count { character -> character == '}' }
      parenDepth += line.count { character -> character == '(' }
      parenDepth -= line.count { character -> character == ')' }
      if (braceDepth > 0) entered = true
      val bodyClosed = entered
      val nextLine = lines.drop(index + 1).firstOrNull { it.isNotBlank() }
      val bodyLessDeclarationClosed = parenDepth <= 0 && (nextLine == null || !nextLine.first().isWhitespace())
      if (braceDepth <= 0 && (bodyClosed || bodyLessDeclarationClosed)) {
        current = null
        entered = false
      }
    }
    return fileScope.toString() to bodies.mapValues { (_, body) -> body.toString() }
  }

  private fun referencesDeclaration(
    name: String,
    text: String,
  ): Boolean = Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)

  private companion object {
    const val PHASE_BLOCKING_STEP = "FeatureTaskRuntimeRunLoopPhaseBlocking"

    val RUN_LOOP_CARRIERS = setOf("FeatureTaskRuntimeRunLoopSession", "FeatureTaskRuntimeRunLoopContext")

    val DECLARATION_MODIFIERS =
      """(?:(?:internal|private|public|protected|abstract|sealed|data|value|enum|annotation|open|""" +
        """final|inline|suspend|operator|infix|tailrec|const|expect|actual|lateinit)\s+)*"""

    val STEP_DECLARATION =
      Regex(
        """^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*$DECLARATION_MODIFIERS""" +
          """(?:object|class|interface)\s+(FeatureTaskRuntimeRunLoop[A-Za-z0-9_]*)\b""",
      )

    val TOP_LEVEL_TYPE =
      Regex(
        """^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*$DECLARATION_MODIFIERS""" +
          """(?:object|class|interface|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
      )

    val TOP_LEVEL_PROPERTY =
      Regex(
        """^(?:@[A-Za-z_][A-Za-z0-9_.]*(?:\([^)]*\))?\s+)*$DECLARATION_MODIFIERS""" +
          """(?:val|var)\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?([A-Za-z_][A-Za-z0-9_]*)\b""",
      )

    val TOP_LEVEL_FUNCTION =
      Regex(
        """^(?:(?:internal|private|public|inline|suspend|operator|infix)\s+)*fun\s+""" +
          """(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?, ]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(""",
      )
  }
}

class FeatureTaskStepIdentityArchitectureTest {
  @Test
  fun `no shared feature-task file decides behaviour by step identity`() {
    val sources = featureTaskEngineSources()
    val read = FeatureTaskStepIdentityScan.scannedSources(sources).size
    assertTrue(read > 0, "The step-identity rule read no shared feature-task file.")

    val violations = FeatureTaskStepIdentityScan.violations(sources, STEP_IDS)

    assertEquals(emptyList(), violations, "Read $read files.\n" + violations.joinToString("\n"))
  }

  @Test
  fun `slot declares no non-private property exposing a single step id`() {
    val sources = featureTaskEngineSources()
    val read = sources.keys.count(::isFeatureTaskSlotPath)
    assertTrue(read > 0, "The step-id alias rule read no slot file.")

    val aliases = FeatureTaskStepIdentityScan.stepIdAliases(sources, STEP_IDS)

    assertEquals(emptyList(), aliases, "Read $read slot files.\n" + aliases.joinToString("\n"))
  }

  @Test
  fun `step-identity rule catches phase-id constants however they are imported`() {
    val source =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds
      import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePhaseIds.*
      import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
      import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD
      import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition as Steps

      internal object Synthetic {
        fun ids(step: String) = step == FeatureTaskRuntimePhaseIds.REVIEW
        fun definition(step: String) = step == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
        fun imported(step: String) = step == PHASE_BUILD
        fun aliased(step: String) = step == Steps.PHASE_PR
        fun starred(step: String) = step == VERIFY_FINDINGS
      }
      """.trimIndent()

    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt:4 phase-id constant FeatureTaskRuntimePhaseIds.*",
        "runloop/core/Synthetic.kt:6 phase-id constant FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_BUILD",
        "runloop/core/Synthetic.kt:10 phase-id constant FeatureTaskRuntimePhaseIds.REVIEW",
        "runloop/core/Synthetic.kt:11 phase-id constant FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT",
        "runloop/core/Synthetic.kt:12 phase-id constant PHASE_BUILD",
        "runloop/core/Synthetic.kt:13 phase-id constant Steps.PHASE_PR",
        "runloop/core/Synthetic.kt:14 phase-id constant VERIFY_FINDINGS",
      ),
      FeatureTaskStepIdentityScan.violations(mapOf("runloop/core/Synthetic.kt" to source), STEP_IDS),
    )
  }

  @Test
  fun `step-identity rule scans every package outside slot`() {
    val source =
      """
      package skillbill.engine.featuretask.runloop.core

      internal object Synthetic {
        fun review(step: String) = step == "review"
      }
      """.trimIndent()
    val sources =
      mapOf(
        "runloop/core/Synthetic.kt" to source,
        "validation/Synthetic.kt" to source,
        "slot/codereview/Synthetic.kt" to source,
      )

    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt:4 step-id literal \"review\"",
        "validation/Synthetic.kt:4 step-id literal \"review\"",
      ),
      FeatureTaskStepIdentityScan.violations(sources, STEP_IDS),
    )
  }

  @Test
  fun `step-identity rule catches whole string literals equal to a step id`() {
    val source =
      """
      package skillbill.engine.featuretask.review.core

      internal object Synthetic {
        fun review(step: String) = step == "review"
        fun planning(step: String) = step in setOf("plan", "planning")
        fun fix(step: String) = step == "verify_findings" || step == 'r'.toString()
        fun escaped() = "say \"audit\" twice"
        val note = "the build step"
      }
      """.trimIndent()

    assertEquals(
      listOf(
        "review/core/Synthetic.kt:4 step-id literal \"review\"",
        "review/core/Synthetic.kt:5 step-id literal \"plan\"",
        "review/core/Synthetic.kt:6 step-id literal \"verify_findings\"",
      ),
      FeatureTaskStepIdentityScan.violations(mapOf("review/core/Synthetic.kt" to source), STEP_IDS),
    )
  }

  @Test
  fun `step-identity rule catches element access on a slot's steps`() {
    val source =
      """
      package skillbill.engine.featuretask.phase.core

      import skillbill.workflow.taskruntime.model.core.PhaseSlot

      internal object Synthetic {
        fun indexed() = PhaseSlot.CODE_REVIEW.steps[1]
        fun first() = PhaseSlot.QUALITY_GATE.steps.first()
        fun last() = PhaseSlot.IMPLEMENTATION.stepIds.lastOrNull()
        fun single() = PhaseSlot.PLAN.steps.single()
        fun got() = PhaseSlot.CODE_REVIEW.steps.get(2)
        fun element() = PhaseSlot.CODE_REVIEW.steps.elementAt(0)
        fun terminal() = FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last()
        fun owns(step: String) = step in PhaseSlot.CODE_REVIEW.steps
      }
      """.trimIndent()

    assertEquals(
      listOf(
        "phase/core/Synthetic.kt:6 step element access PhaseSlot.CODE_REVIEW.steps",
        "phase/core/Synthetic.kt:7 step element access PhaseSlot.QUALITY_GATE.steps.first",
        "phase/core/Synthetic.kt:8 step element access PhaseSlot.IMPLEMENTATION.stepIds.lastOrNull",
        "phase/core/Synthetic.kt:9 step element access PhaseSlot.PLAN.steps.single",
        "phase/core/Synthetic.kt:10 step element access PhaseSlot.CODE_REVIEW.steps.get",
        "phase/core/Synthetic.kt:11 step element access PhaseSlot.CODE_REVIEW.steps.elementAt",
        "phase/core/Synthetic.kt:12 step element access " +
          "FeatureTaskRuntimePhaseWorkflowDefinition.definition.stepIds.last",
      ),
      FeatureTaskStepIdentityScan.violations(mapOf("phase/core/Synthetic.kt" to source), STEP_IDS),
    )
  }

  @Test
  fun `step-identity rule catches a run-loop use of a slot alias object`() {
    val alias =
      """
      package skillbill.engine.featuretask.slot.codereview

      import skillbill.workflow.taskruntime.model.core.PhaseSlot

      internal object ReviewStepNames {
        const val REVIEW_STEP = "review"
        val fixStep: String = PhaseSlot.CODE_REVIEW.steps[2]
      }
      """.trimIndent()
    val runLoop =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.slot.codereview.ReviewStepNames

      internal object Synthetic {
        fun review(step: String) = step == ReviewStepNames.REVIEW_STEP
        fun fix(step: String) = step == ReviewStepNames.fixStep
      }
      """.trimIndent()

    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt:6 step-id alias REVIEW_STEP",
        "runloop/core/Synthetic.kt:7 step-id alias fixStep",
      ),
      FeatureTaskStepIdentityScan.violations(
        mapOf("slot/codereview/ReviewStepNames.kt" to alias, "runloop/core/Synthetic.kt" to runLoop),
        STEP_IDS,
      ),
    )
  }

  @Test
  fun `step-identity rule catches a slot enum whose entries wrap step ids however it is referenced`() {
    val roles =
      """
      package skillbill.engine.featuretask.slot

      import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

      internal enum class SyntheticStepRole(val stepId: String) {
        REVIEW_PASS(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
        ACCEPTANCE(stepId = "audit"),
        PLANNING(
          "plan",
        ),
        ;

        fun matches(candidate: String?): Boolean = candidate == stepId
      }

      internal enum class SyntheticGate(val label: String) {
        BUILD("build gate"),
        VALIDATION("validation gate"),
      }

      private enum class HiddenRole(val stepId: String) {
        REVIEW(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW),
      }
      """.trimIndent()
    val runLoop =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.slot.SyntheticGate
      import skillbill.engine.featuretask.slot.SyntheticStepRole
      import skillbill.engine.featuretask.slot.SyntheticStepRole.*
      import skillbill.engine.featuretask.slot.SyntheticStepRole.PLANNING
      import skillbill.engine.featuretask.slot.SyntheticStepRole as Roles

      internal object Synthetic {
        fun qualified(step: String) = SyntheticStepRole.REVIEW_PASS.matches(step)
        fun aliased(step: String) = Roles.ACCEPTANCE.stepId == step
        fun imported(step: String) = PLANNING.matches(step)
        fun starred(step: String) = ACCEPTANCE.matches(step)
        fun gate() = SyntheticGate.BUILD
        fun count() = SyntheticStepRole.entries.size
      }
      """.trimIndent()
    val sources = mapOf("slot/SyntheticStepRole.kt" to roles, "runloop/core/Synthetic.kt" to runLoop)

    assertEquals(
      listOf(
        "slot/SyntheticStepRole.kt:6 step-id alias SyntheticStepRole.REVIEW_PASS",
        "slot/SyntheticStepRole.kt:7 step-id alias SyntheticStepRole.ACCEPTANCE",
        "slot/SyntheticStepRole.kt:8 step-id alias SyntheticStepRole.PLANNING",
      ),
      FeatureTaskStepIdentityScan.stepIdAliases(sources, STEP_IDS),
    )
    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt:5 step-id alias SyntheticStepRole.*",
        "runloop/core/Synthetic.kt:6 step-id alias SyntheticStepRole.PLANNING",
        "runloop/core/Synthetic.kt:10 step-id alias SyntheticStepRole.REVIEW_PASS",
        "runloop/core/Synthetic.kt:11 step-id alias Roles.ACCEPTANCE",
        "runloop/core/Synthetic.kt:12 step-id alias PLANNING",
        "runloop/core/Synthetic.kt:13 step-id alias ACCEPTANCE",
      ),
      FeatureTaskStepIdentityScan.violations(sources, STEP_IDS),
    )
  }

  @Test
  fun `slot alias census reports exposed single-step properties and skips private, local and strategy ones`() {
    val source =
      """
      package skillbill.engine.featuretask.slot.audit

      import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition

      internal class SyntheticStrategy(
        private val label: String = "audit",
      ) : PhaseStrategy {
        override val entryStep: String = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
        internal val auditStep = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT
        private val hidden = "audit"

        fun decide(step: String): Boolean {
          val local = "audit"
          return step == local
        }

        companion object {
          const val AUDIT =
            "audit"
        }
      }

      val topLevelAudit get() = PhaseSlot.AUDIT.steps.single()
      """.trimIndent()

    assertEquals(
      listOf(
        "slot/audit/SyntheticStrategy.kt:9 step-id alias auditStep",
        "slot/audit/SyntheticStrategy.kt:18 step-id alias AUDIT",
        "slot/audit/SyntheticStrategy.kt:23 step-id alias topLevelAudit",
      ),
      FeatureTaskStepIdentityScan.stepIdAliases(mapOf("slot/audit/SyntheticStrategy.kt" to source), STEP_IDS),
    )
  }

  @Test
  fun `step-identity rule passes slot-membership decisions and unrelated names`() {
    val source =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.phase.prompt.directives.PHASE_PROMPT_TEMPLATE_INDENT
      import skillbill.workflow.taskruntime.model.core.PhaseSlot

      internal object Synthetic {
        fun owns(step: String) = PhaseSlot.slotForStep(step) == PhaseSlot.CODE_REVIEW
        fun member(step: String) = step in PhaseSlot.QUALITY_GATE.steps
        fun indent() = PHASE_PROMPT_TEMPLATE_INDENT + "reviewer" + "plan_review"
      }
      """.trimIndent()

    assertEquals(
      emptyList(),
      FeatureTaskStepIdentityScan.violations(mapOf("runloop/core/Synthetic.kt" to source), STEP_IDS),
    )
  }

  private companion object {
    val STEP_IDS: List<String> = PhaseSlot.entries.flatMap { slot -> slot.steps }
  }
}

class FeatureTaskLaunchPortArchitectureTest {
  @Test
  fun `only the PhaseRunner implementation depends on GoalRunnerSubtaskLauncher`() {
    val sources = featureTaskEngineSources()
    assertTrue(sources.isNotEmpty(), "The launch-port rule read no feature-task file.")

    val violations = FeatureTaskLaunchPortScan.violations(sources)

    assertEquals(emptyList(), violations, "Read ${sources.size} files.\n" + violations.joinToString("\n"))
  }

  @Test
  fun `goal planning reaches GoalRunnerSubtaskLauncher only through the PhaseRunner`() {
    val sources = goalPlanningEngineSources()
    assertTrue(sources.keys.any { it.startsWith("goalrunner/planning/sweep/") }, "Read no goal-planning sweep file.")

    val violations = FeatureTaskLaunchPortScan.violations(sources)

    assertEquals(emptyList(), violations, "Read ${sources.size} files.\n" + violations.joinToString("\n"))
  }

  @Test
  fun `operations reach GoalRunnerSubtaskLauncher only through the PhaseRunner`() {
    val sources = operationEngineSources()
    assertTrue(sources.keys.any { it.startsWith("operation/core/") }, "Read no operation core file.")

    val violations = FeatureTaskLaunchPortScan.violations(sources)

    assertEquals(emptyList(), violations, "Read ${sources.size} files.\n" + violations.joinToString("\n"))
  }

  @Test
  fun `launch-port rule catches an operation launcher dependency even in a PhaseRunner implementation`() {
    val operation =
      """
      package skillbill.engine.operation.release

      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

      internal class SyntheticReleaseOperation(private val launcher: GoalRunnerSubtaskLauncher)
      """.trimIndent()
    val runner =
      """
      package skillbill.engine.operation.core

      import skillbill.engine.featuretask.slot.PhaseRunner

      internal class SyntheticOperationRunner(
        private val launcher: skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher,
      ) : PhaseRunner
      """.trimIndent()

    assertEquals(
      listOf(
        "operation/release/SyntheticReleaseOperation.kt imports " +
          "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
        "operation/core/SyntheticOperationRunner.kt references " +
          "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
      ),
      FeatureTaskLaunchPortScan.violations(
        mapOf(
          "operation/release/SyntheticReleaseOperation.kt" to operation,
          "operation/core/SyntheticOperationRunner.kt" to runner,
        ),
      ),
    )
  }

  @Test
  fun `launch-port rule catches a goal-planning launcher dependency`() {
    val sweep =
      """
      package skillbill.engine.goalrunner.planning.sweep

      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

      data class SyntheticBoundaries(val subtaskLauncher: GoalRunnerSubtaskLauncher)
      """.trimIndent()
    val runner =
      """
      package skillbill.engine.goalrunner.planning.attempt

      import skillbill.engine.featuretask.slot.PhaseRunner

      internal class SyntheticPlanningRunner(
        private val launcher: skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher,
      ) : PhaseRunner
      """.trimIndent()

    assertEquals(
      listOf(
        "goalrunner/planning/sweep/SyntheticBoundaries.kt imports " +
          "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
        "goalrunner/planning/attempt/SyntheticPlanningRunner.kt references " +
          "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
      ),
      FeatureTaskLaunchPortScan.violations(
        mapOf(
          "goalrunner/planning/sweep/SyntheticBoundaries.kt" to sweep,
          "goalrunner/planning/attempt/SyntheticPlanningRunner.kt" to runner,
        ),
      ),
    )
  }

  @Test
  fun `launch-port rule catches run-loop and strategy launcher references and passes the runner`() {
    val runLoop =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
      import skillbill.ports.goalrunner.runner.*

      internal class Synthetic(private val launcher: GoalRunnerSubtaskLauncher)
      """.trimIndent()
    val strategy =
      """
      package skillbill.engine.featuretask.slot.audit

      import skillbill.engine.featuretask.slot.PhaseRunner

      internal class SyntheticStrategy(
        private val runner: PhaseRunner,
        private val launcher: skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher,
      )
      """.trimIndent()
    val runner =
      """
      package skillbill.engine.featuretask.slot.runner

      import skillbill.engine.featuretask.slot.PhaseRunner
      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

      internal class SyntheticPhaseRunner(
        private val launcher: GoalRunnerSubtaskLauncher,
      ) : PhaseRunner
      """.trimIndent()

    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt imports skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
        "runloop/core/Synthetic.kt imports skillbill.ports.goalrunner.runner.*",
        "slot/audit/SyntheticStrategy.kt references skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
      ),
      FeatureTaskLaunchPortScan.violations(
        mapOf(
          "runloop/core/Synthetic.kt" to runLoop,
          "slot/audit/SyntheticStrategy.kt" to strategy,
          "slot/runner/SyntheticPhaseRunner.kt" to runner,
        ),
      ),
    )
  }
}

class FeatureTaskSlotDependencyDirectionArchitectureTest {
  @Test
  fun `shared feature-task code imports no strategy package and strategies stay off the run loop`() {
    val sources = featureTaskEngineSources()
    val shared = sources.keys.count { path -> !isFeatureTaskSlotPath(path) }
    val strategies = sources.keys.count(FeatureTaskDependencyDirectionScan::isStrategyPath)
    assertTrue(shared > 0 && strategies > 0, "Read $shared shared and $strategies strategy files.")

    val violations = FeatureTaskDependencyDirectionScan.violations(sources)

    assertEquals(
      emptyList(),
      violations,
      "Read $shared shared and $strategies strategy files.\n" + violations.joinToString("\n"),
    )
  }

  @Test
  fun `dependency-direction rule catches strategy imports, run-loop drivers, run state and context`() {
    val shared =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.slot.PhaseStrategy
      import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
      import skillbill.engine.featuretask.slot.runner.DefaultPhaseRunner
      import skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy
      import skillbill.engine.featuretask.slot.qualitygate.packbuild.*

      internal class SharedSynthetic(
        private val review: skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy,
      )
      """.trimIndent()
    val strategy =
      """
      package skillbill.engine.featuretask.slot.audit

      import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive
      import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch
      import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopContext
      import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch
      import skillbill.engine.featuretask.runloop.core.PhaseRun
      import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState as RunState

      internal class SyntheticStrategy(private val state: RunState) {
        fun run(context: FeatureTaskRuntimeRunLoopContext) = context
      }
      """.trimIndent()
    val machinery =
      """
      package skillbill.engine.featuretask.slot.attempt

      import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch

      internal class SyntheticAttempt(private val context: FeatureTaskRuntimeRunLoopContext)
      """.trimIndent()

    assertEquals(
      listOf(
        "runloop/core/SharedSynthetic.kt imports skillbill.engine.featuretask.slot.audit.AcceptanceAuditStrategy",
        "runloop/core/SharedSynthetic.kt imports skillbill.engine.featuretask.slot.qualitygate.packbuild.*",
        "runloop/core/SharedSynthetic.kt references " +
          "skillbill.engine.featuretask.slot.codereview.InlineReviewStrategy",
        "slot/audit/SyntheticStrategy.kt imports " +
          "skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopDrive",
        "slot/audit/SyntheticStrategy.kt imports " +
          "skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopLaunch",
        "slot/audit/SyntheticStrategy.kt imports " +
          "skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopPlanningBranch",
        "slot/audit/SyntheticStrategy.kt references FeatureTaskRuntimeRunState",
        "slot/audit/SyntheticStrategy.kt references FeatureTaskRuntimeRunLoopContext",
        "slot/attempt/SyntheticAttempt.kt references FeatureTaskRuntimeRunLoopContext",
      ),
      FeatureTaskDependencyDirectionScan.violations(
        mapOf(
          "runloop/core/SharedSynthetic.kt" to shared,
          "slot/audit/SyntheticStrategy.kt" to strategy,
          "slot/attempt/SyntheticAttempt.kt" to machinery,
        ),
      ),
    )
  }

  @Test
  fun `dependency-direction rule passes shared code on the slot contract and strategies on PhaseRun`() {
    val shared =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.slot.PhaseStrategy
      import skillbill.engine.featuretask.slot.attempt.PhaseAttemptLoop
      import skillbill.engine.featuretask.slot.*
      """.trimIndent()
    val strategy =
      """
      package skillbill.engine.featuretask.slot.qualitygate

      import skillbill.engine.featuretask.runloop.core.PhaseRun
      import skillbill.engine.featuretask.slot.PhaseRunState
      import skillbill.engine.featuretask.slot.qualitygate.packbuild.PackBuildStrategy
      """.trimIndent()

    assertEquals(
      emptyList(),
      FeatureTaskDependencyDirectionScan.violations(
        mapOf("runloop/core/SharedSynthetic.kt" to shared, "slot/qualitygate/QualityGateSteps.kt" to strategy),
      ),
    )
  }
}

class FeatureTaskDurableStoreArchitectureTest {
  @Test
  fun `only the durable run state depends on durable stores, writers and checkpoint git ops`() {
    val sources = featureTaskEngineSources()
    val guarded = FeatureTaskDurableStoreScan.guardedSources(sources).size
    val durable = FeatureTaskDurableStoreScan.durableReferences(sources)
    assertTrue(guarded > 0, "The durable-store rule read no run-loop or slot file.")
    assertTrue(durable.isNotEmpty(), "The durable package references no durable store; the name list is stale.")
    listOf("runloop/", "slot/", "phaserun/").forEach { root ->
      assertTrue(
        FeatureTaskDurableStoreScan.guardedSources(sources).keys.any { path -> path.startsWith(root) },
        "The durable-store rule read no file under $root.",
      )
    }

    val violations = FeatureTaskDurableStoreScan.violations(sources)

    assertEquals(emptyList(), violations, "Read $guarded files.\n" + violations.joinToString("\n"))
  }

  @Test
  fun `durable-store rule catches run-loop and slot store references and passes the durable package`() {
    val runLoop =
      """
      package skillbill.engine.featuretask.runloop.core

      import skillbill.engine.featuretask.persist.FeatureTaskRuntimePhaseRecorder
      import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunState

      internal class Synthetic(private val recorder: FeatureTaskRuntimePhaseRecorder)
      """.trimIndent()
    val edge =
      """
      package skillbill.engine.featuretask.runloop.core

      internal fun establish(gates: Any) = gates.branchSetupRunner.ensureFeatureBranch(request, telemetry, phase)
      """.trimIndent()
    val strategy =
      """
      package skillbill.engine.featuretask.slot.audit

      internal class SyntheticStrategy {
        fun commit(git: Any) = git.toString().also { amendHeadCommit() }
      }
      """.trimIndent()
    val planStop =
      """
      package skillbill.engine.featuretask.slot.plan

      import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder as Terminals

      internal class SyntheticStop(private val terminals: Terminals, private val git: Any) {
        fun pin() = git.updateCheckpointRef(root, prefix, name, sha)
      }
      """.trimIndent()
    val durable =
      """
      package skillbill.engine.featuretask.runloop.durable

      import skillbill.engine.featuretask.persist.FeatureTaskRuntimePhaseRecorder

      internal class SyntheticDurable(private val recorder: FeatureTaskRuntimePhaseRecorder)
      """.trimIndent()

    assertEquals(
      listOf(
        "runloop/core/Synthetic.kt imports skillbill.engine.featuretask.runloop.durable.DurablePhaseRunState",
        "runloop/core/Synthetic.kt references FeatureTaskRuntimePhaseRecorder",
        "runloop/core/SyntheticEdge.kt references branchSetupRunner",
        "slot/audit/SyntheticStrategy.kt references amendHeadCommit",
        "slot/plan/SyntheticStop.kt references FeatureTaskRuntimeDecomposeTerminalRecorder",
        "slot/plan/SyntheticStop.kt references updateCheckpointRef",
      ),
      FeatureTaskDurableStoreScan.violations(
        mapOf(
          "runloop/core/Synthetic.kt" to runLoop,
          "runloop/core/SyntheticEdge.kt" to edge,
          "slot/audit/SyntheticStrategy.kt" to strategy,
          "slot/plan/SyntheticStop.kt" to planStop,
          "runloop/durable/SyntheticDurable.kt" to durable,
        ),
      ),
    )
  }

  @Test
  fun `the phase-run entry stays off durable stores, step ids, definition ids and the launch port`() {
    val sources = featureTaskEngineSources()
    val read = sources.keys.count(::isFeatureTaskPhaseRunPath)
    assertTrue(read > 0, "The phase-run rules read no phaserun file.")

    assertEquals(emptyList(), FeatureTaskPhaseRunDefinitionScan.violations(sources), "Read $read phaserun files.")
  }

  @Test
  fun `phase-run rules catch a synthetic entry that decides by definition, step or durable store`() {
    val entry =
      """
      package skillbill.engine.featuretask.phaserun

      import skillbill.engine.featuretask.runloop.durable.DurablePhaseRunState
      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher

      internal class SyntheticEntry(private val launcher: GoalRunnerSubtaskLauncher) {
        fun review(definition: Any) = definition == SkeletonDefinition.REVIEW
        fun step(step: String) = step == "review"
        fun commit(git: Any) = git.toString().also { amendHeadCommit() }
      }
      """.trimIndent()
    val sources = mapOf("phaserun/SyntheticEntry.kt" to entry)

    assertEquals(
      listOf("phaserun/SyntheticEntry.kt references SkeletonDefinition.REVIEW"),
      FeatureTaskPhaseRunDefinitionScan.violations(sources),
    )
    assertEquals(
      listOf(
        "phaserun/SyntheticEntry.kt imports skillbill.engine.featuretask.runloop.durable.DurablePhaseRunState",
        "phaserun/SyntheticEntry.kt references amendHeadCommit",
      ),
      FeatureTaskDurableStoreScan.violations(sources),
    )
    assertEquals(
      listOf("phaserun/SyntheticEntry.kt:8 step-id literal \"review\""),
      FeatureTaskStepIdentityScan.violations(sources, PhaseSlot.entries.flatMap { slot -> slot.steps }),
    )
    assertEquals(
      listOf(
        "phaserun/SyntheticEntry.kt imports skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
      ),
      FeatureTaskLaunchPortScan.violations(sources),
    )
  }
}
