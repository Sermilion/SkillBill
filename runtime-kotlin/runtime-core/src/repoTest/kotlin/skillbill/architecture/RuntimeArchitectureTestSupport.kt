package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal val runtimeArchitectureRoot: Path = ArchitectureScanSupport.runtimeRoot

internal val runtimeArchitectureSourceRoots: List<Path> =
  RuntimeModuleCatalog.declaredGradleModules
    .filter { moduleName -> moduleName != "runtime-infra" }
    .map { moduleName ->
      runtimeArchitectureRoot.resolve(
        "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName)}/src/main/kotlin",
      )
    }

internal fun kotlinFilesUnderWithArchitectureAsserts(root: Path): List<Path> {
  val files = ArchitectureScanSupport.kotlinFilesUnder(root)
  assertArchitectureScanRootContributedFiles(root, files)
  return files
}

internal fun rawMapViolationsUnder(root: Path): List<String> =
  kotlinFilesUnderWithArchitectureAsserts(root)
    .flatMap { path -> findRawMapViolations(sourceFile(path)) }

internal fun engineInboundApiViolations(
  consumerSourceRoots: List<String>,
  allowedTypes: Set<String>,
): List<String> {
  val violations = mutableListOf<String>()
  consumerSourceRoots.forEach { relativeRoot ->
    val root = runtimeArchitectureRoot.resolve(relativeRoot)
    val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(root)
    sourceFiles.forEach { path ->
      val relativePath = architecturePathLabel(path)
      val source = Files.readString(path)
      source.lineSequence()
        .flatMap { line ->
          Regex("""skillbill\.engine\.[A-Za-z0-9_.]+""")
            .findAll(line)
            .map { it.value }
            .mapNotNull { reference ->
              engineInboundApiViolationMessage(relativePath, reference, allowedTypes)
            }
        }
        .forEach { violation -> violations += violation }
    }
  }
  return violations.distinct().sorted()
}

internal fun engineInboundApiViolationMessage(
  relativePath: String,
  referencedType: String,
  allowedTypes: Set<String>,
): String? =
  if (referencedType in allowedTypes) {
    null
  } else {
    "$relativePath references unpinned engine type $referencedType"
  }

internal fun mainPackageRootsForModule(moduleName: String): Set<String> {
  val root = moduleMainKotlinRoot(moduleName)
  return kotlinFilesUnderWithArchitectureAsserts(root)
    .map { path ->
      val source = Files.readString(path)
      RuntimeArchitectureScanConstants.packagePattern.find(source)?.groupValues?.get(1).orEmpty()
    }
    .filter(String::isNotBlank)
    .map(::mainPackageRootForPackageName)
    .toSet()
}

internal fun mainPackageRootForPackageName(packageName: String): String {
  val segments = packageName.split('.')
  return when {
    segments.size >= 3 && segments[0] == "skillbill" && segments[1] == "infrastructure" ->
      segments.take(3).joinToString(".")
    segments.size >= 2 && segments[0] == "skillbill" ->
      segments.take(2).joinToString(".")
    else -> packageName
  }
}

internal fun subsystemPackageRootViolationMessage(
  moduleName: String,
  actualRoots: Set<String>,
  expectedRoot: String,
): String? =
  when {
    actualRoots.isEmpty() -> "$moduleName has no main-source package root"
    actualRoots.size > 1 ->
      "$moduleName has multiple main-source roots: ${actualRoots.sorted()}"
    actualRoots.single() != expectedRoot ->
      "$moduleName root ${actualRoots.single()} does not match expected $expectedRoot"
    else -> null
  }

internal const val MCP_SCAFFOLD_RUNTIME_PATH =
  "runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/scaffold/McpScaffoldRuntime.kt"

internal fun moduleMainKotlinRoot(moduleName: String): Path =
  runtimeArchitectureRoot.resolve(
    "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName)}/src/main/kotlin",
  )

internal fun moduleMainKotlinRootRelative(moduleName: String): String =
  "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName)}/src/main/kotlin"

