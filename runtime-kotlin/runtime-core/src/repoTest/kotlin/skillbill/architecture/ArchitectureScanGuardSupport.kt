package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

private val INJECT_ANNOTATION_PATTERN = Regex("""@Inject\b""")
private const val CLASS_MODIFIERS =
  "public|internal|private|protected|open|abstract|sealed|value|data|annotation|inner|final"
private val INJECT_TYPE_PATTERN =
  Regex("""@Inject\s+(?:\n\s*)*(?:(?:$CLASS_MODIFIERS)\s+)*class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private const val NON_PRIVATE_PROPERTY_MODIFIERS =
  "public|internal|protected|open|override|final|lateinit|const|abstract"
private val NON_PRIVATE_PROPERTY_DEFAULT_PATTERN =
  Regex(
    """^\s*(?:@\w+\s+)*(?:(?:$NON_PRIVATE_PROPERTY_MODIFIERS)\s+)*(?:val|var)\s+""" +
      """([A-Za-z_][A-Za-z0-9_]*)\s*(?::[^=]*)?=""",
  )
private const val CONSTRUCTOR_PROPERTY_MODIFIERS = "public|internal|protected|private|open|override|final"
private val CONSTRUCTOR_PROPERTY_PATTERN =
  Regex(
    """^(?:@[\w.]+(?:\([^)]*\))?\s+)*((?:(?:$CONSTRUCTOR_PROPERTY_MODIFIERS)\s+)*)(?:val|var)\s+""" +
      """([A-Za-z_][A-Za-z0-9_]*)""",
  )
private val INTERNAL_DATA_CLASS_PATTERN =
  Regex("""\binternal\s+data\s+class\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val DATA_CLASS_PROPERTY_PATTERN =
  Regex(
    """^(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:$CONSTRUCTOR_PROPERTY_MODIFIERS)\s+)*(?:val|var)\s+""" +
      """([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+)$""",
    RegexOption.DOT_MATCHES_ALL,
  )
private val COLLABORATOR_EXACT_TYPE_NAMES = setOf("CliRunState", "Clock")
private val COLLABORATOR_TYPE_SUFFIXES =
  listOf(
    "Service",
    "Port",
    "Gateway",
    "Lookup",
    "Repository",
    "Coordinator",
    "Runner",
    "Launcher",
    "Diagnostics",
  )
private val CLASS_HEADER_TERMINATOR = Regex("""\n\s*\n|\}|\b(?:class|object|interface|fun|typealias)\b""")

private val AMBIENT_CLOCK_FORMS: List<Pair<Regex, String>> =
  listOf(
    Regex("""\bInstant\.now\s*\(""") to "Instant.now()",
    Regex("""\bLocalDateTime\.now\s*\(""") to "LocalDateTime.now()",
    Regex("""\bLocalDate\.now\s*\(""") to "LocalDate.now()",
    Regex("""\bOffsetDateTime\.now\s*\(""") to "OffsetDateTime.now()",
    Regex("""\bZonedDateTime\.now\s*\(""") to "ZonedDateTime.now()",
    Regex("""\bClock\.systemUTC\s*\(""") to "Clock.systemUTC()",
    Regex("""\bJvmSystemClock\.instant\s*\(""") to "JvmSystemClock.instant()",
    Regex("""\bSystem\.currentTimeMillis\s*\(""") to "System.currentTimeMillis()",
  )

private val AMBIENT_ENVIRONMENT_FORMS: List<Pair<Regex, String>> =
  listOf(
    Regex("""\bSystem\.getenv\s*\(""") to "System.getenv()",
    Regex("""\bSystem\.getProperty\s*\(""") to "System.getProperty()",
    Regex("""\bPath\.of\s*\(\s*""\s*\)""") to "Path.of(\"\")",
    Regex("""\bPaths\.get\s*\(\s*""\s*\)""") to "Paths.get(\"\")",
  )

private fun codeWithoutComments(line: String): String {
  val withoutLineComment = line.substringBefore("//")
  return withoutLineComment.replace(Regex("""/\*.*?\*/"""), "").substringBefore("/*")
}

private fun sourceWithoutCommentsOrLiterals(source: String): String =
  buildString(source.length) {
    var index = 0
    while (index < source.length) {
      index =
        when {
          source.startsWith("//", index) -> appendLineComment(source, index)
          source.startsWith("/*", index) -> appendBlockComment(source, index)
          source.startsWith("\"\"\"", index) -> appendTripleQuotedLiteral(source, index)
          source[index] == '"' || source[index] == '\'' -> appendQuotedLiteral(source, index)
          else -> {
            append(source[index])
            index + 1
          }
        }
    }
  }

private fun StringBuilder.appendLineComment(
  source: String,
  start: Int,
): Int {
  append("  ")
  var index = start + 2
  while (index < source.length && source[index] != '\n') {
    append(' ')
    index += 1
  }
  if (index < source.length) append('\n')
  return index + 1
}

