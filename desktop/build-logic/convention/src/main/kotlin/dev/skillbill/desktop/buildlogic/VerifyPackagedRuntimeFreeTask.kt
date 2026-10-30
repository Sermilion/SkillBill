package dev.skillbill.desktop.buildlogic

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification produces no output worth caching.")
abstract class VerifyPackagedRuntimeFreeTask : DefaultTask() {
  @get:InputDirectory
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val imageDirectory: DirectoryProperty

  @get:Input
  abstract val runtimeModuleNames: ListProperty<String>

  @TaskAction
  fun verify() {
    val offenders = packagedRuntimeJars(imageDirectory.get().asFile, runtimeModuleNames.get().toSet())
    if (offenders.isNotEmpty()) {
      throw GradleException("The desktop image contains Skill Bill runtime module jars:\n${offenders.joinToString("\n")}")
    }
  }
}

internal fun packagedRuntimeJars(image: File, modules: Set<String>): List<String> = image.walkTopDown()
  .filter { it.isFile && it.extension == "jar" && it.parentFile.name == "app" }
  .filter { jar ->
    val name = jar.name.removeSuffix(".jar").replace(Regex("-[0-9].*$"), "")
    name in modules || ZipFile(jar).use { archive ->
      archive.entries().asSequence().any { it.name.startsWith("skillbill/") && it.name.endsWith(".class") }
    }
  }
  .map { it.relativeTo(image).invariantSeparatorsPath }
  .sorted()
  .toList()
