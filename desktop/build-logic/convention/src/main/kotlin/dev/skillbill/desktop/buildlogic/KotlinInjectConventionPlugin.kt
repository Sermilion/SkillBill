package dev.skillbill.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KotlinInjectConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpLibraryConventionPlugin::class.java)
    pluginManager.apply("com.google.devtools.ksp")
    extensions.configure<KotlinMultiplatformExtension> {
      sourceSets.getByName("commonMain").dependencies {
        implementation(libs.library("kotlin-inject-runtime"))
        implementation(libs.library("anvil-runtime"))
        // Owns the `@SingleIn` scope annotation.
        implementation(libs.library("anvil-runtime-optional"))
      }
    }
    dependencies {
      add("kspJvm", libs.library("kotlin-inject-compiler"))
      add("kspJvm", libs.library("anvil-compiler"))
    }
  }
}
