package dev.skillbill.tray.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType

private const val RUNTIME_DISTRIBUTION_MODULE = "dev.skillbill:runtime-cli"
private const val FIRST_PARTY_GROUP = "dev.skillbill"

/**
 * Fails when the desktop resolves a third-party library at a different version than the runtime resolves it on its own. The
 * runtime graph is resolved apart from the desktop's, so conflict resolution inside the desktop graph cannot hide a drift.
 */
@DisableCachingByDefault(because = "Verification produces no output worth caching.")
abstract class VerifySharedRuntimeVersionsTask : DefaultTask() {
  @get:Input
  abstract val runtimeVersions: MapProperty<String, String>

  @get:Input
  abstract val desktopVersions: MapProperty<String, String>

  @TaskAction
  fun verify() {
    val desktop = desktopVersions.get()
    val differing = runtimeVersions.get().mapNotNull { (module, version) ->
      desktop[module]?.takeIf { it != version }?.let { "$module: runtime $version, desktop $it" }
    }.sorted()
    if (differing.isNotEmpty()) {
      throw GradleException(
        "The desktop resolves shared libraries at other versions than the runtime:\n" +
          differing.joinToString("\n") { "  $it" } +
          "\nAlign desktop-tray/gradle/libs.versions.toml with runtime-kotlin/gradle/libs.versions.toml.",
      )
    }
  }
}

internal fun Project.registerSharedRuntimeVersionCheck() {
  val runtime = configurations.detachedConfiguration(dependencies.create(RUNTIME_DISTRIBUTION_MODULE)).apply {
    attributes.attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
    attributes.attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
    attributes.attribute(
      TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
      objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM),
    )
    attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
  }
  val verification = tasks.register<VerifySharedRuntimeVersionsTask>("verifySharedRuntimeVersions") {
    group = "verification"
    description = "Verifies the desktop resolves every library it shares with the runtime at the runtime's version."
    runtimeVersions.set(runtime.incoming.resolutionResult.rootComponent.map { it.moduleVersions() })
    desktopVersions.set(
      configurations.named("jvmRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }.map { it.moduleVersions() },
    )
  }
  tasks.named("check") { dependsOn(verification) }
  tasks.matching { it.name == "createDistributable" }.configureEach { dependsOn(verification) }
}

private fun ResolvedComponentResult.moduleVersions(): Map<String, String> {
  val seen = linkedSetOf<ResolvedComponentResult>()
  val pending = ArrayDeque(listOf(this))
  while (pending.isNotEmpty()) {
    val component = pending.removeFirst()
    if (!seen.add(component)) continue
    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.add(it.selected) }
  }
  return seen.mapNotNull { component -> (component.id as? ModuleComponentIdentifier)?.takeIf { it.group != FIRST_PARTY_GROUP } }
    .associate { id -> "${id.group}:${id.module}" to id.version }
}
