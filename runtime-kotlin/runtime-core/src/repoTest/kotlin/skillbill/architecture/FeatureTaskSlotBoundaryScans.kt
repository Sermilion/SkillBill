package skillbill.architecture

import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

internal const val FEATURE_TASK_ENGINE_ROOT =
  "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask"

internal fun featureTaskEngineSources(): Map<String, String> {
  val root = ArchitectureScanSupport.runtimeRoot.resolve(FEATURE_TASK_ENGINE_ROOT)
  return kotlinFilesUnderWithArchitectureAsserts(root).associate { path ->
    root.relativize(path).invariantSeparatorsPathString to path.readText()
  }
}

internal fun isFeatureTaskSlotPath(path: String): Boolean = path.startsWith("slot/")

internal val STEP_OWNED_PACKAGES: List<String> = listOf("phase/", "lifecycle/")

internal object FeatureTaskStepIdentityScan {
  private const val CONSTANT_FORM = "phase-id constant"
  private const val LITERAL_FORM = "step-id literal"
  private const val ELEMENT_FORM = "step element access"
  private const val ALIAS_FORM = "step-id alias"
  private const val DEFINITION = "FeatureTaskRuntimePhaseWorkflowDefinition"
  private const val PHASE_IDS = "FeatureTaskRuntimePhaseIds"
  private const val TRIPLE_QUOTE = "\"\"\""

  private val STRATEGY_STEP_PROPERTIES = setOf("entryStep", "entryStepId", "steps", "stepIds")
  private val WHITESPACE = Regex("""\s+""")
  private val LITERAL = Regex("\"{3}[\\s\\S]*?\"{3}|\"[^\"\\n]*\"")
  private val STEP_LITERAL = Regex("\"([^\"\\\\]*)\"")
  private val SINGLE_CONSTANT =
    Regex("""(?:[\w.]*\.)?(PHASE_[A-Z0-9_]+)|(?:[\w.]*\.)?$PHASE_IDS\s*\.\s*([A-Z0-9_]+)""")
  private val ELEMENT_ACCESS =
    Regex(
      """(?:\b\w+\s*\.\s*)+(?:steps|stepIds)\b""" +
        """(?:(?=\s*\[)|\s*\.\s*(?:first|last|single|get|elementAt)\w*)""",
    )
  private val ALIAS_DECLARATION =
    Regex(
      """^\s*(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:(?:public|internal|protected|private|override|const|open|""" +
        """final|actual)\s+)*)va[lr]\s+(\w+)\s*(?::\s*[\w.<>?, ]+?)?\s*(?:get\s*\(\s*\)\s*)?=\s*(.+?)\s*;?\s*$""",
    )

  fun violations(
    sources: Map<String, String>,
    stepIds: List<String>,
    scannedPackages: List<String>,
  ): List<String> {
    val vocabulary = StepVocabulary(stepIds)
    val aliasNames = slotAliases(sources, vocabulary).map { alias -> alias.name }.toSet()
    return scannedSources(sources, scannedPackages).flatMap { (path, source) ->
      val scanned = ScannedKotlinSource(source)
      val hits =
        constantHits(scanned, vocabulary) + literalHits(scanned, vocabulary) +
          elementAccessHits(scanned) + aliasHits(scanned, aliasNames)
      hits.sortedBy { hit -> hit.offset }.map { hit ->
        "$path:${scanned.lineOf(hit.offset)} ${hit.form} ${hit.token}"
      }
    }
  }

  fun scannedSources(
    sources: Map<String, String>,
    scannedPackages: List<String>,
  ): Map<String, String> =
    sources.filterKeys { path -> !isFeatureTaskSlotPath(path) && scannedPackages.any(path::startsWith) }

  fun stepIdAliases(
    sources: Map<String, String>,
    stepIds: List<String>,
  ): List<String> =
    slotAliases(sources, StepVocabulary(stepIds)).map { alias ->
      "${alias.path}:${alias.line} $ALIAS_FORM ${alias.name}"
    }

  private fun slotAliases(
    sources: Map<String, String>,
    vocabulary: StepVocabulary,
  ): List<StepAlias> =
    sources.filterKeys(::isFeatureTaskSlotPath).flatMap { (path, source) ->
      aliasDeclarations(path, source, vocabulary)
    }

  private fun constantHits(
    scanned: ScannedKotlinSource,
    vocabulary: StepVocabulary,
  ): List<StepHit> = vocabulary.owners.flatMap { owner -> ownerHits(scanned, owner) }

