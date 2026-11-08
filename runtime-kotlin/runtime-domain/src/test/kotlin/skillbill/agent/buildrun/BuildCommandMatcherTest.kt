package skillbill.agent.buildrun

import skillbill.agent.model.BuildMatch
import skillbill.agent.model.BuildMatchContext
import skillbill.agent.model.BuildProcessSample
import skillbill.agent.model.BuildRepositoryFiles
import skillbill.model.FileLocation
import skillbill.scaffold.model.BuildToolDeclaration
import skillbill.scaffold.model.BuildToolVersionSource
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.RoutingSignals
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildCommandMatcherTest {
  private val root = "/repo"
  private val gradle =
    BuildToolDeclaration(
      executable = "gradlew",
      displayExecutable = "./gradlew",
      tool = "Gradle",
      projectKind = "Android",
      processMarkers = listOf("org.gradle.wrapper.GradleWrapperMain"),
      projectMarkers = listOf("app/src/main/AndroidManifest.xml"),
      shortSkip = listOf("clean"),
      versionSource = BuildToolVersionSource.GRADLE_WRAPPER,
    )
  private val packs =
    listOf(
      pack("kmp", gradle),
      pack("kotlin", gradle.copy(projectKind = "Kotlin/JVM", projectMarkers = emptyList())),
      pack(
        "typescript",
        nodeTool("npm", "npm-cli.js"),
        nodeTool("pnpm", "pnpm.cjs", "pnpm.mjs", "pnpm.js"),
      ),
      pack("go", tool("go", "Go", "Go").copy(versionSource = BuildToolVersionSource.GO_MOD)),
      pack("rust", tool("cargo", "Cargo", "Rust")),
      pack("ios", tool("xcodebuild", "Xcode", "iOS").copy(shortSubcommand = false)),
    )

  @Test
  fun `gradle wrapper java form picks kotlin without a kmp marker and kmp with one`() {
    val files = FakeFiles(mapOf("gradle/wrapper/gradle-wrapper.properties" to WRAPPER_PROPERTIES))
    val sample =
      sample(1, null, "/usr/bin/java", "-Xmx64m", "org.gradle.wrapper.GradleWrapperMain", ":runtime-engine:test")

    val kotlin = matcher(files).match(listOf(sample)).single()
    assertEquals("./gradlew :runtime-engine:test", kotlin.displayCommand)
    assertEquals("gradlew test", kotlin.shortCommand)
    assertEquals("Kotlin/JVM", kotlin.projectKind)
    assertEquals("Gradle 8.10", kotlin.toolLabel)

    val withMarker = FakeFiles(files.texts + ("app/src/main/AndroidManifest.xml" to "<manifest/>"))
    assertEquals("Android", matcher(withMarker).match(listOf(sample)).single().projectKind)
  }

  @Test
  fun `node script forms match by process marker and read the package manager major`() {
    val files = FakeFiles(mapOf("package.json" to """{ "packageManager": "pnpm@9.12.0" }"""))
    val matches =
      matcher(files).match(
        listOf(
          sample(1, null, "/usr/bin/node", "/usr/lib/node_modules/npm/bin/npm-cli.js", "run", "lint"),
          sample(2, null, "/usr/bin/node", "/opt/pnpm/bin/pnpm.cjs", "run", "build"),
        ),
      )

    assertEquals(listOf("npm run lint", "pnpm run build"), matches.map(BuildMatch::displayCommand))
    assertEquals(listOf("npm lint", "pnpm build"), matches.map(BuildMatch::shortCommand))
    assertEquals(listOf("npm", "pnpm 9"), matches.map(BuildMatch::toolLabel))
  }

  @Test
  fun `only the outermost non-shell match counts and a bare sample shows the executable`() {
    val matches =
      matcher(FakeFiles(emptyMap())).match(
        listOf(
          sample(10, null, "/bin/sh", "-c", "go test ./... && echo done"),
          sample(11, 10, "/usr/local/go/bin/go", "test", "./..."),
          sample(12, 11, "/usr/local/go/pkg/tool/go", "vet", "./..."),
          sample(20, null, "/usr/bin/cargo"),
        ),
      )

    assertEquals(listOf(11L, 20L), matches.map(BuildMatch::pid))
    assertEquals(listOf("go test ./...", "cargo"), matches.map(BuildMatch::displayCommand))
  }

  @Test
  fun `short commands follow the subcommand rules`() {
    val cases =
      mapOf(
        listOf("./gradlew", ":app:assembleDebug") to "gradlew assembleDebug",
        listOf("./gradlew", "clean", "check", "createDistributable", "--no-build-cache") to "gradlew check",
        listOf("pnpm", "run", "build") to "pnpm build",
        listOf("go", "test", "./...") to "go test",
        listOf("cargo", "build", "--release") to "cargo build",
        listOf("xcodebuild", "-scheme", "Newsreader", "build") to "xcodebuild",
      )
    val matcher = matcher(FakeFiles(emptyMap()))

    cases.forEach { (command, expected) ->
      val match = matcher.match(listOf(sample(1, null, command.first(), *command.drop(1).toTypedArray()))).single()
      assertEquals(expected, match.shortCommand, "command=$command")
    }
  }

  private fun tool(
    executable: String,
    label: String,
    kind: String,
  ) = BuildToolDeclaration(executable = executable, displayExecutable = executable, tool = label, projectKind = kind)

  private fun nodeTool(
    executable: String,
    vararg markers: String,
  ) = tool(executable, executable, "TypeScript").copy(
    processMarkers = markers.toList(),
    shortSkip = listOf("run"),
    versionSource = BuildToolVersionSource.PACKAGE_MANAGER,
  )

  private fun matcher(files: FakeFiles) = BuildCommandMatcher(packs, BuildMatchContext(root, null), files)

  private fun sample(
    pid: Long,
    parentPid: Long?,
    command: String,
    vararg arguments: String,
  ) = BuildProcessSample(pid, parentPid, command, arguments.toList(), null)

  private fun pack(
    slug: String,
    vararg tools: BuildToolDeclaration,
  ) = PlatformManifest(
    slug = slug,
    packRoot = FileLocation("/packs/$slug"),
    contractVersion = "1.8",
    routingSignals = RoutingSignals(strong = emptyList(), tieBreakers = emptyList()),
    declaredCodeReviewAreas = emptyList(),
    declaredFiles = DeclaredFiles(null, emptyMap()),
    areaMetadata = emptyMap(),
    buildTools = tools.toList(),
  )

  private inner class FakeFiles(val texts: Map<String, String>) : BuildRepositoryFiles {
    override fun exists(path: String): Boolean = path.removePrefix("$root/") in texts

    override fun readText(path: String): String? = texts[path.removePrefix("$root/")]
  }

  private companion object {
    const val WRAPPER_PROPERTIES =
      "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n"
  }
}