internal fun assertArchitectureScanRootContributedFiles(
  root: Path,
  files: List<Path>,
) {
  assertTrue(
    Files.isDirectory(root),
    "Missing architecture scan root: ${runtimeArchitectureRoot.relativize(root)}",
  )
  assertTrue(
    files.isNotEmpty(),
    "Architecture scan root must contribute at least one .kt file: " +
      runtimeArchitectureRoot.relativize(root),
  )
  files.forEach { file ->
    assertTrue(
      file.normalize().startsWith(root.normalize()),
      "Architecture scan read $file outside root $root",
    )
  }
}

internal fun syntheticSourceFile(
  relativePath: String,
  source: String,
): SourceFile =
  SourceFile(
    relativePath = relativePath,
    packageName =
      RuntimeArchitectureScanConstants.packagePattern.find(source)?.groupValues?.get(1)
        ?: relativePath
          .substringBeforeLast('/')
          .removePrefix("src/main/kotlin/")
          .replace('/', '.')
          .let { path -> if (path.startsWith("skillbill.")) path else "skillbill.$path" },
    imports =
      RuntimeArchitectureScanConstants.importPattern.findAll(source)
        .map { it.groupValues[1].substringBefore(" as ") }
        .toList(),
    source = source,
  )

internal fun installPortFunctionSignatures(sourceFile: SourceFile): List<InstallPortFunctionSignature> {
  val lines = sourceFile.source.lines()
  return lines.mapIndexedNotNull { index, line ->
    val match =
      RuntimeArchitectureScanConstants.portFunctionStartPattern.find(line.trim())
        ?: return@mapIndexedNotNull null
    val signatureText = collectFunctionSignature(lines, index)
    val parsed = RuntimeArchitectureScanConstants.portFunctionSignaturePattern.find(signatureText)
    val functionName = match.groupValues[1]
    val parameters = parsed?.groupValues?.get(2).orEmpty().trim()
    val returnType = parsed?.groupValues?.get(3).orEmpty()
    val parameterTypes =
      parameters.split(",")
        .map(String::trim)
        .filter(String::isNotBlank)
        .map { parameter -> parameter.substringAfter(":").trim().substringAfterLast(".") }
    InstallPortFunctionSignature(
      sourcePath = sourceFile.relativePath,
      functionName = functionName,
      parameters = parameters,
      returnType = returnType,
      hasSingleRequestParameter = parameterTypes.size == 1 && parameterTypes.single().endsWith("Request"),
      hasResultReturn = returnType.substringBefore("<").substringAfterLast(".").endsWith("Result"),
    )
  }
}

internal fun collectFunctionSignature(
  lines: List<String>,
  startIndex: Int,
): String {
  val signature = StringBuilder()
  var openParens = 0
  var sawParen = false
  var index = startIndex
  var shouldStop = false
  while (index < lines.size && !shouldStop) {
    val current = lines[index]
    signature.append(current.trim()).append(' ')
    current.forEach { char ->
      when (char) {
        '(' -> {
          openParens += 1
          sawParen = true
        }
        ')' -> openParens -= 1
      }
    }
    if (sawParen && openParens == 0) {
      val text = signature.toString()
      shouldStop = hasFunctionSignatureTerminator(text)
      val nextLine = lines.getOrNull(index + 1)?.trim().orEmpty()
      if (!nextLine.startsWith(":")) shouldStop = true
    }
    index += 1
  }
  return signature.toString()
}

internal fun hasFunctionSignatureTerminator(text: String): Boolean =
  containsReturnTypeSeparator(text) || " =" in text || text.trim().endsWith("{")

internal fun containsReturnTypeSeparator(text: String): Boolean = "):" in text || ") :" in text