  private fun ownerHits(
    scanned: ScannedKotlinSource,
    owner: ConstantOwner,
  ): List<StepHit> {
    val ownerName = Regex.escape(owner.name)
    val aliases =
      Regex("""^\s*import\s+[\w.]*\b$ownerName\s+as\s+(\w+)""", RegexOption.MULTILINE)
        .findAll(scanned.blanked).map { match -> match.groupValues[1] }.toList()
    val qualifiers = (listOf(owner.name) + aliases).joinToString("|") { name -> Regex.escape(name) }
    val qualified =
      Regex("""(?<!\w)(?:$qualifiers)\s*\.\s*${owner.memberPattern}\b""").findAll(scanned.blanked).toList()
    val star = Regex("""\b$ownerName\s*\.\s*\*""").findAll(scanned.blanked).toList()
    val bareNames = importedMemberNames(scanned, owner) + if (star.isEmpty()) emptySet() else owner.bareMembers
    val bare =
      bareNames.flatMap { name ->
        Regex("""(?<![\w.])${Regex.escape(name)}\b""").findAll(scanned.body).toList()
      }
    return (qualified + star + bare).map { match ->
      StepHit(match.range.first, CONSTANT_FORM, compact(match.value))
    }
  }

  private fun importedMemberNames(
    scanned: ScannedKotlinSource,
    owner: ConstantOwner,
  ): Set<String> =
    Regex(
      """^\s*import\s+[\w.]*\b${Regex.escape(owner.name)}\.(${owner.memberPattern})(?:\s+as\s+(\w+))?""",
      RegexOption.MULTILINE,
    ).findAll(scanned.blanked).map { match -> match.groupValues[2].ifEmpty { match.groupValues[1] } }.toSet()

  private fun literalHits(
    scanned: ScannedKotlinSource,
    vocabulary: StepVocabulary,
  ): List<StepHit> =
    LITERAL.findAll(scanned.blanked)
      .filterNot { match -> match.value.startsWith(TRIPLE_QUOTE) }
      .mapNotNull { match ->
        val content = scanned.code.substring(match.range.first + 1, match.range.last)
        StepHit(match.range.first, LITERAL_FORM, "\"$content\"").takeIf { content in vocabulary.literals }
      }.toList()

  private fun elementAccessHits(scanned: ScannedKotlinSource): List<StepHit> =
    ELEMENT_ACCESS.findAll(scanned.body).map { match ->
      StepHit(match.range.first, ELEMENT_FORM, compact(match.value))
    }.toList()

  private fun aliasHits(
    scanned: ScannedKotlinSource,
    aliasNames: Set<String>,
  ): List<StepHit> =
    aliasNames.flatMap { name ->
      Regex("""\b${Regex.escape(name)}\b""").findAll(scanned.blanked).map { match ->
        StepHit(match.range.first, ALIAS_FORM, name)
      }.toList()
    }

  private fun aliasDeclarations(
    path: String,
    source: String,
    vocabulary: StepVocabulary,
  ): List<StepAlias> {
    val scanned = ScannedKotlinSource(source)
    val codeLines = scanned.code.split('\n')
    val tracker = DeclarationScopeTracker()
    return scanned.blanked.split('\n').mapIndexedNotNull { index, line ->
      val memberLevel = tracker.atMemberLevel()
      tracker.consume(line)
      aliasOnLine(codeLines, index, vocabulary)?.takeIf { memberLevel }?.let { name ->
        StepAlias(path, index + 1, name)
      }
    }
  }

  private fun aliasOnLine(
    lines: List<String>,
    index: Int,
    vocabulary: StepVocabulary,
  ): String? {
    val match = ALIAS_DECLARATION.find(declarationText(lines, index)) ?: return null
    val (modifiers, name, initializer) = match.destructured
    val exposed = "private" !in modifiers.split(WHITESPACE) && name !in STRATEGY_STEP_PROPERTIES
    return name.takeIf { exposed && exposesSingleStep(initializer.trim(), vocabulary) }
  }

  private fun declarationText(
    lines: List<String>,
    index: Int,
  ): String {
    val line = lines[index]
    val next = lines.getOrNull(index + 1).orEmpty()
    val continues = line.trimEnd().endsWith("=") || '=' !in line && next.trimStart().startsWith("get")
    return if (continues) "$line ${next.trim()}" else line
  }

