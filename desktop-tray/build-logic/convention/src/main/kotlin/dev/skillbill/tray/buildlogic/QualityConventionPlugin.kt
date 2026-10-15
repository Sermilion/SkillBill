package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.File
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.attributes.Usage
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

const val ARCHITECTURE_MANIFEST_USAGE = "skillbill-architecture-manifest"
const val ARCHITECTURE_MANIFESTS_PROPERTY = "skillbill.architecture.manifests"

private val KNOWN_PLUGIN_IDS = listOf(
  "org.jetbrains.kotlin.multiplatform",
  "org.jetbrains.kotlin.jvm",
  "org.jetbrains.kotlin.plugin.compose",
  "org.jetbrains.kotlin.plugin.serialization",
  "org.jetbrains.compose",
  "com.google.devtools.ksp",
  "skillbill.kmp.library",
  "skillbill.kmp.compose",
  "skillbill.feature",
  "skillbill.kotlin.inject",
  "skillbill.kotlin.serialization",
  "skillbill.room",
  "skillbill.desktop.application",
)

private val RESOLVED_CONFIGURATIONS = listOf(
  "jvmCompileClasspath",
  "jvmRuntimeClasspath",
  "jvmTestCompileClasspath",
  "jvmTestRuntimeClasspath",
)

internal class ManifestArguments(
  @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val manifests: FileCollection,
) : CommandLineArgumentProvider {
  override fun asArguments(): Iterable<String> =
    listOf("-D$ARCHITECTURE_MANIFESTS_PROPERTY=${manifests.files.joinToString(File.pathSeparator) { it.absolutePath }}")
}

class QualityConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    val manifestUsage = objects.named(Usage::class.java, ARCHITECTURE_MANIFEST_USAGE)
    val manifestTask = tasks.register<ArchitectureManifestTask>("writeArchitectureManifest") {
      group = "verification"
      description = "Writes this module's Gradle architecture manifest for the architecture guards."
      // Qualified: inside this block a bare `path` is the task's path.
      projectPath.set(target.path)
      manifest.set(layout.buildDirectory.file("architecture/manifest.json"))
    }
    configurations.consumable("architectureManifestElements") {
      attributes.attribute(Usage.USAGE_ATTRIBUTE, manifestUsage)
      outgoing.artifact(manifestTask.flatMap { it.manifest })
    }
    // Gradle declares dependencies on a dependency-scope configuration and resolves them through one that extends it.
    val manifestDependencies = configurations.dependencyScope("architectureManifests")
    val manifests = configurations.resolvable("architectureManifestFiles") {
      extendsFrom(manifestDependencies.get())
      attributes.attribute(Usage.USAGE_ATTRIBUTE, manifestUsage)
    }
    tasks.withType(Test::class.java).configureEach {
      jvmArgumentProviders += ManifestArguments(files(manifests))
    }
    afterEvaluate { describeProject(manifestTask.get()) }
  }

  private fun Project.describeProject(task: ArchitectureManifestTask) {
    task.plugins.set(KNOWN_PLUGIN_IDS.filter { pluginManager.hasPlugin(it) })
    task.pluginClasses.set(plugins.map { it.javaClass.name })
    task.kspConfigurations.set(configurations.names.filter { it.startsWith("ksp") })
    task.projectDependencies.set(
      configurations.flatMap { configuration ->
        configuration.dependencies.filterIsInstance<ProjectDependency>().map { "${configuration.name}->${it.path}" }
      }.distinct(),
    )
    extensions.findByType(KotlinMultiplatformExtension::class.java)?.let { kotlin ->
      task.targets.set(kotlin.targets.map { "${it.name}:${it.platformType.name}" })
      task.sourceSets.set(kotlin.sourceSets.names)
      val desktopRoot = rootProject.projectDir
      val buildDir = layout.buildDirectory.get().asFile
      // Generated output (KSP, Room, Compose resources) is listed apart: it is produced from governed sources and would add bogus
      // roots to the call graph, so the guards only resolve edges into it.
      fun directories(generated: Boolean) = provider {
        kotlin.sourceSets.associate { sourceSet ->
          sourceSet.name to sourceSet.kotlin.srcDirs
            .filter { it.startsWith(buildDir) == generated }
            .map { it.relativeTo(desktopRoot).invariantSeparatorsPath }
            .sorted()
        }
      }
      task.sourceDirectories.set(directories(generated = false))
      task.generatedDirectories.set(directories(generated = true))
    }
    RESOLVED_CONFIGURATIONS.forEach { name ->
      configurations.findByName(name)?.let { configuration ->
        task.resolved.put(name, configuration.incoming.resolutionResult.rootComponent.map { it.flattenCoordinates() })
      }
    }
  }
}
