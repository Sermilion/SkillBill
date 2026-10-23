package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecompositionManifestArchitectureTest {
  private val runtimeRoot: Path = ArchitectureScanSupport.runtimeRoot
  private val infraWorkflowModule = RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:workflow")
  private val domainDecompositionManifestRuntimeSeamTokens =
    listOf(
      "DecompositionManifestSchemaValidator",
      "validateYamlText",
      "YAMLMapper",
      "readTree(",
      "readValue(",
      "writeValueAsString(",
      "fun encodeYaml",
      "fun decodeYaml",
    )

  @Test
  fun `decomposition manifest schema validation stays at application seams`() {
    val applicationSeam =
      Files.readString(
        runtimeRoot.resolve(
          "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/decomposition/" +
            "DecompositionManifestFileWrites.kt",
        ),
      )

    assertContains(applicationSeam, "validator: DecompositionManifestValidator")
    assertContains(applicationSeam, "validator.validateYamlText")
    assertContains(applicationSeam, "validator.encodeManifestWireMap")
    assertFalse(applicationSeam.contains("validator.validate("))
    assertFalse(applicationSeam.contains("DecompositionManifestWireCodec.decode"))
    assertFalse(applicationSeam.contains("fun encodeDecompositionManifestMap"))

    assertFalse(
      applicationSeam.contains("YAMLMapper"),
      "Application seam must not own the YAML serializer; it now flows through the file-store port.",
    )
    assertContains(applicationSeam, "fileStore.encodeManifestYaml")
    assertFalse(applicationSeam.contains("DecompositionManifestWireCodec.encodeYaml"))
    assertFalse(
      applicationSeam.contains("DecompositionManifestSchemaValidator"),
      "Application seam must not reference the concrete schema validator directly.",
    )

    val infraStoreSeam =
      Files.readString(
        runtimeRoot.resolve(
          "$infraWorkflowModule/src/main/kotlin/skillbill/infrastructure/workflow/decomposition/" +
            "FileSystemDecompositionManifestFileStore.kt",
        ),
      )
    assertContains(infraStoreSeam, "YAMLMapper")
    assertContains(infraStoreSeam, "writeValueAsString")
  }

  @Test
  fun `domain workflow code does not own decomposition manifest schema or YAML seams`() {
    val domainWorkflowRoot =
      runtimeRoot.resolve(
        "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow",
      )

    val validatorPortFile =
      domainWorkflowRoot.resolve("decomposition/DecompositionManifestValidator.kt").normalize()
    val violations =
      Files.walk(domainWorkflowRoot).use { paths ->
        paths
          .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
          .filter { path -> path.normalize() != validatorPortFile }
          .flatMap { path ->
            val text = Files.readString(path)
            val tokens = domainDecompositionManifestRuntimeSeamTokens.filter { token -> text.contains(token) }
            tokens
              .map { token -> "${runtimeRoot.relativize(path)} contains $token" }
              .stream()
          }
          .toList()
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `application decomposition runtime artifact emission uses validated seam`() {
    val applicationRoot =
      runtimeRoot.resolve(
        "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application",
      )
    val allowedRawWireMapFile = applicationRoot.resolve("decomposition/DecompositionManifestFileWrites.kt").normalize()
    val violations =
      Files.walk(applicationRoot).use { paths ->
        paths
          .filter { path -> path.isRegularFile() && path.toString().endsWith(".kt") }
          .filter { path -> path.normalize() != allowedRawWireMapFile }
          .flatMap { path ->
            val text = Files.readString(path)
            buildList {
              if (text.contains(".toWireMap()") || text.contains("toWireMap(")) {
                add("${runtimeRoot.relativize(path)} calls toWireMap directly")
              }
              decompositionRuntimeEmissionPatterns.forEach { pattern ->
                pattern.findAll(text).forEach { match ->
                  val emissionExpression = match.value
                  if (!emissionExpression.contains("encodeManifestWireMap")) {
                    add("${runtimeRoot.relativize(path)} emits decomposition_runtime without validated map seam")
                  }
                }
              }
            }.stream()
          }
          .toList()
      }

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  private companion object {
    val decompositionRuntimeEmissionPatterns =
      listOf(
        Regex("""DECOMPOSITION_RUNTIME_ARTIFACT_KEY\s+to[\s\S]*?(?=,\s*(?:"|\w+\s+to)|\n\s*\)|\z)"""),
        Regex("""put\(\s*DECOMPOSITION_RUNTIME_ARTIFACT_KEY[\s\S]*?\)"""),
      )
  }
}
