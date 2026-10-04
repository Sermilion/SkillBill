package skillbill.architecture

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeEnforcementHardeningArchitectureTest {
  private val runtimeRoot: Path = ArchitectureScanSupport.runtimeRoot

  @Test
  fun `application domain and ports do not embed inline fully-qualified adapter or infrastructure references`() {
    val violations =
      SOURCE_TEXT_LAYER_RULES.flatMap { (sourceRoot, forbiddenPrefixes) ->
        val root = runtimeRoot.resolve(sourceRoot)
        val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(root)
        sourceFiles.flatMap { sourceFile ->
          bannedInlineReferences(sourceFile.readText(), forbiddenPrefixes).map { reference ->
            "${runtimeRoot.relativize(sourceFile)} contains inline reference $reference"
          }
        }
      }.sorted()

    assertEquals(
      emptyList(),
      violations,
      "Application, domain, and port source must not embed inline fully-qualified references to adapters, " +
        "infrastructure, or DI composition roots — even without a matching import statement.",
    )
  }

  @Test
  fun `inline fully-qualified reference scanner fires on synthetic fixture`() {
    val fixtureWithInlineReference =
      """

      class Leaky {
        fun build(): Any = skillbill.infrastructure.skills.Foo()
      }
      """.trimIndent()
    assertEquals(
      listOf("skillbill.infrastructure"),
      bannedInlineReferences(
        fixtureWithInlineReference,
        listOf("skillbill.cli", "skillbill.infrastructure", "skillbill.db"),
      ),
      "Inline-FQN scanner must report a bare `skillbill.infrastructure.skills.Foo()` reference with no import.",
    )

    val wholeCommentLine = "// trailing comment skillbill.cli.Baz reference must be ignored"
    val commentTailLine = "fun build(): Foo = Foo() // inline tail skillbill.infrastructure.skills.Qux"
    val cleanFixture =
      """

      import skillbill.infrastructure.skills.Foo

      interface CleanDoc {
        /**
         * Doc: see skillbill.infrastructure.skills.Foo for the adapter wiring.
         * skillbill.infrastructure.sqlite.Bar is the legacy path.
         */
        fun documented(): Unit
      }

      class Clean {
        $wholeCommentLine
        $commentTailLine
      }
      """.trimIndent()
    assertEquals(
      emptyList(),
      bannedInlineReferences(cleanFixture, listOf("skillbill.cli", "skillbill.infrastructure", "skillbill.db")),
      "Inline-FQN scanner must not flag forbidden FQNs that appear only inside KDoc, block comments, " +
        "or `//` line-comment tails.",
    )
  }

  @Test
  fun `pure layers must not import concrete schema or coherence validators`() {
    val guardedSourceRoots =
      listOf(
        "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/install",
        "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/workflow",
        moduleMainKotlinRootRelative("runtime-application"),
      )
    val scannedFiles =
      guardedSourceRoots
        .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
        .flatMap { root -> kotlinFilesUnderWithArchitectureAsserts(root) }
    assertTrue(
      scannedFiles.size >= guardedSourceRoots.size,
      "Each guarded source root must resolve to at least one .kt file; a renamed or absent root would " +
        "silently make this AC3 guard vacuous. Guarded roots: $guardedSourceRoots",
    )
    val violations =
      scannedFiles
        .flatMap { sourceFile ->
          importedNames(sourceFile.readText())
            .filter(::isSchemaOrCoherenceValidatorImport)
            .map { importedName -> "${runtimeRoot.relativize(sourceFile)} imports $importedName" }
            .toList()
        }
        .sorted()

    assertEquals(
      emptyList(),
      violations,
      "runtime-domain install/workflow source and runtime-application main source must reach schema validation " +
        "only through the domain-owned validator ports, never by importing a concrete " +
        "*SchemaValidator/*CoherenceValidator.",
    )
  }

  @Test
  fun `validator-import extraction strips aliases before applying the ban predicate`() {
    val aliasedImportSource =
      """
      import skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator as InstallValidator

      class Leaky
      """.trimIndent()
    val flagged = importedNames(aliasedImportSource).filter(::isSchemaOrCoherenceValidatorImport)
    assertEquals(
      listOf(
        "skillbill.infrastructure.contracts.install.InstallPlanSchemaValidator",
      ),
      flagged,
      "AC3 import extraction must strip ` as <alias>` so an aliased concrete validator import is still caught.",
    )
  }

  @Test
  fun `runtime-contracts main source does not declare concrete schema or coherence validators`() {
    val contractsMainRoot = moduleMainKotlinRoot("runtime-contracts")
    val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(contractsMainRoot)
    val violations =
      sourceFiles
        .flatMap { sourceFile ->
          concreteContractValidatorDeclarations(sourceFile.readText()).map { declaration ->
            "${runtimeRoot.relativize(sourceFile)} declares $declaration"
          }
        }
        .sorted()

    assertEquals(
      emptyList(),
      violations,
      "runtime-contracts main source must not declare new concrete schema/coherence validators under " +
        "skillbill.contracts.*. Put concrete validators behind the existing infra/domain-port ownership pattern.",
    )
  }

  @Test
  fun `runtime-contracts concrete-validator declaration scanner fires on synthetic fixture`() {
    val fixture =
      """
      package skillbill.contracts.workflow

      object NewSchemaValidator
      open class NewWorkflowCoherenceValidator
      interface ContractOnlySchemaValidator
      abstract class AbstractContractCoherenceValidator
      sealed class SealedContractSchemaValidator
      class NewCoherenceValidator
      """.trimIndent()

    assertEquals(
      listOf(
        "skillbill.contracts.workflow.NewSchemaValidator",
        "skillbill.contracts.workflow.NewWorkflowCoherenceValidator",
        "skillbill.contracts.workflow.NewCoherenceValidator",
      ),
      concreteContractValidatorDeclarations(fixture),
      "Concrete-validator declaration scanner must report new validator declarations under skillbill.contracts.*.",
    )
  }

  private fun bannedInlineReferences(
    source: String,
    forbiddenPrefixes: List<String>,
  ): List<String> =
    source.lineSequence()
      .filterNot { line ->
        val trimmed = line.trim()
        trimmed.startsWith("import ") ||
          trimmed.startsWith("package ") ||
          trimmed.startsWith("//") ||
          trimmed.startsWith("*") ||
          trimmed.startsWith("/*")
      }
      .map { line -> line.substringBefore("//") }
      .flatMap { line ->
        forbiddenPrefixes.filter { prefix -> Regex("""\b${Regex.escape(prefix)}\.""").containsMatchIn(line) }
      }
      .distinct()
      .toList()

  private fun importedNames(source: String): List<String> =
    IMPORT_PATTERN.findAll(source)
      .map { match -> match.groupValues[1].substringBefore(" as ").trim() }
      .toList()

  private fun concreteContractValidatorDeclarations(source: String): List<String> =
    PACKAGE_PATTERN.find(source)
      ?.groupValues
      ?.get(1)
      ?.takeIf { packageName -> packageName.startsWith("skillbill.contracts.") }
      ?.let { packageName ->
        topLevelDeclarationNames(source)
          .filter { declaration ->
            declaration.name.endsWith("SchemaValidator") || declaration.name.endsWith("CoherenceValidator")
          }
          .map { declaration -> "$packageName.${declaration.name}" }
      }
      .orEmpty()

  private fun topLevelDeclarationNames(source: String): List<KotlinDeclaration> {
    var braceDepth = 0
    var inBlockComment = false
    val declarations = mutableListOf<KotlinDeclaration>()
    source.lineSequence().forEach { rawLine ->
      val line = rawLine.withoutCommentText(inBlockComment)
      inBlockComment = line.inBlockComment
      if (braceDepth == 0) line.topLevelConcreteDeclaration()?.let(declarations::add)
      braceDepth += line.text.count { character -> character == '{' }
      braceDepth -= line.text.count { character -> character == '}' }
      if (braceDepth < 0) braceDepth = 0
    }
    return declarations
  }

  private fun String.withoutCommentText(startsInBlockComment: Boolean): SourceLine {
    var remaining = this
    var inBlockComment = startsInBlockComment
    val output = StringBuilder()
    while (remaining.isNotEmpty()) {
      if (inBlockComment) {
        val end = remaining.indexOf("*/")
        if (end == -1) return SourceLine(output.toString(), inBlockComment = true)
        remaining = remaining.drop(end + 2)
        inBlockComment = false
      } else {
        val lineComment = remaining.indexOf("//").takeUnless { index -> index == -1 } ?: remaining.length
        val blockComment = remaining.indexOf("/*").takeUnless { index -> index == -1 } ?: remaining.length
        when {
          lineComment < blockComment -> {
            output.append(remaining.take(lineComment))
            remaining = ""
          }
          blockComment < remaining.length -> {
            output.append(remaining.take(blockComment))
            remaining = remaining.drop(blockComment + 2)
            inBlockComment = true
          }
          else -> {
            output.append(remaining)
            remaining = ""
          }
        }
      }
    }
    return SourceLine(output.toString(), inBlockComment)
  }

  private fun SourceLine.topLevelConcreteDeclaration(): KotlinDeclaration? =
    TOP_LEVEL_DECLARATION_PATTERN.find(text)
      ?.toKotlinDeclaration()
      ?.takeIf(KotlinDeclaration::isConcrete)

  private data class SourceLine(val text: String, val inBlockComment: Boolean)

  private data class KotlinDeclaration(val modifiers: Set<String>, val kind: String, val name: String) {
    val isConcrete: Boolean
      get() = kind != "interface" && "abstract" !in modifiers && "sealed" !in modifiers
  }

  private companion object {
    val PACKAGE_PATTERN: Regex = Regex("""^\s*package\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
    val TOP_LEVEL_DECLARATION_PATTERN: Regex =
      Regex(
        """^\s*((?:(?:public|internal|private|protected|abstract|sealed|open|final|data|enum|value|fun)\s+)*)""" +
          """(class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
      )

    fun MatchResult.toKotlinDeclaration(): KotlinDeclaration =
      KotlinDeclaration(
        modifiers = groupValues[1].split(Regex("""\s+""")).filter { modifier -> modifier.isNotBlank() }.toSet(),
        kind = groupValues[2],
        name = groupValues[3],
      )

    val IMPORT_PATTERN: Regex = Regex("""^import\s+([A-Za-z0-9_.*]+)""", RegexOption.MULTILINE)

    val SOURCE_TEXT_LAYER_RULES: Map<String, List<String>> =
      mapOf(
        moduleMainKotlinRootRelative("runtime-application") to
          listOf(
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
          ),
        moduleMainKotlinRootRelative("runtime-domain") to
          listOf(
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
          ),
        moduleMainKotlinRootRelative("runtime-ports") to
          listOf(
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
          ),
      )
  }
}
