package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeRawMapArchitectureTest {
  @Test
  fun `domain artifact keys stay internal and artifact maps stay behind domain accessors`() {
    val sources = declaredMainSourceFiles()
    val domainSources =
      sources.filter { it.relativePath.startsWith("runtime-kotlin/runtime-domain/") }
    val keyPattern =
      Regex("(?s)const\\s+val\\s+([A-Z0-9_]*_ARTIFACT_KEY)\\b[^=]*=\\s*\"([^\"]+)\"")
    val domainKeys =
      domainSources
        .flatMap { file ->
          keyPattern.findAll(file.source).map { match -> match.groupValues[1] to match.groupValues[2] }.toList()
        }
    val publicKeyDeclarations =
      domainSources
        .flatMap { file ->
          Regex("""(?m)^\s*(?!internal\s+)const\s+val\s+[A-Z0-9_]*_ARTIFACT_KEY\b""")
            .findAll(file.source)
            .map { "${file.relativePath}:${it.range.first}" }
            .toList()
        }
    val consumers =
      sources
        .filterNot { it.relativePath.startsWith("runtime-kotlin/runtime-domain/") }
        .flatMap { file ->
          file.source.lineSequence().withIndex().filter { (_, line) ->
            domainKeys.any { (name, value) ->
              name in line ||
                Regex("""\[\s*"$value"\s*]""").containsMatchIn(line) ||
                Regex("""\.\s*(get|containsKey)\(\s*"$value"\s*\)""").containsMatchIn(line) ||
                Regex("(?:put|remove)\\(\\s*\"$value\"").containsMatchIn(line) ||
                Regex("""["']$value["']\s+to""").containsMatchIn(line)
            }
          }.map { (index, _) -> "${file.relativePath}:${index + 1}" }.toList()
        }
    assertEquals(emptyList(), publicKeyDeclarations)
    assertEquals(emptyList(), consumers)
  }

  @Test
  fun `runtime architecture forbids public raw map shapes in inner layers`() {
    val violations =
      listOf("runtime-application", "runtime-domain", "runtime-ports")
        .flatMap { moduleName -> rawMapViolationsUnder(moduleMainKotlinRoot(moduleName)) }
    assertTrue(
      violations.isEmpty(),
      "Public application/domain/port declarations must not use raw Map<String, Any?> " +
        "shapes or be typed exactly Any. Contain maps in private or adapter-only serializers, " +
        "or replace them with typed models.\n" +
        "Violations:\n" + violations.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `inner-layer raw-map scanner rejects synthetic public map in application main source`() {
    val root = Files.createTempDirectory("raw-map-fixture")
    val sourceRoot = root.resolve("runtime-application/src/main/kotlin")
    Files.createDirectories(sourceRoot.resolve("skillbill/application/fixture"))
    val sourceFile = sourceRoot.resolve("skillbill/application/fixture/SyntheticLeak.kt")
    Files.writeString(
      sourceFile,
      """

      class SyntheticLeak {
        fun payload(): Map<String, Any?> = emptyMap()
      }
      """.trimIndent(),
    )
    val violations = rawMapViolationsUnder(sourceRoot)
    assertEquals(1, violations.size)
    assertTrue(
      violations.single().contains("SyntheticLeak.kt") &&
        violations.single().contains("payload") &&
        violations.single().contains(sourceRoot.toString()),
      "The inner-layer scanner must report the public raw-map declaration it read from the fixture root.",
    )
  }

  @Test
  fun `inner-layer raw-map scanner rejects synthetic public Any-typed declarations in domain main source`() {
    val root = Files.createTempDirectory("exact-any-fixture")
    val sourceRoot = root.resolve("runtime-domain/src/main/kotlin")
    Files.createDirectories(sourceRoot.resolve("skillbill/workflow/fixture"))
    Files.writeString(
      sourceRoot.resolve("skillbill/workflow/fixture/SyntheticAnyLeak.kt"),
      """
      class SyntheticAnyLeak {
        val artifacts: Any
        private val hidden: Any
        fun wire(): Any = Unit
        internal fun internalWire(): Any = Unit
        fun nullable(): Any? = null
        fun decode(raw: Any): Int = 0
      }
      """.trimIndent(),
    )
    val violations = rawMapViolationsUnder(sourceRoot)
    assertTrue(
      violations.all { it.contains("SyntheticAnyLeak.kt") },
      "The exact-Any rule must report declarations read from the fixture file.",
    )
    val violatingNames = violations.map { it.substringAfter("public `").substringBefore('`') }
    assertEquals(
      listOf("artifacts", "wire"),
      violatingNames.sorted(),
      "Only public declarations typed exactly Any may be reported.",
    )
  }

  @Test
  fun `ports main path disables suffix boundary-carrier exemption for FooMap`() {
    val fixture =
      SourceFile(
        relativePath =
          "${moduleMainKotlinRootRelative("runtime-ports")}/skillbill/ports/fixture/FooMap.kt",
        packageName = "skillbill.ports.fixture",
        imports = emptyList(),
        source =
          """

          class FooMap(private val delegate: Map<String, Any?>) : Map<String, Any?> by delegate
          """.trimIndent(),
      )
    val violations = findRawMapViolations(fixture)
    assertEquals(
      listOf(
        "${moduleMainKotlinRootRelative("runtime-ports")}/skillbill/ports/fixture/FooMap.kt:2: " +
          "public `FooMap` exposes raw map shape",
      ),
      violations,
    )
  }

  @Test
  fun `raw map violation scanner fires on known violation fixtures`() {
    val fixture =
      SourceFile(
        relativePath = "test-fixture/Fake.kt",
        packageName = "skillbill.application",
        imports = emptyList(),
        source = rawMapViolationFixtureSource(),
      )
    val violations = findRawMapViolations(fixture)
    val violatingNames = violations.map { it.substringAfter("public `").substringBefore('`') }
    assertEquals(
      expectedRawMapViolationFixtureNames(),
      violatingNames.sorted(),
    )
  }
}