internal fun rawMapViolationFixtureSource(): String =
  """

  typealias AnyMapAlias = Map<String, Any>
  typealias HashMapAlias = HashMap<String, Any?>

  open class PublicBase {
    open fun overrideMap(): Map<String, Any?> = emptyMap()
  }

  class PublicDerived : PublicBase() {
    override fun overrideMap(): Map<String, Any?> = emptyMap()
  }

  class PublicPayload {
    fun payloadMap(): Map<String, Any?> = emptyMap()
  }

  class Fake {
    public fun foo(): Map<String, Any?> = emptyMap()

    public fun nonNullMap(): Map<String, Any> = emptyMap()

    fun bar(): Map<String, *> = emptyMap<String, Any?>()

    fun baz(input: MutableMap<String, Any?>) { input.clear() }

    fun mutableNonNull(input: MutableMap<String, Any>) { input.clear() }

    fun mutableStar(input: MutableMap<String, *>) {}

    fun hashMap(input: HashMap<String, Any?>) { input.clear() }

    fun hashMapNonNull(input: HashMap<String, Any>) { input.clear() }

    fun hashMapStar(input: HashMap<String, *>) {}

    fun linkedHashMap(input: LinkedHashMap<String, Any?>) { input.clear() }

    fun linkedHashMapNonNull(input: LinkedHashMap<String, Any>) { input.clear() }

    fun linkedHashMapStar(input: LinkedHashMap<String, *>) {}

    fun aliasMap(input: AnyMapAlias) {}

    fun aliasHashMap(): HashMapAlias = hashMapOf()

    fun multiLine(
      first: String,
    ): Map<String, Any?> = emptyMap()
  }
  """.trimIndent()

internal fun expectedRawMapViolationFixtureNames(): List<String> =
  listOf(
    "aliasHashMap",
    "aliasMap",
    "AnyMapAlias",
    "bar",
    "baz",
    "foo",
    "hashMap",
    "hashMapNonNull",
    "hashMapStar",
    "linkedHashMap",
    "linkedHashMapNonNull",
    "linkedHashMapStar",
    "multiLine",
    "mutableNonNull",
    "mutableStar",
    "nonNullMap",
    "overrideMap",
    "overrideMap",
    "payloadMap",
    "HashMapAlias",
  ).sorted()

private val rawMapBannedShapes =
  listOf(
    "Map<String, Any?>",
    "Map<String, Any>",
    "Map<String, *>",
    "HashMap<String, Any?>",
    "HashMap<String, Any>",
    "HashMap<String, *>",
    "LinkedHashMap<String, Any?>",
    "LinkedHashMap<String, Any>",
    "LinkedHashMap<String, *>",
    "MutableMap<String, Any?>",
    "MutableMap<String, Any>",
    "MutableMap<String, *>",
  )

private val rawMapFunDeclPattern =
  Regex(
    """^(?:(?:public|private|protected|internal|override|open|final|abstract|suspend|""" +
      """inline|operator|infix|tailrec|external|expect|actual)\s+)*""" +
      """fun\s+(?:<[^>]+>\s+)?(?:[A-Za-z0-9_]+\.)?([A-Za-z0-9_]+)\s*\(""",
  )

private val rawMapValDeclPattern =
  Regex(
    """^(?:(?:public|private|protected|internal|override|open|final|abstract|""" +
      """const|lateinit|expect|actual)\s+)*(?:val|var)\s+([A-Za-z0-9_]+)\s*:""",
  )

private val rawMapTypeAliasDeclPattern =
  Regex(
    """^(?:(?:public|private|protected|internal)\s+)*typealias\s+([A-Za-z0-9_]+)\s*=\s*(.+)$""",
  )

private val rawMapDelegatingClassDeclPattern =
  Regex(
    """^(?:(?:public|private|protected|internal|abstract|open|sealed|data)\s+)*""" +
      """class\s+([A-Za-z0-9_]+)\b""",
  )

private fun rawMapDeclarationName(trimmed: String): String? {
  val funMatch = rawMapFunDeclPattern.find(trimmed)
  val valMatch = rawMapValDeclPattern.find(trimmed)
  return funMatch?.groupValues?.get(1) ?: valMatch?.groupValues?.get(1)
}

private fun collectRawMapDeclarationSignature(
  lines: List<String>,
  startIndex: Int,
  hasValDecl: Boolean,
): String {
  val signature = StringBuilder()
  var index = startIndex
  var openParens = 0
  var sawParen = false
  var awaitingReturn = false
  while (index < lines.size && index - startIndex <= 30) {
    val current = lines[index]
    signature.append(current).append('\n')
    current.forEach { ch ->
      when (ch) {
        '(' -> {
          openParens += 1
          sawParen = true
        }
        ')' -> openParens -= 1
      }
    }
    val closed = sawParen && openParens == 0
    val stop = rawMapSignatureComplete(current, closed, awaitingReturn, sawParen, hasValDecl)
    if (stop) break
    if (closed) awaitingReturn = true
    index += 1
  }
  return signature.toString()
}