  private fun exposesSingleStep(
    initializer: String,
    vocabulary: StepVocabulary,
  ): Boolean =
    STEP_LITERAL.matchEntire(initializer)?.let { match -> match.groupValues[1] in vocabulary.literals } == true ||
      isStepConstant(initializer, vocabulary) ||
      ELEMENT_ACCESS.find(initializer)?.range?.first == 0

  private fun isStepConstant(
    initializer: String,
    vocabulary: StepVocabulary,
  ): Boolean {
    val match = SINGLE_CONSTANT.matchEntire(initializer) ?: return false
    val (phaseConstant, idsMember) = match.destructured
    return phaseConstant in vocabulary.phaseConstants || idsMember in vocabulary.idsMembers
  }

  private fun compact(token: String): String = token.replace(WHITESPACE, "")

  private class StepVocabulary(stepIds: List<String>) {
    val literals: Set<String> = stepIds.toSet()
    val phaseConstants: Set<String> = stepIds.map { step -> "PHASE_${step.uppercase()}" }.toSet()
    val idsMembers: Set<String> = stepIds.map { step -> step.uppercase() }.toSet()
    val owners: List<ConstantOwner> =
      listOf(
        ConstantOwner(DEFINITION, "PHASE_[A-Z0-9_]+", phaseConstants),
        ConstantOwner(PHASE_IDS, "[A-Z][A-Z0-9_]*", idsMembers),
      )
  }

  private class ConstantOwner(
    val name: String,
    val memberPattern: String,
    val bareMembers: Set<String>,
  )

  private data class StepHit(
    val offset: Int,
    val form: String,
    val token: String,
  )

  private data class StepAlias(
    val path: String,
    val line: Int,
    val name: String,
  )
}

internal object FeatureTaskLaunchPortScan {
  private const val LAUNCHER = "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher"
  private const val LAUNCHER_PACKAGE_STAR = "skillbill.ports.goalrunner.runner.*"

  private val PHASE_RUNNER_IMPLEMENTATION =
    Regex("""\b(?:class|object)\s+\w+(?:\s*\([^{]*?\))?\s*:\s*(?:(?!\bfun\b)[^{}=])*?(?<![\w.])PhaseRunner\b""")

  fun violations(sources: Map<String, String>): List<String> =
    sources.flatMap { (path, source) ->
      val scanned = ScannedKotlinSource(source)
      if (PHASE_RUNNER_IMPLEMENTATION.containsMatchIn(scanned.blanked)) {
        emptyList()
      } else {
        launcherReferences(path, scanned)
      }
    }

  private fun launcherReferences(
    path: String,
    scanned: ScannedKotlinSource,
  ): List<String> {
    val imports =
      scanned.imports.filter { target -> target == LAUNCHER_PACKAGE_STAR || refersToLauncher(target) }
        .map { target -> "$path imports $target" }
    val qualified = scanned.qualifiedReferences.filter(::refersToLauncher).map { fqn -> "$path references $fqn" }
    return imports + qualified
  }

  private fun refersToLauncher(fqn: String): Boolean = fqn == LAUNCHER || fqn.startsWith("$LAUNCHER.")
}

internal object FeatureTaskDependencyDirectionScan {
  private const val RUN_LOOP_PREFIX = "skillbill.engine.featuretask.runloop."
  private const val RUN_STATE = "FeatureTaskRuntimeRunState"

  private val RUN_STATE_REFERENCE = Regex("""\b$RUN_STATE\b""")
  private val SHARED_SLOT_PACKAGES = setOf("attempt", "runner")
  private val STRATEGY_PACKAGE_REFERENCE = Regex("""^skillbill\.engine\.featuretask\.slot\.([a-z]\w*)(?:\.|$)""")
  private val RUN_LOOP_DRIVER =
    Regex(
      """(?:FeatureTaskRuntimeRunLoop(?:Drive|Launch|Attempt|PlanningBranch)""" +
        """|FeatureTaskRuntimePlanningBranch)\w*""",
    )

  fun violations(sources: Map<String, String>): List<String> =
    sources.flatMap { (path, source) ->
      val scanned = ScannedKotlinSource(source)
      when (packageRole(path)) {
        PackageRole.SHARED -> strategyPackageReferences(path, scanned)
        PackageRole.STRATEGY -> runLoopDriverReferences(path, scanned) + runStateReferences(path, scanned)
        PackageRole.SLOT_MACHINERY -> emptyList()
      }
    }

  fun isStrategyPath(path: String): Boolean = packageRole(path) == PackageRole.STRATEGY

