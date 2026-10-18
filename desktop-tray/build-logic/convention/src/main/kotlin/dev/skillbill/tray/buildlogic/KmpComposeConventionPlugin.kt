package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposeConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpLibraryConventionPlugin::class.java)
    pluginManager.apply("org.jetbrains.compose")
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    val composeExtension = extensions.getByType(ComposeExtension::class.java)
    val compose = composeExtension.dependencies
    composeExtension.extensions.configure<ResourcesExtension> {
      packageOfResClass = "dev.skillbill.tray${path.replace(':', '.')}.generated.resources"
    }
    extensions.configure<KotlinMultiplatformExtension> {
      sourceSets.getByName("commonMain").dependencies {
        implementation(compose.runtime)
        implementation(compose.foundation)
        implementation(compose.material3)
        implementation(compose.ui)
        implementation(compose.components.resources)
      }
      sourceSets.getByName("jvmMain").dependencies {
        implementation(compose.desktop.currentOs)
      }
    }
  }
}