private fun StringBuilder.appendBlockComment(
  source: String,
  start: Int,
): Int {
  append("  ")
  var index = start + 2
  var depth = 1
  while (index < source.length && depth > 0) {
    when {
      source.startsWith("/*", index) -> {
        append("  ")
        depth += 1
        index += 2
      }
      source.startsWith("*/", index) -> {
        append("  ")
        depth -= 1
        index += 2
      }
      source[index] == '\n' -> {
        append('\n')
        index += 1
      }
      else -> {
        append(' ')
        index += 1
      }
    }
  }
  return index
}

private fun StringBuilder.appendTripleQuotedLiteral(
  source: String,
  start: Int,
): Int {
  append("   ")
  var index = start + 3
  while (index < source.length && !source.startsWith("\"\"\"", index)) {
    append(if (source[index] == '\n') '\n' else ' ')
    index += 1
  }
  if (index < source.length) {
    append("   ")
    index += 3
  }
  return index
}

private fun StringBuilder.appendQuotedLiteral(
  source: String,
  start: Int,
): Int {
  val quote = source[start]
  append(' ')
  var index = start + 1
  var escaped = false
  while (index < source.length && (escaped || source[index] != quote)) {
    val character = source[index]
    append(if (character == '\n') '\n' else ' ')
    escaped = !escaped && character == '\\'
    if (character != '\\') escaped = false
    index += 1
  }
  if (index < source.length) {
    append(' ')
    index += 1
  }
  return index
}

private fun extractBalanced(
  source: String,
  openIndex: Int,
  open: Char,
  close: Char,
): String? {
  if (source.getOrNull(openIndex) != open) return null
  var depth = 0
  var index = openIndex
  while (index < source.length) {
    when (source[index]) {
      open -> depth += 1
      close -> {
        depth -= 1
        if (depth == 0) return source.substring(openIndex, index + 1)
      }
    }
    index += 1
  }
  return null
}

private fun defaultArgumentParameters(constructorBody: String): List<String> {
  val inner = constructorBody.trim().removePrefix("(").removeSuffix(")")
  if (inner.isBlank()) return emptyList()
  val parameters = splitTopLevelParameters(inner)
  return parameters.mapNotNull { parameter ->
    val name =
      parameter.substringBefore(':').trim().substringAfterLast(' ').ifBlank {
        parameter.substringBefore(':').trim()
      }
    if ('=' in parameter) name.takeIf { it.isNotBlank() } else null
  }
}

private fun splitTopLevelParameters(parameters: String): List<String> {
  val parts = mutableListOf<String>()
  val current = StringBuilder()
  var angleDepth = 0
  var parenDepth = 0
  parameters.forEach { character ->
    when (character) {
      '<' -> angleDepth += 1
      '>' -> angleDepth -= 1
      '(' -> parenDepth += 1
      ')' -> parenDepth -= 1
      ',' ->
        if (angleDepth == 0 && parenDepth == 0) {
          parts += current.toString().trim()
          current.clear()
          return@forEach
        }
    }
    current.append(character)
  }
  if (current.isNotBlank()) parts += current.toString().trim()
  return parts
}

private typealias AmbientSite = ArchitectureScanSupport.AmbientCallSite
private typealias AuthoredSuppressionSite = ArchitectureScanSupport.AuthoredSuppression

private fun ambientSitesInText(
  relativePath: String,
  text: String,
  forms: List<Pair<Regex, String>>,
): List<AmbientSite> {
  val sites = mutableListOf<AmbientSite>()
  text.lineSequence().forEachIndexed { index, line ->
    val code = codeWithoutComments(line)
    forms.filter { (pattern, _) -> pattern.containsMatchIn(code) }
      .forEach { (_, call) -> sites += AmbientSite(relativePath, index + 1, call) }
  }
  return sites
}

private fun ambientSitesUnder(
  root: Path,
  scanRoot: String,
  forms: List<Pair<Regex, String>>,
): List<AmbientSite> =
  ArchitectureScanSupport.kotlinFilesUnder(root.resolve(scanRoot))
    .flatMap { sourceFile ->
      val relativePath = root.relativize(sourceFile).toString().replace('\\', '/')
      ambientSitesInText(relativePath, sourceFile.readText(), forms)
    }

fun ArchitectureScanSupport.encodeAmbientSites(sites: List<AmbientSite>): List<String> =
  sites.groupingBy { site -> site.relativePath to site.call }
    .eachCount()
    .entries
    .sortedWith(compareBy({ entry -> entry.key.first }, { entry -> entry.key.second }))
    .map { (key, count) -> "${key.first}:${key.second}:$count" }

private fun unlistedAmbientRows(
  rows: List<String>,
  baseline: Set<String>,
  guardName: String,
): List<String> =
  (rows.toSet() - baseline)
    .sorted()
    .map { row -> "$row is not listed in the $guardName baseline." }

fun ArchitectureScanSupport.ambientClockCallSites(scanRoot: String): List<AmbientSite> =
  ambientSitesUnder(runtimeRoot, scanRoot, AMBIENT_CLOCK_FORMS)