private fun rawMapSignatureComplete(
  current: String,
  closed: Boolean,
  awaitingReturn: Boolean,
  sawParen: Boolean,
  hasValDecl: Boolean,
): Boolean {
  if (closed) {
    val containsReturnMarker =
      current.contains("):") || current.contains(") :") ||
        current.endsWith(":") || current.contains(" {") || current.endsWith("{") ||
        current.contains(" =") || current.endsWith("= ") || current.endsWith(") = null")
    if (containsReturnMarker || awaitingReturn) return true
  }
  return !sawParen && hasValDecl && current.contains(": ")
}

private fun signatureUsesBannedRawMap(
  sigText: String,
  bannedShapes: List<String>,
  bannedTypeAliases: Set<String>,
): Boolean =
  bannedShapes.any { shape -> shape in sigText } ||
    bannedTypeAliases.any { alias -> Regex("""\b${Regex.escape(alias)}\b""").containsMatchIn(sigText) }

private data class RawMapDeclarationContext(
  val trimmed: String,
  val tracker: ScopeTracker,
  val source: String,
  val relativePath: String,
)

private fun isBoundaryCarrierRawMapDeclaration(context: RawMapDeclarationContext): Boolean {
  if (
    rawMapDeclarationModifiers(context.trimmed)
      .any { modifier -> modifier in setOf("private", "protected", "internal") } ||
    context.tracker.insideNonPublicScope
  ) {
    return true
  }
  if (context.relativePath.replace('\\', '/').contains("runtime-ports/src/main/kotlin/")) {
    return false
  }
  val enclosingName = context.tracker.enclosingStack.lastOrNull().orEmpty()
  val namedCarrier =
    enclosingName.endsWith("Map") ||
      enclosingName.endsWith("Payload") ||
      enclosingName.endsWith("Artifacts") ||
      enclosingName.endsWith("Document") ||
      enclosingName.endsWith("Arguments") ||
      enclosingName.endsWith("Patch") ||
      enclosingName == "WorkflowStepUpdates" ||
      enclosingName == "DurableWorkflowArtifacts"
  if (!namedCarrier) return false
  return Regex(
    """(?s)(?:class|data\s+class)\s+$enclosingName\b[^{}]*?\bprivate\s+(?:val|var)\s+\w+\s*:\s*""" +
      """(?:List<)?(?:Map|MutableMap|HashMap|LinkedHashMap)<String""",
  ).containsMatchIn(context.source)
}

private fun rawMapDeclarationModifiers(trimmed: String): Set<String> {
  val keyword = Regex("""\b(?:fun|val|var|typealias)\b""").find(trimmed) ?: return emptySet()
  return trimmed.substring(0, keyword.range.first)
    .trim()
    .split(Regex("""\s+"""))
    .filter(String::isNotBlank)
    .toSet()
}

internal fun findRawMapViolations(file: SourceFile): List<String> {
  val lines = file.source.lines()
  val bannedTypeAliases = rawMapTypeAliases(file.source, rawMapBannedShapes)
  val violations = mutableListOf<String>()
  val tracker = ScopeTracker()
  lines.forEachIndexed { index, line ->
    tracker.consume(line)
    rawMapViolationForLine(file, lines, index, tracker, bannedTypeAliases)?.let(violations::add)
  }
  return violations
}

private val rawMapBoundaryAccessors =
  setOf(
    "skillbill.workflow.engine.model.DurableWorkflowArtifactFamily.contains",
    "skillbill.workflow.engine.model.DurableWorkflowArtifactFamily.value",
    "skillbill.workflow.engine.model.DurableWorkflowArtifactFamily.putInto",
    "skillbill.workflow.engine.model.DurableWorkflowArtifactFamily.removeFrom",
  )

