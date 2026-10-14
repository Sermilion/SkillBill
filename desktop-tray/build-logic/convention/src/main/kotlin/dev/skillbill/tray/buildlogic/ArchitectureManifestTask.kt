package dev.skillbill.tray.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier

abstract class ArchitectureManifestTask : DefaultTask() {
  @get:Input abstract val projectPath: Property<String>
  @get:Input abstract val plugins: ListProperty<String>
  @get:Input abstract val pluginClasses: ListProperty<String>
  @get:Input abstract val targets: ListProperty<String>
  @get:Input abstract val kspConfigurations: ListProperty<String>
  @get:Input abstract val sourceSets: ListProperty<String>
  @get:Input abstract val sourceDirectories: MapProperty<String, List<String>>
  @get:Input abstract val projectDependencies: ListProperty<String>
  @get:Input abstract val resolved: MapProperty<String, List<String>>
  @get:OutputFile abstract val manifest: RegularFileProperty

  @TaskAction
  fun write() {
    val document = mapOf(
      "project" to projectPath.get(),
      "plugins" to plugins.get().sorted(),
      "pluginClasses" to pluginClasses.get().sorted(),
      "targets" to targets.get().sorted(),
      "kspConfigurations" to kspConfigurations.get().sorted(),
      "sourceSets" to sourceSets.get().sorted(),
      "sourceDirectories" to sourceDirectories.get(),
      "projectDependencies" to projectDependencies.get().sorted(),
      "resolved" to resolved.get(),
    )
    manifest.get().asFile.apply { parentFile.mkdirs() }.writeText(renderJson(document) + "\n")
  }
}

internal fun ResolvedComponentResult.flattenCoordinates(): List<String> {
  val seen = linkedSetOf<ResolvedComponentResult>()
  val pending = ArrayDeque(listOf(this))
  while (pending.isNotEmpty()) {
    val component = pending.removeFirst()
    if (!seen.add(component)) continue
    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.add(it.selected) }
  }
  return seen.drop(1).map { component ->
    when (val id = component.id) {
      is ProjectComponentIdentifier -> "project:${id.projectPath}"
      is ModuleComponentIdentifier -> "${id.group}:${id.module}"
      else -> id.displayName
    }
  }.distinct().sorted()
}
