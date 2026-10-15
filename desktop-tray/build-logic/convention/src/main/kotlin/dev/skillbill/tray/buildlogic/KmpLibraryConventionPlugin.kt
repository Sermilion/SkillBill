package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply("org.jetbrains.kotlin.multiplatform")
    pluginManager.apply(QualityConventionPlugin::class.java)
    extensions.configure<KotlinMultiplatformExtension> {
      jvmToolchain(JVM_TOOLCHAIN_VERSION)
      jvm()
      compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
      sourceSets.getByName("commonTest").dependencies { implementation(libs.library("kotlin-test")) }
      sourceSets.getByName("jvmTest").dependencies {
        implementation(libs.library("junit-jupiter"))
        runtimeOnly(project.dependencies.platform(libs.library("junit-bom")))
        runtimeOnly("org.junit.platform:junit-platform-launcher")
      }
    }
    tasks.withType<Test>().configureEach {
      useJUnitPlatform()
      maxHeapSize = "1g"
    }
  }
}