private fun rawMapViolationForLine(
  file: SourceFile,
  lines: List<String>,
  index: Int,
  tracker: ScopeTracker,
  bannedTypeAliases: Set<String>,
): String? {
  val trimmed = lines[index].trim()
  val directViolation =
    rawMapDelegatingClassViolation(trimmed, index, tracker, file)
      ?: rawMapTypeAliasViolation(trimmed, index, tracker, file, bannedTypeAliases)
  if (directViolation != null) return directViolation
  val declName = rawMapDeclarationName(trimmed) ?: return null
  val signature =
    collectRawMapDeclarationSignature(
      lines,
      index,
      rawMapValDeclPattern.find(trimmed) != null,
    )
  val context = RawMapDeclarationContext(trimmed, tracker, file.source, file.relativePath)
  val enclosingPrefix = tracker.enclosingStack.joinToString(".").let { if (it.isEmpty()) "" else "$it." }
  val fqn =
    listOf(file.packageName, "$enclosingPrefix$declName")
      .filter(String::isNotBlank)
      .joinToString(".")
  return when {
    signatureDeclaresExactAny(signature) && !isNonPublicDeclaration(context) ->
      "${file.relativePath}:${index + 1} public `$declName` declares type exactly Any (fqn=$fqn)"
    !signatureUsesBannedRawMap(signature, rawMapBannedShapes, bannedTypeAliases) -> null
    fqn in rawMapBoundaryAccessors -> null
    isBoundaryCarrierRawMapDeclaration(context) -> null
    else -> "${file.relativePath}:${index + 1} public `$declName` exposes raw map shape (fqn=$fqn)"
  }
}

private val exactAnyDeclarationPattern =
  Regex("""\)\s*:\s*Any(?![\w?<.])|\b(?:val|var)\s+\w+\s*:\s*Any(?![\w?<.])""")

private fun signatureDeclaresExactAny(signature: String): Boolean =
  exactAnyDeclarationPattern.containsMatchIn(signature)

private fun isNonPublicDeclaration(context: RawMapDeclarationContext): Boolean =
  rawMapDeclarationModifiers(context.trimmed).any { it in setOf("private", "protected", "internal") } ||
    context.tracker.insideNonPublicScope

private fun rawMapDelegatingClassViolation(
  trimmed: String,
  index: Int,
  tracker: ScopeTracker,
  file: SourceFile,
): String? {
  val match = rawMapDelegatingClassDeclPattern.find(trimmed) ?: return null
  if (!isPublicRawMapDelegation(trimmed, tracker)) return null
  return "${file.relativePath}:${index + 1}: public `${match.groupValues[1]}` exposes raw map shape"
}

private fun isPublicRawMapDelegation(
  trimmed: String,
  tracker: ScopeTracker,
): Boolean {
  if (tracker.insideNonPublicScope) return false
  if (rawMapDeclarationModifiers(trimmed).any { it in setOf("private", "protected", "internal") }) return false
  if (trimmed.startsWith("private ") || trimmed.startsWith("protected ") || trimmed.startsWith("internal ")) {
    return false
  }
  return rawMapBannedShapes.any { shape -> "$shape by" in trimmed }
}

private fun rawMapTypeAliasViolation(
  trimmed: String,
  index: Int,
  tracker: ScopeTracker,
  file: SourceFile,
  bannedTypeAliases: Set<String>,
): String? {
  val match = rawMapTypeAliasDeclPattern.find(trimmed) ?: return null
  if (match.groupValues[1] !in bannedTypeAliases) return null
  val context = RawMapDeclarationContext(trimmed, tracker, file.source, file.relativePath)
  if (isBoundaryCarrierRawMapDeclaration(context)) return null
  return "${file.relativePath}:${index + 1} " +
    "public `${match.groupValues[1]}` exposes raw map shape"
}

internal fun rawMapTypeAliases(
  source: String,
  bannedShapes: List<String>,
): Set<String> {
  val directAliases = mutableMapOf<String, String>()
  val aliasPattern =
    Regex(
      """^(?:(?:public|private|protected|internal)\s+)*typealias\s+([A-Za-z0-9_]+)\s*=\s*(.+)$""",
      RegexOption.MULTILINE,
    )
  aliasPattern.findAll(source).forEach { match ->
    directAliases[match.groupValues[1]] = match.groupValues[2].trim()
  }
  val bannedAliases = mutableSetOf<String>()
  var changed = true
  while (changed) {
    changed = false
    directAliases.forEach { (alias, target) ->
      if (alias !in bannedAliases && (bannedShapes.any { shape -> shape in target } || target in bannedAliases)) {
        bannedAliases += alias
        changed = true
      }
    }
  }
  return bannedAliases
}

internal class ScopeTracker {
  val enclosingStack: ArrayDeque<String> = ArrayDeque()
  val scopeNonPublic: ArrayDeque<Boolean> = ArrayDeque()
  private val scopeKind: ArrayDeque<Kind> = ArrayDeque()