fun ArchitectureScanSupport.encodeAmbientClockSitesInSource(
  relativePath: String,
  source: String,
): List<String> = encodeAmbientSites(ambientSitesInText(relativePath, source, AMBIENT_CLOCK_FORMS))

fun ArchitectureScanSupport.ambientClockViolationsInSource(
  relativePath: String,
  source: String,
  baseline: Set<String>,
): List<String> =
  unlistedAmbientRows(
    encodeAmbientClockSitesInSource(relativePath, source),
    baseline,
    "ambient-clock",
  )

fun ArchitectureScanSupport.ambientEnvironmentCallSites(scanRoot: String): List<AmbientSite> =
  ambientSitesUnder(runtimeRoot, scanRoot, AMBIENT_ENVIRONMENT_FORMS)
    .filterNot { site -> site.relativePath in PrincipleEnforcementInventory.ambientEnvironmentExemptions }

fun ArchitectureScanSupport.ambientEnvironmentViolationsInSource(
  relativePath: String,
  source: String,
  baseline: Set<String>,
): List<String> =
  unlistedAmbientRows(
    encodeAmbientSites(ambientSitesInText(relativePath, source, AMBIENT_ENVIRONMENT_FORMS)),
    baseline,
    "ambient-environment",
  )

private typealias ModuleScanCase = PrincipleEnforcementInventory.ModuleArchitectureScanCase

private fun ArchitectureScanSupport.moduleBaselineDrift(
  readBaseline: (String) -> String,
  baselineName: (ModuleScanCase) -> String,
  currentRows: (ModuleScanCase) -> Set<String>,
): List<String> =
  PrincipleEnforcementInventory.moduleArchitectureScanCases.flatMap { scanCase ->
    val name = baselineName(scanCase)
    val baseline = parseStringSetBaseline(readBaseline(name))
    val current = currentRows(scanCase)
    (current - baseline).sorted().map { row ->
      "${scanCase.moduleName}: $row is not listed in $name."
    } +
      (baseline - current).sorted().map { row ->
        "${scanCase.moduleName}: $row is listed in $name but no longer exists; re-record the baseline."
      }
  }

internal fun ArchitectureScanSupport.ambientClockDrift(
  scanRoot: Path = runtimeRoot,
  readBaseline: (String) -> String = ArchitectureBaselineSupport::readBaseline,
): List<String> =
  moduleBaselineDrift(readBaseline, ModuleScanCase::ambientClockBaseline) { scanCase ->
    encodeAmbientSites(ambientSitesUnder(scanRoot, scanCase.mainScanRoot, AMBIENT_CLOCK_FORMS)).toSet()
  }

internal fun ArchitectureScanSupport.ambientEnvironmentDrift(
  scanRoot: Path = runtimeRoot,
  readBaseline: (String) -> String = ArchitectureBaselineSupport::readBaseline,
): List<String> =
  moduleBaselineDrift(readBaseline, ModuleScanCase::ambientEnvironmentBaseline) { scanCase ->
    encodeAmbientSites(
      ambientSitesUnder(scanRoot, scanCase.mainScanRoot, AMBIENT_ENVIRONMENT_FORMS)
        .filterNot { site -> site.relativePath in PrincipleEnforcementInventory.ambientEnvironmentExemptions },
    ).toSet()
  }

internal const val SYNTHETIC_ENGINE_VIOLATION_PATH: String =
  "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/SyntheticEngineService.kt"

internal fun seedModuleScanTreeWithEngineViolation(
  root: Path,
  violationSource: String,
) {
  PrincipleEnforcementInventory.moduleArchitectureScanCases.forEach { scanCase ->
    val mainRoot = root.resolve(scanCase.mainScanRoot)
    Files.createDirectories(mainRoot)
    Files.writeString(mainRoot.resolve("SyntheticNeutral.kt"), "package skillbill.synthetic\n")
  }
  val violationFile = root.resolve(SYNTHETIC_ENGINE_VIOLATION_PATH)
  Files.createDirectories(violationFile.parent)
  Files.writeString(violationFile, violationSource)
}

internal fun ArchitectureScanSupport.injectConstructorDefaultDrift(
  scanRoot: Path = runtimeRoot,
  readBaseline: (String) -> String = ArchitectureBaselineSupport::readBaseline,
): List<String> =
  moduleBaselineDrift(readBaseline, ModuleScanCase::injectDefaultsBaseline) { scanCase ->
    injectConstructorDefaultSitesUnder(scanRoot, scanCase.mainScanRoot)
      .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
      .toSet()
  }

fun ArchitectureScanSupport.parseStringSetBaseline(text: String): Set<String> =
  text.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toSet()

