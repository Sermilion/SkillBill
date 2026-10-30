package dev.skillbill.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A screen feature: Compose UI plus a lifecycle ViewModel in `commonMain`, with kotlin-inject for its bindings. The plugin adds the
 * ViewModel, preview and test tooling and the `:core:ui`, `:core:designsystem` and `:core:domain` edges every feature needs; any
 * other project edge is the feature's own, and the architecture guards hold it to the allowed subset.
 */
class FeatureConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpComposeConventionPlugin::class.java)
    pluginManager.apply(KotlinInjectConventionPlugin::class.java)
    extensions.configure<KotlinMultiplatformExtension> {
      sourceSets.getByName("commonMain").dependencies {
        implementation(project(":core:ui"))
        implementation(project(":core:designsystem"))
        implementation(project(":core:domain"))
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