  private val scopeDepth: ArrayDeque<Int> = ArrayDeque()
  private var braceDepth = 0
  private var parenDepth = 0
  private var pendingScopeName: String? = null
  private var pendingScopeIsData = false
  private var pendingScopeNonPublic = false

  enum class Kind { BRACE, PAREN }

  val insideNonPublicScope: Boolean get() = scopeNonPublic.any { it }

  private var resumeClassName: String? = null
  private var resumeClassNonPublic = false

  fun consume(lineText: String) {
    noteScopeDeclaration(lineText)
    lineText.forEach { ch ->
      when (ch) {
        '{' -> onOpenBrace()
        '}' -> onCloseBrace()
        '(' -> onOpenParen()
        ')' -> onCloseParen()
      }
    }
  }

  internal fun noteScopeDeclaration(lineText: String) {
    val scopeMatch = RuntimeArchitectureScanConstants.scopeDeclarationPattern.find(lineText) ?: return
    pendingScopeName = scopeMatch.groupValues[1]
    pendingScopeIsData = lineText.contains(Regex("""\bdata\s+class\b"""))
    pendingScopeNonPublic = Regex("""^\s*(?:private|internal)\s+""").containsMatchIn(lineText)
    resumeClassName = null
    resumeClassNonPublic = false
  }

  internal fun onOpenBrace() {
    val pendingName = pendingScopeName
    val resumeName = resumeClassName
    when {
      pendingName != null -> {
        pushScope(pendingName, Kind.BRACE, braceDepth, pendingScopeNonPublic)
        pendingScopeName = null
        pendingScopeIsData = false
        pendingScopeNonPublic = false
      }
      resumeName != null && parenDepth == 0 -> {
        pushScope(resumeName, Kind.BRACE, braceDepth, resumeClassNonPublic)
        resumeClassName = null
        resumeClassNonPublic = false
      }
    }
    braceDepth += 1
  }

  internal fun onCloseBrace() {
    braceDepth -= 1
    popScopesWhile(Kind.BRACE) { braceDepth <= it }
  }

  internal fun onOpenParen() {
    val pendingName = pendingScopeName
    if (pendingName != null && pendingScopeIsData) {
      pushScope(pendingName, Kind.PAREN, parenDepth, pendingScopeNonPublic)
      resumeClassName = pendingName
      resumeClassNonPublic = pendingScopeNonPublic
      pendingScopeName = null
      pendingScopeIsData = false
      pendingScopeNonPublic = false
    }
    parenDepth += 1
  }

  internal fun onCloseParen() {
    parenDepth -= 1
    popScopesWhile(Kind.PAREN) { parenDepth <= it }
  }

  internal fun pushScope(
    name: String,
    kind: Kind,
    depth: Int,
    nonPublic: Boolean,
  ) {
    enclosingStack.addLast(name)
    scopeKind.addLast(kind)
    scopeDepth.addLast(depth)
    scopeNonPublic.addLast(nonPublic)
  }

  private inline fun popScopesWhile(
    kind: Kind,
    condition: (Int) -> Boolean,
  ) {
    while (scopeKind.isNotEmpty() && scopeKind.last() == kind && condition(scopeDepth.last())) {
      scopeKind.removeLast()
      scopeDepth.removeLast()
      enclosingStack.removeLast()
      scopeNonPublic.removeLast()
    }
  }
}

internal fun assertNoBannedImports(
  files: List<SourceFile>,
  bannedImports: List<String>,
) {
  val violations =
    files.flatMap { file ->
      file.imports
        .filter { importedName -> bannedImports.any(importedName::startsWith) }
        .map { importedName -> "${file.relativePath} imports $importedName" }
    }
  assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
}

internal fun assertNoBannedSourceReferences(
  files: List<SourceFile>,
  bannedReferences: List<String>,
  description: String,
) {
  val violations =
    files.flatMap { file ->
      file.source.lines().flatMapIndexed { index, line ->
        bannedReferences
          .filter { reference -> line.containsBannedReference(reference) }
          .map { reference ->
            "${file.relativePath}:${index + 1} contains $description $reference"
          }
      }
    }
  assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
}

