package dev.skillbill.tray.buildlogic

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

internal class ConventionFixture(private val root: File, modules: Map<String, String>) {
  init {
    val catalog = File(requireNotNull(System.getProperty("skillbill.desktop.catalog")) { "The desktop catalog property is not set." })
    write(
      "settings.gradle.kts",
      """
      pluginManagement { repositories { gradlePluginPortal(); google(); mavenCentral() } }
      plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
      dependencyResolutionManagement {
        repositories { google(); mavenCentral() }
        versionCatalogs { create("libs") { from(files("${catalog.invariantSeparatorsPath}")) } }
      }
      rootProject.name = "fixture"
      include(${modules.keys.joinToString { "\":$it\"" }})
      """.trimIndent(),
    )
    modules.forEach { (name, build) -> write("$name/build.gradle.kts", build.trimIndent()) }
  }

  fun write(path: String, text: String) {
    root.resolve(path).apply { parentFile.mkdirs() }.writeText(text + "\n")
  }

  fun file(path: String): File = root.resolve(path)

  fun run(vararg arguments: String): BuildResult = runner(arguments).build()

  fun runAndFail(vararg arguments: String): BuildResult = runner(arguments).buildAndFail()

  fun manifest(module: String): String = file("$module/build/architecture/manifest.json").readText()

  private fun runner(arguments: Array<out String>): GradleRunner = GradleRunner.create()
    .withProjectDir(root)
    .withPluginClasspath()
    .withArguments(*arguments, "--stacktrace")
}

internal fun manifestStrings(manifest: String, key: String): List<String> {
  val array = requireNotNull(Regex("\"$key\":\\[(.*?)]").find(manifest)) { "Manifest has no '$key' array." }.groupValues[1]
  return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(array).map { it.groupValues[1] }.toList()
}
