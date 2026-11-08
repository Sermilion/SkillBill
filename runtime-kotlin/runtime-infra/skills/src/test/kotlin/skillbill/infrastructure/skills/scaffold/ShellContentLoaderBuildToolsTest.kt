package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.parseBuildTools
import skillbill.scaffold.model.BuildToolDeclaration
import skillbill.scaffold.model.BuildToolVersionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShellContentLoaderBuildToolsTest {
  @Test
  fun `a declared build_tools block parses with defaults and an absent one is empty`() {
    assertEquals(emptyList(), parseBuildTools(mapOf("platform" to "go"), "go"))

    val parsed =
      parseBuildTools(
        mapOf(
          "build_tools" to
            listOf(
              mapOf(
                "executable" to "gradlew",
                "display_executable" to "./gradlew",
                "tool" to "Gradle",
                "project_kind" to "Android",
                "process_markers" to listOf("org.gradle.wrapper.GradleWrapperMain"),
                "project_markers" to listOf("app/src/main/AndroidManifest.xml"),
                "short_skip" to listOf("clean"),
                "version_source" to "gradle_wrapper",
              ),
              mapOf(
                "executable" to "xcodebuild",
                "tool" to "Xcode",
                "project_kind" to "iOS",
                "short_subcommand" to false,
              ),
            ),
        ),
        "kmp",
      )

    assertEquals(
      listOf(
        BuildToolDeclaration(
          executable = "gradlew",
          displayExecutable = "./gradlew",
          tool = "Gradle",
          projectKind = "Android",
          processMarkers = listOf("org.gradle.wrapper.GradleWrapperMain"),
          projectMarkers = listOf("app/src/main/AndroidManifest.xml"),
          shortSkip = listOf("clean"),
          shortSubcommand = true,
          versionSource = BuildToolVersionSource.GRADLE_WRAPPER,
        ),
        BuildToolDeclaration(
          executable = "xcodebuild",
          displayExecutable = "xcodebuild",
          tool = "Xcode",
          projectKind = "iOS",
          shortSubcommand = false,
        ),
      ),
      parsed,
    )
  }

  @Test
  fun `malformed build_tools entries fail with the manifest schema error`() {
    val valid = mapOf("executable" to "gradlew", "tool" to "Gradle", "project_kind" to "Kotlin/JVM")
    val malformed =
      listOf(
        listOf(valid, valid),
        listOf(valid + ("version_source" to "maven_wrapper")),
        listOf(valid + ("project_markers" to listOf("/etc/passwd"))),
        listOf(valid + ("project_markers" to listOf("app/../../secret"))),
        listOf(valid + ("tool" to " ")),
      )

    malformed.forEach { entries ->
      val error =
        assertFailsWith<SkillBillRuntimeException> { parseBuildTools(mapOf("build_tools" to entries), "kotlin") }
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, error.code, "entries=$entries")
    }
  }
}