internal fun String.containsBannedReference(reference: String): Boolean =
  if (reference == "Files.") {
    Regex("""\bFiles\.""").containsMatchIn(this)
  } else {
    reference in this
  }

internal fun assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery(mcpFiles: List<SourceFile>) {
  val scaffoldFile =
    mcpFiles.first { file ->
      file.relativePath == MCP_SCAFFOLD_RUNTIME_PATH
    }
  val filesReferenceLines =
    scaffoldFile.source.lines()
      .filter { line -> "java.nio.file.Files" in line || "Files." in line }
      .map(String::trim)

  assertEquals(emptyList(), filesReferenceLines)
}

internal fun sourceFiles(): List<SourceFile> =
  runtimeArchitectureSourceRoots.flatMap { sourceRoot ->
    sourceFilesIn(sourceRoot)
  }

internal fun declaredMainSourceFiles(): List<SourceFile> =
  RuntimeModuleCatalog.declaredGradleModules
    .flatMap { moduleName -> mainSourceRoots(moduleName) }
    .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

internal fun innerLayerTestSourceFiles(): List<SourceFile> =
  listOf("runtime-application", "runtime-domain", "runtime-ports")
    .flatMap { moduleName ->
      listOf("src/test/kotlin", "src/repoTest/kotlin", "src/jvmTest/kotlin", "src/commonTest/kotlin")
        .map { sourceSet ->
          runtimeArchitectureRoot
            .resolve(RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName))
            .resolve(sourceSet)
        }
        .filter(Files::isDirectory)
    }
    .flatMap { sourceRoot -> sourceFilesIn(sourceRoot) }

internal fun mainSourceRoots(moduleName: String): List<Path> {
  if (moduleName == "runtime-infra") return emptyList()
  val sourceRoot =
    runtimeArchitectureRoot
      .resolve(RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName))
      .resolve("src")
  if (!Files.isDirectory(sourceRoot)) {
    error(
      "Missing module source root: ${runtimeArchitectureRoot.relativize(sourceRoot)}",
    )
  }
  val roots =
    Files.list(sourceRoot).use { stream ->
      stream
        .filter(Files::isDirectory)
        .filter { path -> path.fileName.toString() == "main" || path.fileName.toString().endsWith("Main") }
        .map { path -> path.resolve("kotlin") }
        .filter(Files::isDirectory)
        .toList()
        .sorted()
    }
  if (roots.isEmpty()) {
    error("Missing module main Kotlin source root: ${runtimeArchitectureRoot.relativize(sourceRoot)}")
  }
  roots.forEach(::kotlinFilesUnderWithArchitectureAsserts)
  return roots
}

internal fun sourceFilesIn(sourceRoot: Path): List<SourceFile> =
  kotlinFilesUnderWithArchitectureAsserts(sourceRoot).map(::sourceFile)

internal fun sourceFile(path: Path): SourceFile {
  val source = Files.readString(path)
  return SourceFile(
    relativePath = architecturePathLabel(path),
    packageName = RuntimeArchitectureScanConstants.packagePattern.find(source)?.groupValues?.get(1).orEmpty(),
    imports =
      RuntimeArchitectureScanConstants.importPattern.findAll(source)
        .map { it.groupValues[1].substringBefore(" as ") }
        .toList(),
    source = source,
  )
}

internal fun architecturePathLabel(path: Path): String {
  val normalized = path.toAbsolutePath().normalize()
  return if (normalized.startsWith(runtimeArchitectureRoot)) {
    runtimeArchitectureRoot.relativize(normalized).toString().replace('\\', '/')
  } else {
    normalized.toString().replace('\\', '/')
  }
}

internal fun sourcePath(relativePath: String): Path =
  runtimeArchitectureSourceRoots
    .map { sourceRoot -> sourceRoot.resolve(relativePath) }
    .firstOrNull(Files::exists)
    ?: error("Missing source file: $relativePath")

internal data class SourceFile(
  val relativePath: String,
  val packageName: String,
  val imports: List<String>,
  val source: String,
)

internal data class InstallPortFunctionSignature(
  val sourcePath: String,
  val functionName: String,
  val parameters: String,
  val returnType: String,
  val hasSingleRequestParameter: Boolean,
  val hasResultReturn: Boolean,
) {
  fun render(): String = "$sourcePath::$functionName($parameters): ${returnType.ifBlank { "<missing>" }}"
}

