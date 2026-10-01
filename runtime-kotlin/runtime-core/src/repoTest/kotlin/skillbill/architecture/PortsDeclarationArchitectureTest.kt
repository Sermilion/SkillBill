package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PortsDeclarationArchitectureTest {
  private val portsMainRoot = moduleMainKotlinRoot("runtime-ports")

  @Test
  fun `runtime-ports main source has no forbidden top-level objects classes casts or interface throw defaults`() {
    val violations = scanPortsMainSource()
    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `scanner rejects synthetic fixtures for each forbidden ports-main pattern`() {
    val objectFixture =
      """

      object ForbiddenPortObject
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: object ForbiddenPortObject"),
      topLevelObjectViolations("Forbidden.kt", objectFixture),
    )

    val classFixture =
      """

      class ForbiddenPortClass
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: class ForbiddenPortClass"),
      forbiddenTopLevelClassViolations("Forbidden.kt", classFixture),
    )

    val castFixture =
      """

      fun cast(value: Any) = (this as String)
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: fun cast(value: Any) = (this as String)"),
      thisAsCastViolations("Forbidden.kt", castFixture),
    )

    val interfaceFixture =
      """

      interface ForbiddenPort {
        fun refuse(): Unit = error("not implemented")
      }
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: interface default body uses error or throw"),
      interfaceDefaultBodyViolations("Forbidden.kt", interfaceFixture),
    )
  }

  @Test
  fun `the interface default check rejects constant results outside fun interfaces`() {
    val constantFixture =
      """
      interface ConstantDefaultPort {
        fun enabled(): Boolean = true
      }
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: interface default body returns a constant result"),
      interfaceDefaultBodyViolations("Forbidden.kt", constantFixture),
    )

    val gitResultFixture =
      """
      interface GitDefaultPort {
        fun stage(repoRoot: Path): WorkflowGitOperationResult = WorkflowGitOperationResult.Ok("")
      }
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: interface default body returns a constant result"),
      interfaceDefaultBodyViolations("Forbidden.kt", gitResultFixture),
    )

    val derivedFixture =
      """
      interface DerivedDefaultPort {
        fun label(): String

        fun shortLabel(): String = label().take(8)
      }
      """.trimIndent()
    assertEquals(emptyList(), interfaceDefaultBodyViolations("Allowed.kt", derivedFixture))

    val funInterfaceFixture =
      """
      fun interface ProbePort {
        fun probe(): String?

        fun fallbackProbe(): String? = null
      }
      """.trimIndent()
    assertEquals(emptyList(), interfaceDefaultBodyViolations("Allowed.kt", funInterfaceFixture))

    val nestedBraceFixture =
      """
      interface NestedBraceDefaultPort {
        fun labels(): List<String>

        fun firstLabel(): String? = labels().firstOrNull { it.isNotBlank() }

        fun enabled(): Boolean = true
      }
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: interface default body returns a constant result"),
      interfaceDefaultBodyViolations("Forbidden.kt", nestedBraceFixture),
    )
  }

  @Test
  fun `the repository-driving function check rejects repository receivers and store parameters`() {
    val receiverFixture =
      """
      fun WorkflowStateRepository.findParent(issueKey: String): String? = null
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: fun findParent has a *Repository receiver"),
      repositoryDrivingFunctionViolations("Forbidden.kt", receiverFixture),
    )

    val nestedGenericFixture =
      """
      fun <T : Comparable<T>> WorkflowStateRepository.f(): T? = null
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: fun f has a *Repository receiver"),
      repositoryDrivingFunctionViolations("Forbidden.kt", nestedGenericFixture),
    )

    val multiLineFixture =
      """
      fun persistFailure(
        engine: WorkflowEngine,
        unitOfWork: UnitOfWork,
        workflowId: String,
      ): Boolean
      """.trimIndent()
    assertEquals(
      listOf(
        "Forbidden.kt: fun persistFailure takes engine: WorkflowEngine",
        "Forbidden.kt: fun persistFailure takes unitOfWork: UnitOfWork",
      ),
      repositoryDrivingFunctionViolations("Forbidden.kt", multiLineFixture),
    )

    val storeParameterFixture =
      """
      fun loadManifest(path: Path, fileStore: DecompositionManifestStore): String
      """.trimIndent()
    assertEquals(
      listOf("Forbidden.kt: fun loadManifest takes fileStore: DecompositionManifestStore"),
      repositoryDrivingFunctionViolations("Forbidden.kt", storeParameterFixture),
    )

    val derivedFixture =
      """
      fun TelemetryConfigStore.writeTelemetryLevel(level: String): Boolean {
        return true
      }

      fun interface ProbeStore {
        fun probe(): String?
      }
      """.trimIndent()
    assertEquals(emptyList(), repositoryDrivingFunctionViolations("Allowed.kt", derivedFixture))
  }

  private fun scanPortsMainSource(): List<String> {
    val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(portsMainRoot)
    return buildList {
      sourceFiles.forEach { path ->
        val source = Files.readString(path)
        val fileName = portsMainRoot.relativize(path).toString()
        addAll(topLevelObjectViolations(fileName, source))
        addAll(forbiddenTopLevelClassViolations(fileName, source))
        addAll(thisAsCastViolations(fileName, source))
        addAll(interfaceDefaultBodyViolations(fileName, source))
        addAll(repositoryDrivingFunctionViolations(fileName, source))
      }
    }.sorted()
  }

  private val topLevelFunctionStart =
    Regex("""^(?:(?:public|internal|private|inline|suspend|operator|infix|tailrec)\s+)*fun\s+""")

  private val funInterfaceStart = Regex("""\bfun\s+interface\b""")

  private val functionHeader = Regex("""fun\s+(?:<(?:[^<>]|<[^<>]*>)*>\s+)?(?:([^(]+?)\.)?(\w+)\s*\(""")

  private val forbiddenParameterTypes =
    setOf("UnitOfWork", "GoalRunnerPersistenceSession", "DatabaseSessionFactory", "WorkflowEngine")

  private fun repositoryDrivingFunctionViolations(
    fileName: String,
    source: String,
  ): List<String> =
    topLevelFunctionSignatures(source).flatMap { signature ->
      val header = functionHeader.find(signature) ?: return@flatMap emptyList()
      val name = header.groupValues[2]
      val receiver = header.groupValues[1].takeIf(String::isNotEmpty)?.let(::simpleTypeName)
      buildList {
        if (receiver != null && receiver.endsWith("Repository")) {
          add("$fileName: fun $name has a *Repository receiver")
        }
        parameterDeclarations(signature, header.range.last).forEach { parameter ->
          val type = parameter.substringAfter(':', "").substringBefore('=').trim()
          if (type.isEmpty() || type.startsWith("(")) return@forEach
          val typeName = simpleTypeName(type)
          if (typeName in forbiddenParameterTypes || typeName.endsWith("Repository") || typeName.endsWith("Store")) {
            add("$fileName: fun $name takes ${parameter.substringBefore(':').trim()}: $typeName")
          }
        }
      }
    }

  private fun simpleTypeName(type: String): String =
    type.substringBefore('<').trim().removeSuffix("?").substringAfterLast('.')

  private fun topLevelFunctionSignatures(source: String): List<String> {
    val lines = source.lines()
    return buildList {
      var index = 0
      while (index < lines.size) {
        val line = lines[index]
        if (topLevelFunctionStart.containsMatchIn(line) && !funInterfaceStart.containsMatchIn(line)) {
          var signature = line
          while (parameterListEnd(signature, functionHeader.find(signature)?.range?.last) == null &&
            index + 1 < lines.size
          ) {
            index++
            signature += "\n" + lines[index]
          }
          add(signature)
        }
        index++
      }
    }
  }

  private fun parameterListEnd(
    signature: String,
    openIndex: Int?,
  ): Int? {
    if (openIndex == null) return signature.length
    var depth = 0
    for (index in openIndex until signature.length) {
      when (signature[index]) {
        '(' -> depth++
        ')' -> {
          depth--
          if (depth == 0) return index
        }
      }
    }
    return null
  }

  private fun parameterDeclarations(
    signature: String,
    openIndex: Int,
  ): List<String> {
    val closeIndex = parameterListEnd(signature, openIndex) ?: signature.length
    val parameters = mutableListOf<String>()
    var depth = 0
    var start = openIndex + 1
    for (index in start until closeIndex) {
      when (signature[index]) {
        '(', '<' -> depth++
        ')' -> depth--
        '>' -> if (signature.getOrNull(index - 1) != '-') depth--
        ',' ->
          if (depth == 0) {
            parameters += signature.substring(start, index)
            start = index + 1
          }
      }
    }
    parameters += signature.substring(start, closeIndex)
    return parameters.filter { it.isNotBlank() }
  }

  private fun topLevelObjectViolations(
    fileName: String,
    source: String,
  ): List<String> =
    source.lineSequence()
      .map { it.trim() }
      .filter { line ->
        Regex(
          """(?:(?:public|private|protected|internal)\s+)?(?<!data\s)object\s+[A-Za-z_][A-Za-z0-9_]*\b""",
        )
          .containsMatchIn(line)
      }
      .map { line -> "$fileName: $line" }
      .toList()

  private fun forbiddenTopLevelClassViolations(
    fileName: String,
    source: String,
  ): List<String> =
    source.lineSequence()
      .map { it.trim() }
      .filter { line ->
        line.startsWith("class ") ||
          line.startsWith("internal class ") ||
          line.startsWith("abstract class ") ||
          line.startsWith("open class ")
      }
      .filterNot { line ->
        line.startsWith("data class ") ||
          line.startsWith("enum class ") ||
          line.startsWith("sealed class ") ||
          line.startsWith("value class ") ||
          line.startsWith("internal data class ") ||
          line.startsWith("internal enum class ") ||
          line.startsWith("internal sealed class ") ||
          line.startsWith("internal value class ")
      }
      .map { line -> "$fileName: $line" }
      .toList()

  private fun thisAsCastViolations(
    fileName: String,
    source: String,
  ): List<String> =
    source.lineSequence()
      .filter { line -> "(this as" in line }
      .map { line -> "$fileName: ${line.trim()}" }
      .toList()

  private fun interfaceDefaultBodyViolations(
    fileName: String,
    source: String,
  ): List<String> {
    val sourceWithoutCompanionBodies = removeCompanionBodies(source)
    return abstractInterfaceBodies(sourceWithoutCompanionBodies).flatMap { body ->
      buildList {
        if (
          Regex(
            """\bfun\s+\w+\s*\([^)]*\)[^={]*(?:=[^{]*\b(?:error|throw)\s*\(|\{[^}]*\b(?:error|throw)\b)""",
            RegexOption.DOT_MATCHES_ALL,
          ).containsMatchIn(body)
        ) {
          add("$fileName: interface default body uses error or throw")
        }
        if (constantDefaultBody.containsMatchIn(body)) {
          add("$fileName: interface default body returns a constant result")
        }
      }
    }
  }

  private val constantDefaultBody =
    Regex(
      """\bfun\s+\w+\s*\([^)]*\)\s*(?::[^={\n]*)?=\s*""" +
        """(?:true\b|false\b|null\b|Unit\b|""" +
        """empty(?:List|Map|Set|Array)\(\)|listOf\(\)|mapOf\(\)|setOf\(\)|""" +
        """WorkflowGitOperationResult\.(?:Ok|Failed)\b)""",
    )

  private fun abstractInterfaceBodies(source: String): List<String> =
    Regex("""(?<!fun )\binterface\s+\w+[^{]*\{""")
      .findAll(source)
      .mapNotNull { match ->
        matchingBrace(source, match.range.last)?.let { closing -> source.substring(match.range.last + 1, closing) }
      }
      .toList()

  private fun removeCompanionBodies(source: String): String {
    val ranges =
      Regex("""companion\s+object\s*\{""")
        .findAll(source)
        .mapNotNull { match ->
          val closingBrace = matchingBrace(source, match.range.last)
          closingBrace?.let { match.range.first..it }
        }
        .toList()
    return ranges.asReversed().fold(source) { current, range -> current.removeRange(range) }
  }

  private fun matchingBrace(
    source: String,
    openIndex: Int,
  ): Int? {
    var depth = 0
    for (index in openIndex until source.length) {
      when (source[index]) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return index
        }
      }
    }
    return null
  }
}
