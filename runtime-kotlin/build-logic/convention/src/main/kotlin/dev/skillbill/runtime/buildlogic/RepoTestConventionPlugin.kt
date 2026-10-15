package dev.skillbill.runtime.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class RepoTestConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      val repoTestRoot = layout.projectDirectory.dir("src/repoTest/kotlin").asFile
      if (!repoTestRoot.isDirectory) {
        throw GradleException(
          "skillbill.repo-test is applied to $path but has no repository-contract sources at " +
            "$repoTestRoot. Add the source tree or remove the plugin from that module.",
        )
      }
      configureRepoTestSourceSet()
    }
  }
}

private fun Project.configureRepoTestSourceSet() {
  val java = extensions.getByType(JavaPluginExtension::class.java)
  val repoTestSourceSet = java.sourceSets.create("repoTest")
  val testSourceSet = java.sourceSets.getByName("test")

  configurations.getByName(repoTestSourceSet.implementationConfigurationName)
    .extendsFrom(configurations.getByName(testSourceSet.implementationConfigurationName))
  configurations.getByName(repoTestSourceSet.runtimeOnlyConfigurationName)
    .extendsFrom(configurations.getByName(testSourceSet.runtimeOnlyConfigurationName))

  extensions.configure(KotlinJvmProjectExtension::class.java) {
    val compilations = target.compilations
    compilations.getByName("repoTest").associateWith(compilations.getByName("test"))
  }

  dependencies.add(repoTestSourceSet.implementationConfigurationName, testSourceSet.output)

  val repoTest =
    tasks.register("repoTest", Test::class.java) {
      group = "verification"
      description = "Repository-contract suites that read governed sources outside runtime-kotlin."
      testClassesDirs = repoTestSourceSet.output.classesDirs
      classpath = repoTestSourceSet.runtimeClasspath
      inputs.files(governedRepositorySources())
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("governedRepositorySources")
    }

  tasks.named("check") {
    dependsOn(repoTest)
  }
}

private fun Project.governedRepositorySources(): FileCollection {
  val repoRoot = rootProject.layout.projectDirectory.dir("..")
  return files(
    repoRoot.dir(".github"),
    repoRoot.dir("skills"),
    repoRoot.dir("platform-packs"),
    repoRoot.dir("orchestration"),
    repoRoot.dir("agent-addons"),
    repoRoot.dir("docs"),
    repoRoot.dir("tests"),
    repoRoot.dir("scripts"),
    repoRoot.file("README.md"),
    repoRoot.file("LICENSE"),
    repoRoot.file("install.sh"),
    repoRoot.file("uninstall.sh"),
  )
}