private fun injectConstructorDefaultSitesUnder(
  root: Path,
  scanRoot: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> =
  ArchitectureScanSupport.kotlinFilesUnder(root.resolve(scanRoot))
    .flatMap { sourceFile ->
      val relativePath = root.relativize(sourceFile).toString().replace('\\', '/')
      ArchitectureScanSupport.injectConstructorDefaultSitesInSource(relativePath, sourceFile.readText())
    }
    .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

fun ArchitectureScanSupport.injectConstructorDefaultSites(
  scanRoot: String = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> =
  injectConstructorDefaultSitesUnder(runtimeRoot, scanRoot)

fun ArchitectureScanSupport.injectConstructorDefaultSitesInSource(
  relativePath: String,
  source: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> {
  if (!INJECT_ANNOTATION_PATTERN.containsMatchIn(source)) return emptyList()
  val scannable = sourceWithoutCommentsOrLiterals(source)
  val sites = mutableListOf<ArchitectureScanSupport.InjectConstructorDefaultSite>()
  INJECT_TYPE_PATTERN.findAll(scannable).forEach { match ->
    val symbol = match.groupValues[1]
    val headerEnd = afterClassTypeParameters(scannable, match.range.last + 1)
    val defaults =
      if (scannable.getOrNull(headerEnd) == '(') {
        extractBalanced(scannable, headerEnd, '(', ')')?.let(::defaultArgumentParameters)
      } else {
        classBody(scannable, headerEnd)?.let(::nonPrivatePropertyDefaults)
      }
    defaults.orEmpty().forEach { parameter ->
      sites += ArchitectureScanSupport.InjectConstructorDefaultSite(relativePath, symbol, parameter)
    }
  }
  return sites
}

fun ArchitectureScanSupport.injectConstructorPropertySites(
  scanRoot: String = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> =
  kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
    .flatMap { sourceFile ->
      val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
      injectConstructorPropertySitesInSource(relativePath, sourceFile.readText())
    }
    .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

fun ArchitectureScanSupport.injectConstructorPropertyViolations(
  baseline: Set<String>,
  scanRoot: String = PrincipleEnforcementInventory.RUNTIME_APPLICATION_MAIN,
): List<String> {
  val current =
    injectConstructorPropertySites(scanRoot)
      .map { site -> "${site.relativePath}::${site.symbol}::${site.parameter}" }
      .toSet()
  return (current - baseline).sorted().map { site ->
    "$site is a non-private constructor property on an @Inject class."
  }
}

fun ArchitectureScanSupport.injectConstructorPropertySitesInSource(
  relativePath: String,
  source: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> {
  if (!INJECT_ANNOTATION_PATTERN.containsMatchIn(source)) return emptyList()
  val scannable = sourceWithoutCommentsOrLiterals(source)
  return INJECT_TYPE_PATTERN.findAll(scannable).flatMap { match ->
    val headerEnd = afterClassTypeParameters(scannable, match.range.last + 1)
    val constructor =
      if (scannable.getOrNull(headerEnd) == '(') extractBalanced(scannable, headerEnd, '(', ')') else null
    nonPrivateConstructorProperties(constructor.orEmpty()).map { parameter ->
      ArchitectureScanSupport.InjectConstructorDefaultSite(relativePath, match.groupValues[1], parameter)
    }
  }.toList()
}

private fun nonPrivateConstructorProperties(constructorBody: String): List<String> {
  val inner = constructorBody.trim().removePrefix("(").removeSuffix(")").replace("->", "~~")
  if (inner.isBlank()) return emptyList()
  return splitTopLevelParameters(inner).mapNotNull { parameter ->
    CONSTRUCTOR_PROPERTY_PATTERN.find(parameter)
      ?.takeUnless { match -> "private" in match.groupValues[1].split(Regex("""\s+""")) }
      ?.groupValues
      ?.get(2)
  }
}

fun ArchitectureScanSupport.dataClassCollaboratorPropertySites(
  scanRoot: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> =
  kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
    .flatMap { sourceFile ->
      val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
      dataClassCollaboratorPropertySitesInSource(relativePath, sourceFile.readText())
    }
    .sortedWith(compareBy({ it.relativePath }, { it.symbol }, { it.parameter }))

fun ArchitectureScanSupport.dataClassCollaboratorPropertyViolations(scanRoot: String): List<String> =
  dataClassCollaboratorPropertySites(scanRoot).map { site ->
    "${site.relativePath}::${site.symbol}::${site.parameter} is a collaborator property on an internal data class."
  }

fun ArchitectureScanSupport.dataClassCollaboratorPropertySitesInSource(
  relativePath: String,
  source: String,
): List<ArchitectureScanSupport.InjectConstructorDefaultSite> {
  val scannable = sourceWithoutCommentsOrLiterals(source)
  return INTERNAL_DATA_CLASS_PATTERN.findAll(scannable).flatMap { match ->
    val headerEnd = afterClassTypeParameters(scannable, match.range.last + 1)
    val constructor =
      if (scannable.getOrNull(headerEnd) == '(') extractBalanced(scannable, headerEnd, '(', ')') else null
    collaboratorPropertyNames(constructor.orEmpty()).map { parameter ->
      ArchitectureScanSupport.InjectConstructorDefaultSite(relativePath, match.groupValues[1], parameter)
    }
  }.toList()
}

private fun collaboratorPropertyNames(constructorBody: String): List<String> {
  val inner = constructorBody.trim().removePrefix("(").removeSuffix(")").replace("->", "~~")
  if (inner.isBlank()) return emptyList()
  return splitTopLevelParameters(inner).mapNotNull { parameter ->
    DATA_CLASS_PROPERTY_PATTERN.find(parameter.trim())?.let { match ->
      val type = match.groupValues[2].substringBefore('=').trim()
      match.groupValues[1].takeIf { "~~" !in type && isCollaboratorTypeName(simpleTypeName(type)) }
    }
  }
}

private fun simpleTypeName(type: String): String =
  type.substringBefore('<').removeSuffix("?").trim().substringAfterLast('.')

private fun isCollaboratorTypeName(name: String): Boolean =
  name in COLLABORATOR_EXACT_TYPE_NAMES || COLLABORATOR_TYPE_SUFFIXES.any(name::endsWith)

private fun afterClassTypeParameters(
  source: String,
  nameEnd: Int,
): Int {
  var index = source.skipWhitespace(nameEnd)
  if (source.getOrNull(index) == '<') {
    val typeParameters = extractBalanced(source, index, '<', '>') ?: return index
    index = source.skipWhitespace(index + typeParameters.length)
  }
  return index
}

private fun String.skipWhitespace(from: Int): Int {
  var index = from
  while (index < length && this[index].isWhitespace()) index += 1
  return index
}

private fun classBody(
  source: String,
  headerEnd: Int,
): String? {
  val braceIndex = source.indexOf('{', headerEnd)
  if (braceIndex < 0) return null
  if (CLASS_HEADER_TERMINATOR.containsMatchIn(source.substring(headerEnd, braceIndex))) return null
  return extractBalanced(source, braceIndex, '{', '}')?.removeSurrounding("{", "}")
}

private fun nonPrivatePropertyDefaults(classBody: String): List<String> {
  val names = mutableListOf<String>()
  var depth = 0
  classBody.lineSequence().forEach { code ->
    if (depth == 0) {
      NON_PRIVATE_PROPERTY_DEFAULT_PATTERN.find(code)?.let { match -> names += match.groupValues[1] }
    }
    depth += code.count { it == '{' || it == '(' } - code.count { it == '}' || it == ')' }
    if (depth < 0) depth = 0
  }
  return names
}

private val COMPLEXITY_SUPPRESSION_RULES: Set<String> =
  setOf(
    "TooManyFunctions",
    "LargeClass",
    "LongMethod",
    "CyclomaticComplexMethod",
    "ComplexCondition",
    "NestedBlockDepth",
    "ReturnCount",
    "ThrowsCount",
    "LongParameterList",
  )

private val SUPPRESSION_SCAN_ROOTS: List<String> =
  listOf(
    "runtime-kotlin",
    "runtime-kotlin/build-logic",
  )

internal fun ArchitectureScanSupport.authoredSuppressions(
  scanRoots: List<String> = SUPPRESSION_SCAN_ROOTS,
): List<AuthoredSuppressionSite> =
  scanRoots.flatMap { scanRoot ->
    kotlinFilesUnder(runtimeRoot.resolve(scanRoot))
      .filter { path -> !path.toString().replace('\\', '/').contains("/generated/") }
      .flatMap { sourceFile ->
        val normalized = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
        val relativePath = normalized.removePrefix("runtime-kotlin/")
        authoredSuppressionsFromFile(relativePath, sourceFile.readText())
      }
  }

internal fun ArchitectureScanSupport.authoredSuppressionsFromFile(
  relativePath: String,
  source: String,
): List<AuthoredSuppressionSite> = AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

internal fun ArchitectureScanSupport.authoredSuppressionsInSource(
  relativePath: String,
  source: String,
): List<AuthoredSuppressionSite> = AuthoredSuppressionScanner.scan(relativePath, source.lineSequence())

internal fun ArchitectureScanSupport.suppressionViolations(
  suppressions: List<AuthoredSuppressionSite>,
  allowList: Set<Triple<String, String, String>>,
): List<String> =
  suppressions.mapNotNull { site ->
    when {
      site.rule in COMPLEXITY_SUPPRESSION_RULES ->
        "${site.relativePath}::${site.symbol} uses banned complexity suppression '${site.rule}'; refactor instead."
      Triple(site.relativePath, site.symbol, site.rule) !in allowList ->
        "${site.relativePath}::${site.symbol} has @Suppress('${site.rule}') without an allow-list row; " +
          "fix the finding or add path, symbol, rule, and why to " +
          "PrincipleEnforcementInventory.suppressionAllowList."
      else -> null
    }
  }.sorted()

fun ArchitectureScanSupport.detektComplexityPinViolations(detektYaml: String): List<String> =
  COMPLEXITY_SUPPRESSION_RULES.mapNotNull { rule ->
    val section = Regex("""\n\s*$rule:\s*\n\s*active:\s*(true|false)""").find(detektYaml)
    when {
      section == null -> "detekt.yml missing pinned complexity rule '$rule'."
      section.groupValues[1] != "true" -> "detekt.yml must pin '$rule' with active: true."
      else -> null
    }
  }

private val PROVIDES_FUNCTION_PATTERN =
  Regex("""@Provides[\s\S]*?fun\s+[A-Za-z_][A-Za-z0-9_]*\s*\(([^)]*)\)""")

private val PROVIDES_EXPLICIT_CONSTRUCTION_PATTERN =
  Regex(
    """@Provides[\s\S]*?fun\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\)\s*(?::[^={]+)?=\s*([A-Z][A-Za-z0-9_]*)\s*\(""",
  )

private val CLASS_DECLARATION_CONSTRUCTION_PATTERN =
  Regex("""\b(?:class|object|data\s+class|enum\s+class)\s+[A-Za-z_][A-Za-z0-9_]*\s*\(""")

private val IMPORT_ALIAS_PATTERN =
  Regex("""^\s*import\s+([A-Za-z0-9_.]+)(?:\s+as\s+([A-Za-z_][A-Za-z0-9_]*))?\s*$""", RegexOption.MULTILINE)
private val COMPONENT_PROVIDER_RETURN_TYPE_PATTERN =
  Regex("""^\s*fun\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\)\s*:\s*([A-Z][A-Za-z0-9_]*)""", RegexOption.MULTILINE)

private fun concreteTypeNamesFromProvidesParameterList(parameters: String): List<String> {
  if (parameters.isBlank()) return emptyList()
  return splitTopLevelParameters(parameters).mapNotNull { parameter ->
    val typeSegment = parameter.substringAfter(':', "").substringBefore('=').trim()
    if (typeSegment.isEmpty()) return@mapNotNull null
    Regex("""([A-Z][A-Za-z0-9_]*)""")
      .findAll(typeSegment)
      .lastOrNull()
      ?.value
  }
}

private fun canonicalTypeName(
  source: String,
  typeName: String,
): String =
  IMPORT_ALIAS_PATTERN.findAll(source)
    .firstOrNull { match -> match.groupValues[2] == typeName }
    ?.groupValues
    ?.get(1)
    ?.substringAfterLast('.')
    ?: typeName

private fun componentProviderReturnTypeNames(source: String): Set<String> =
  COMPONENT_PROVIDER_RETURN_TYPE_PATTERN.findAll(source)
    .map { match -> canonicalTypeName(source, match.groupValues[1]) }
    .toSet()

private fun providesBoundConcreteTypeNames(
  source: String,
  excludedParameterTypeNames: Set<String> = emptySet(),
): Set<String> {
  val names = linkedSetOf<String>()
  PROVIDES_FUNCTION_PATTERN.findAll(source).forEach { match ->
    concreteTypeNamesFromProvidesParameterList(match.groupValues[1])
      .map { typeName -> canonicalTypeName(source, typeName) }
      .filterNot { typeName -> typeName in excludedParameterTypeNames }
      .forEach(names::add)
  }
  PROVIDES_EXPLICIT_CONSTRUCTION_PATTERN.findAll(source).forEach { match ->
    names += canonicalTypeName(source, match.groupValues[1])
  }
  return names
}

private fun importLocalNamesForSimpleType(
  source: String,
  simpleName: String,
): Set<String> {
  val localNames = linkedSetOf(simpleName)
  IMPORT_ALIAS_PATTERN.findAll(source).forEach { match ->
    val imported = match.groupValues[1]
    val alias = match.groupValues[2]
    if (imported == simpleName || imported.endsWith(".$simpleName")) {
      if (alias.isNotBlank()) {
        localNames += alias
      }
    }
  }
  return localNames
}

private fun constructionTokenToBoundClass(
  source: String,
  boundClassNames: Set<String>,
): Map<String, String> {
  val tokenToBound = linkedMapOf<String, String>()
  boundClassNames.forEach { boundClass ->
    importLocalNamesForSimpleType(source, boundClass).forEach { token ->
      tokenToBound[token] = boundClass
    }
  }
  return tokenToBound
}

private fun isClassDeclarationConstruction(
  source: String,
  matchStart: Int,
): Boolean {
  val lineStart = source.lastIndexOf('\n', matchStart - 1) + 1
  val lineEnd = source.indexOf('\n', matchStart).let { index -> if (index < 0) source.length else index }
  return CLASS_DECLARATION_CONSTRUCTION_PATTERN.containsMatchIn(source.substring(lineStart, lineEnd))
}

private fun isSameNamedFunctionDeclaration(
  source: String,
  matchStart: Int,
  token: String,
): Boolean {
  val lineStart = source.lastIndexOf('\n', matchStart - 1) + 1
  val lineEnd = source.indexOf('\n', matchStart).let { index -> if (index < 0) source.length else index }
  return Regex("""\bfun\s+${Regex.escape(token)}\s*\(""").containsMatchIn(source.substring(lineStart, lineEnd))
}

private fun constructionViolationsForBoundClasses(
  relativePath: String,
  source: String,
  boundClassNames: Set<String>,
): List<String> {
  val scannable = sourceWithoutCommentsOrLiterals(source)
  val tokenToBound = constructionTokenToBoundClass(source, boundClassNames)
  if (tokenToBound.isEmpty()) return emptyList()
  val pattern =
    Regex(
      tokenToBound.keys.sortedByDescending(String::length).joinToString(separator = "|") { Regex.escape(it) }
        .let { """\b($it)\s*\(""" },
    )
  return pattern.findAll(scannable).mapNotNull { match ->
    val token = match.groupValues[1]
    if (isClassDeclarationConstruction(scannable, match.range.first)) return@mapNotNull null
    if (isSameNamedFunctionDeclaration(scannable, match.range.first, token)) return@mapNotNull null
    val boundClass = tokenToBound[token] ?: return@mapNotNull null
    "$relativePath constructs $boundClass outside skillbill.di"
  }.toList()
}

fun ArchitectureScanSupport.boundComponentConcreteClassNames(diScanRoot: String): Set<String> {
  val componentFile = runtimeRoot.resolve(PrincipleEnforcementInventory.RUNTIME_COMPONENT_SOURCE)
  val excludedParameterTypeNames =
    componentFile.takeIf { Files.exists(it) }
      ?.let { componentProviderReturnTypeNames(it.readText()) }
      .orEmpty()
  val names = linkedSetOf<String>()
  kotlinFilesUnder(runtimeRoot.resolve(diScanRoot)).forEach { sourceFile ->
    names += providesBoundConcreteTypeNames(sourceFile.readText(), excludedParameterTypeNames)
  }
  return names
}

fun ArchitectureScanSupport.boundComponentConcreteClassNamesInSource(source: String): Set<String> =
  providesBoundConcreteTypeNames(source)

fun ArchitectureScanSupport.directComponentConstructionViolations(
  boundClassNames: Set<String>,
  scanRoots: List<String>,
  compositionDiRoot: String,
): List<String> {
  if (boundClassNames.isEmpty()) return emptyList()
  val violations = mutableListOf<String>()
  scanRoots.forEach { scanRoot ->
    kotlinFilesUnder(runtimeRoot.resolve(scanRoot)).forEach { sourceFile ->
      val relativePath = runtimeRoot.relativize(sourceFile).toString().replace('\\', '/')
      if (relativePath.startsWith(compositionDiRoot)) return@forEach
      violations +=
        constructionViolationsForBoundClasses(
          relativePath,
          sourceFile.readText(),
          boundClassNames,
        )
    }
  }
  return violations.sorted()
}

fun ArchitectureScanSupport.directComponentConstructionViolationsForSource(
  boundClassNames: Set<String>,
  relativePath: String,
  source: String,
): List<String> {
  if (boundClassNames.isEmpty()) return emptyList()
  return constructionViolationsForBoundClasses(relativePath, source, boundClassNames)
}

private val THROWABLE_DECLARATION_PATTERN =
  Regex("""(?<!::)\b(?:(enum|annotation|companion)\s+)?(?:class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)""")
private val PRIMARY_CONSTRUCTOR_KEYWORD_PATTERN =
  Regex("""^(?:@\w+\s+)*(?:(?:public|internal|private|protected)\s+)?constructor\b""")
private val SUPERTYPE_LIST_END_PATTERN =
  Regex("""^(?:[ \t]*\n|[ \t]*(?:@|\}|(?:[a-z]+[ \t]+)*(?:class|object|interface|fun|val|var|typealias)\b))""")
private const val THROWABLE_ROOT_NAME = "Throwable"
private val KOTLIN_THROWABLE_ROOT_NAMES = setOf(THROWABLE_ROOT_NAME, "Error", "Exception")

private data class RawThrowableDeclaration(
  val start: Int,
  val name: String,
  val body: IntRange?,
  val supertypes: List<String>,
)

private data class ThrowableDeclaration(
  val moduleName: String,
  val file: String,
  val path: String,
  val supertypes: List<String>,
) {
  val simpleName: String get() = path.substringAfterLast('.')
  val row: String get() = "$moduleName:$path"
}

internal fun ArchitectureScanSupport.customThrowableRows(scanRoot: Path = runtimeRoot): Set<String> {
  val declarations =
    PrincipleEnforcementInventory.moduleArchitectureScanCases.flatMap { scanCase ->
      kotlinFilesUnder(scanRoot.resolve(scanCase.mainScanRoot)).flatMap { sourceFile ->
        throwableDeclarationsInSource(scanCase.moduleName, sourceFile.toString(), sourceFile.readText())
      }
    }
  val bySimpleName = declarations.groupBy(ThrowableDeclaration::simpleName)
  return declarations
    .filter { declaration -> isThrowable(declaration, declarations, bySimpleName, mutableSetOf()) }
    .map(ThrowableDeclaration::row)
    .toSet()
}

internal fun ArchitectureScanSupport.customThrowableDrift(
  scanRoot: Path = runtimeRoot,
  readBaseline: (String) -> String = ArchitectureBaselineSupport::readBaseline,
): List<String> {
  val baselineName = PrincipleEnforcementInventory.CUSTOM_THROWABLE_BASELINE
  val baseline = parseStringSetBaseline(readBaseline(baselineName))
  val current = customThrowableRows(scanRoot)
  return (current - baseline).sorted().map { row ->
    "${row.substringBefore(':')} declares custom throwable ${row.substringAfter(':')}; " +
      "return a result, use require/check, or throw SkillBillRuntimeException with a code."
  } +
    (baseline - current).sorted().map { row ->
      "$row is listed in $baselineName but no longer exists; re-record the baseline."
    }
}

private fun throwableDeclarationsInSource(
  moduleName: String,
  file: String,
  source: String,
): List<ThrowableDeclaration> {
  val raw = rawThrowableDeclarations(sourceWithoutCommentsOrLiterals(source))
  return raw.map { declaration ->
    val parents =
      raw.filter { parent ->
        parent.start < declaration.start && parent.body?.contains(declaration.start) == true
      }
    ThrowableDeclaration(
      moduleName = moduleName,
      file = file,
      path = (parents.map(RawThrowableDeclaration::name) + declaration.name).joinToString("."),
      supertypes = declaration.supertypes,
    )
  }
}

private fun rawThrowableDeclarations(source: String): List<RawThrowableDeclaration> =
  THROWABLE_DECLARATION_PATTERN.findAll(source)
    .filter { match -> match.groupValues[1].isEmpty() }
    .map { match ->
      val constructorEnd = afterPrimaryConstructor(source, afterClassTypeParameters(source, match.range.last + 1))
      val colonIndex = source.skipWhitespace(constructorEnd)
      val hasSupertypes = source.startsWith(":", colonIndex) && !source.startsWith("::", colonIndex)
      val headerEnd = if (hasSupertypes) supertypeListEnd(source, colonIndex + 1) else constructorEnd
      val braceIndex = source.skipWhitespace(headerEnd)
      RawThrowableDeclaration(
        start = match.range.first,
        name = match.groupValues[2],
        body = extractBalanced(source, braceIndex, '{', '}')?.let { body -> braceIndex until braceIndex + body.length },
        supertypes =
          if (hasSupertypes) {
            supertypeReferences(source.substring(colonIndex + 1, headerEnd))
          } else {
            emptyList()
          },
      )
    }
    .toList()

private fun afterPrimaryConstructor(
  source: String,
  from: Int,
): Int {
  var index = source.skipWhitespace(from)
  PRIMARY_CONSTRUCTOR_KEYWORD_PATTERN.find(source.substring(index))?.let { keyword ->
    index = source.skipWhitespace(index + keyword.value.length)
  }
  return extractBalanced(source, index, '(', ')')?.let { parameters -> index + parameters.length } ?: from
}

private fun supertypeListEnd(
  source: String,
  from: Int,
): Int {
  var depth = 0
  var index = from
  while (index < source.length) {
    val character = source[index]
    when {
      character == '(' || character == '<' -> depth += 1
      character == ')' || (character == '>' && source.getOrNull(index - 1) != '-') -> depth -= 1
      depth == 0 && character == '{' -> return index
      depth == 0 && character == '\n' && SUPERTYPE_LIST_END_PATTERN.containsMatchIn(source.substring(index + 1)) ->
        return index
    }
    index += 1
  }
  return source.length
}

private fun supertypeReferences(supertypeList: String): List<String> =
  splitTopLevelParameters(supertypeList)
    .map { entry -> entry.substringBefore(" by ").substringBefore('<').substringBefore('(').trim().removeSuffix("?") }
    .filter(String::isNotBlank)

private fun isThrowable(
  declaration: ThrowableDeclaration,
  all: List<ThrowableDeclaration>,
  bySimpleName: Map<String, List<ThrowableDeclaration>>,
  visited: MutableSet<ThrowableDeclaration>,
): Boolean {
  if (!visited.add(declaration)) return false
  return declaration.supertypes.any { reference ->
    val sameFile = all.filter { other -> other.file == declaration.file && other.path == reference }
    val simpleReference = reference.substringAfterLast('.')
    val resolved =
      sameFile.ifEmpty {
        if (simpleReference in KOTLIN_THROWABLE_ROOT_NAMES) emptyList() else bySimpleName[simpleReference].orEmpty()
      }
    if (resolved.isNotEmpty()) {
      resolved.any { supertype -> isThrowable(supertype, all, bySimpleName, visited) }
    } else {
      val root = reference.substringAfterLast('.')
      root == THROWABLE_ROOT_NAME || root.endsWith("Exception") || root.endsWith("Error")
    }
  }
}
