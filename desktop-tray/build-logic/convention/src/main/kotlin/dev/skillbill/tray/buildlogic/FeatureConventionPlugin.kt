package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A screen feature: Compose UI plus a lifecycle ViewModel in `commonMain`. The plugin adds the ViewModel, preview and test tooling;
 * each feature module declares its own project dependencies, which the architecture guards hold to the allowed subset.
 */
class FeatureConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpComposeConventionPlugin::class.java)
    extensions.configure<KotlinMultiplatformExtension> {
      sourceSets.getByName("commonMain").dependencies {
        implementation(libs.library("kotlinx-coroutines-core"))
        implementation(libs.library("lifecycle-viewmodel"))
        implementation(libs.library("lifecycle-viewmodel-compose"))
        implementation(libs.library("compose-ui-tooling-preview"))
      }
      sourceSets.getByName("commonTest").dependencies {
        implementation(libs.library("kotlinx-coroutines-test"))
      }
      sourceSets.getByName("jvmTest").dependencies {
        implementation(libs.library("compose-ui-test"))
      }
    }
  }
}