internal object RuntimeArchitectureScanConstants {
  val contractsForbiddenImports: List<String> =
    listOf(
      "com.networknt.",
      "com.fasterxml.jackson.",
      "java.nio.file.Files",
      "org.yaml.",
      "java.io.",
    )
  val contractsForbiddenSourceReferences: List<String> =
    listOf(
      "com.networknt.",
      "com.fasterxml.jackson.",
      "java.nio.file.Files",
      "Files.",
      "org.yaml.",
      "java.io.",
      "getResourceAsStream",
    )
  val directFileIoImports: List<String> =
    listOf(
      "java.io.File",
      "java.nio.file.Files",
      "kotlin.io.path",
      "kotlin.io.path.readText",
      "kotlin.io.path.writeText",
      "kotlin.io.path.inputStream",
      "kotlin.io.path.outputStream",
      "kotlin.io.path.bufferedReader",
      "kotlin.io.path.bufferedWriter",
    )
  val directFileIoSourceReferences: List<String> =
    listOf(
      "java.io.File",
      "java.nio.file.Files",
      "Files.",
      ".toFile()",
      ".readText()",
      ".writeText()",
      ".inputStream()",
      ".outputStream()",
      ".bufferedReader()",
      ".bufferedWriter()",
      "kotlin.io.path.readText",
      "kotlin.io.path.writeText",
      "kotlin.io.path.inputStream",
      "kotlin.io.path.outputStream",
      "kotlin.io.path.bufferedReader",
      "kotlin.io.path.bufferedWriter",
    )
  val processAccessSourceReferences: List<String> =
    listOf(
      "System.getenv",
      "System.getProperty",
    )
  val boundaryFrameworkImports: List<String> =
    listOf(
      "com.github.ajalt.clikt",
      "com.zaxxer.hikari",
      "io.ktor.client",
      "java.net.HttpURLConnection",
      "java.net.URL",
      "java.net.URLConnection",
      "java.net.http",
      "java.sql",
      "javax.sql",
      "okhttp3",
      "org.http4k",
      "org.jooq",
      "org.sqlite",
      "retrofit2",
    )
  val boundaryFrameworkSourceReferences: List<String> =
    listOf(
      "com.github.ajalt.clikt",
      "com.zaxxer.hikari",
      "HttpURLConnection",
      "io.ktor.client",
      "java.net.HttpURLConnection",
      "java.net.URL",
      "java.net.URLConnection",
      "java.net.http",
      "java.sql",
      "javax.sql",
      "okhttp3",
      "org.http4k",
      "org.jooq",
      "org.sqlite",
      "retrofit2",
    )
  val homeExpansionSourceReferences: List<String> =
    listOf(
      "== \"~\"",
      ".startsWith(\"~/\")",
      ".removePrefix(\"~/\")",
    )

  val domainEffectPuritySourceReferences: List<String> =
    listOf(
      "UUID.randomUUID",
      "LocalDate.now",
      "Instant.now",
      "System.currentTimeMillis",
      "System.nanoTime",
      "Clock.system",
      "java.util.logging",
    )
  val packagePattern: Regex = Regex("^package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE)
  val importPattern: Regex = Regex("^import\\s+([A-Za-z0-9_.*]+)", RegexOption.MULTILINE)
  val portFunctionStartPattern: Regex = Regex("^fun\\s+([A-Za-z0-9_]+)\\s*\\(")
  val portFunctionSignaturePattern: Regex =
    Regex("fun\\s+([A-Za-z0-9_]+)\\s*\\((.*?)\\)\\s*:\\s*([A-Za-z0-9_.<>]+)")
  val publicModelDeclarationPattern: Regex =
    Regex(
      "^\\s*(?:public\\s+)?(data\\s+class|enum\\s+class|sealed\\s+(?:class|interface))\\s+([A-Za-z0-9_]+)",
      RegexOption.MULTILINE,
    )
  val scopeDeclarationPattern: Regex =
    Regex(
      """^\s*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+""" +
        """|data\s+|inner\s+|enum\s+|annotation\s+|value\s+|fun\s+)*""" +
        """(?:class|object|interface)\s+([A-Za-z0-9_]+)""",
    )
}
