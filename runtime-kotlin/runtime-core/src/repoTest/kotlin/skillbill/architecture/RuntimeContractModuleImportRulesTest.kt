package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RuntimeContractModuleImportRulesTest {
  @Test
  fun `runtime-domain imports no serialization or filesystem library`() {
    assertEquals(
      emptyList(),
      forbiddenImportViolations("runtime-domain", DOMAIN_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-domain must reach a wire format or a filesystem only through the types its callers hand it.",
    )
  }

  @Test
  fun `runtime-ports imports no adapter machinery`() {
    assertEquals(
      emptyList(),
      forbiddenImportViolations("runtime-ports", PORTS_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-ports declares interfaces and DTOs; an adapter import means behaviour landed in the port.",
    )
  }

  @Test
  fun `scanner rejects the imports each module bans and accepts the neighbours it allows`() {
    val source =
      """
      package skillbill.model
      import java.time.Instant
      import kotlin.text.Regex
      import java.nio.file.Files
      import java.nio.file.Path
      """.trimIndent()

    assertEquals(
      emptyList(),
      forbiddenImportsIn(
        "Domain.kt",
        """
        package skillbill.model
        import java.time.Instant
        import kotlin.text.Regex
        """.trimIndent(),
        DOMAIN_FORBIDDEN_IMPORT_PREFIXES,
      ),
      "Domain must accept imports from allowed neighbour packages.",
    )

    assertEquals(
      listOf("Domain.kt: import java.nio.file.Files", "Domain.kt: import java.nio.file.Path"),
      forbiddenImportsIn("Domain.kt", source, DOMAIN_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-domain bans every java.nio import, not just the charset corner of it.",
    )
    assertEquals(
      listOf("Ports.kt: import java.nio.file.Files"),
      forbiddenImportsIn("Ports.kt", source, PORTS_FORBIDDEN_IMPORT_PREFIXES),
      "runtime-ports may still name a Path in a DTO; only filesystem access is banned there.",
    )
  }

  @Test
  fun `shared scanner ignores indented package and import text`() {
    val source =
      """
      package fixture.real
      import java.time.Instant
        package fixture.indented
        import java.nio.file.Files
      """.trimIndent()

    assertEquals("fixture.real", ArchitectureScanSupport.declaredPackage(source))
    assertEquals(listOf("java.time.Instant"), ArchitectureScanSupport.declaredImports(source))
    assertNull(ArchitectureScanSupport.declaredPackage("  package fixture.indented"))
    assertEquals(emptyList(), ArchitectureScanSupport.declaredImports("  import java.nio.file.Files"))
  }

  private fun forbiddenImportViolations(
    module: String,
    forbiddenPrefixes: List<String>,
  ): List<String> {
    val root = moduleMainKotlinRoot(module)
    val sourceFiles = kotlinFilesUnderWithArchitectureAsserts(root)
    return sourceFiles.flatMap { path ->
      forbiddenImportsIn(path.fileName.toString(), Files.readString(path), forbiddenPrefixes)
    }.sorted()
  }

  private fun forbiddenImportsIn(
    fileName: String,
    source: String,
    forbiddenPrefixes: List<String>,
  ): List<String> =
    source.lineSequence()
      .filter { line -> line.startsWith("import ") }
      .filter { line -> forbiddenPrefixes.any { prefix -> line.removePrefix("import ").startsWith(prefix) } }
      .map { line -> "$fileName: ${line.trim()}" }
      .toList()

  private companion object {
    val DOMAIN_FORBIDDEN_IMPORT_PREFIXES =
      listOf(
        "com.fasterxml.",
        "java.io.",
        "java.nio.",
        "kotlinx.serialization.",
        "org.yaml.",
      )
    val PORTS_FORBIDDEN_IMPORT_PREFIXES =
      listOf(
        "java.io.",
        "java.nio.file.Files",
        "kotlinx.serialization.",
        "me.tatarka.inject.",
        "org.yaml.",
      )
  }
}
