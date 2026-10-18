package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Wires Room and the JDBC-backed SQLite driver into the JVM source set; the compiler runs through `kspJvm` only. */
class RoomConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpLibraryConventionPlugin::class.java)
    pluginManager.apply("com.google.devtools.ksp")
    extensions.configure<KotlinMultiplatformExtension> {
      sourceSets.getByName("jvmMain").dependencies {
        implementation(libs.library("room-runtime"))
        implementation(libs.library("sqlite-api"))
        implementation(libs.library("sqlite-jdbc"))
      }
    }
    dependencies {
      add("kspJvm", libs.library("room-compiler"))
    }
  }
}