  private fun packageRole(path: String): PackageRole {
    val segments = path.split('/')
    return when {
      !isFeatureTaskSlotPath(path) -> PackageRole.SHARED
      segments.size <= 2 || segments[1] in SHARED_SLOT_PACKAGES -> PackageRole.SLOT_MACHINERY
      else -> PackageRole.STRATEGY
    }
  }

  private fun strategyPackageReferences(
    path: String,
    scanned: ScannedKotlinSource,
  ): List<String> =
    scanned.imports.filter(::isStrategyPackageReference).map { target -> "$path imports $target" } +
      scanned.qualifiedReferences.filter(::isStrategyPackageReference).map { fqn -> "$path references $fqn" }

  private fun isStrategyPackageReference(fqn: String): Boolean =
    STRATEGY_PACKAGE_REFERENCE.find(fqn)?.groupValues?.get(1)?.let { name -> name !in SHARED_SLOT_PACKAGES } == true

  private fun runLoopDriverReferences(
    path: String,
    scanned: ScannedKotlinSource,
  ): List<String> =
    scanned.imports.filter(::isRunLoopDriverImport).map { target -> "$path imports $target" } +
      scanned.qualifiedReferences.filter(::isRunLoopDriverReference).map { fqn -> "$path references $fqn" }

  private fun isRunLoopDriverImport(target: String): Boolean =
    target.startsWith(RUN_LOOP_PREFIX) && (target.endsWith(".*") || isRunLoopDriverReference(target))

  private fun isRunLoopDriverReference(fqn: String): Boolean =
    fqn.startsWith(RUN_LOOP_PREFIX) && fqn.split('.').any(RUN_LOOP_DRIVER::matches)

  private fun runStateReferences(
    path: String,
    scanned: ScannedKotlinSource,
  ): List<String> =
    if (RUN_STATE_REFERENCE.containsMatchIn(scanned.blanked)) listOf("$path references $RUN_STATE") else emptyList()

  private enum class PackageRole { SHARED, SLOT_MACHINERY, STRATEGY }
}

private class ScannedKotlinSource(source: String) {
  val code: String = CommentStripper(source).strip()
  val blanked: String = CommentStripper(source, blankStringLiterals = true).strip()
  val body: String =
    blanked.split('\n').joinToString("\n") { line ->
      if (IMPORT_OR_PACKAGE_LINE.matches(line)) " ".repeat(line.length) else line
    }
  val imports: List<String>
    get() = IMPORT_PATTERN.findAll(blanked).map { match -> match.groupValues[1] }.toList()
  val qualifiedReferences: List<String>
    get() = QUALIFIED_REFERENCE.findAll(body).map { match -> match.value }.toList()

  fun lineOf(offset: Int): Int = blanked.substring(0, offset).count { character -> character == '\n' } + 1

  private companion object {
    val IMPORT_PATTERN = Regex("""^\s*import\s+(\w+(?:\.\w+)*(?:\.\*)?)""", RegexOption.MULTILINE)
    val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(?:import|package)\s+.*""")
    val QUALIFIED_REFERENCE = Regex("""\bskillbill(?:\.\w+)+""")
  }
}

private class DeclarationScopeTracker {
  private val typeScopes = ArrayDeque<Boolean>()
  private var parenDepth = 0
  private var pendingTypeBody = false

  fun atMemberLevel(): Boolean = parenDepth == 0 && typeScopes.lastOrNull() != false

  fun consume(line: String) {
    if (parenDepth == 0) updatePendingTypeBody(line)
    line.forEach(::consumeCharacter)
  }

  private fun updatePendingTypeBody(line: String) {
    val functionHeader = FUNCTION_HEADER.containsMatchIn(line)
    when {
      TYPE_HEADER.containsMatchIn(line) && !functionHeader -> pendingTypeBody = true
      functionHeader || MEMBER_HEADER.containsMatchIn(line) -> pendingTypeBody = false
    }
  }

  private fun consumeCharacter(character: Char) {
    when (character) {
      '(' -> parenDepth += 1
      ')' -> parenDepth -= 1
      '{' -> openScope()
      '}' -> typeScopes.removeLastOrNull()
    }
  }

  private fun openScope() {
    val typeBody = pendingTypeBody && parenDepth == 0
    typeScopes.addLast(typeBody)
    if (parenDepth == 0) pendingTypeBody = false
  }

  private companion object {
    val TYPE_HEADER = Regex("""(?<![:\w])(?:class|object|interface)\b""")
    val FUNCTION_HEADER = Regex("""\bfun\b""")
    val MEMBER_HEADER = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:[a-z]+\s+)*(?:va[lr]|init)\b""")
  }
}
