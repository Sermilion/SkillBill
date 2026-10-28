package dev.skillbill.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies the Kotlin serialization compiler plugin from build-logic's classpath. A module cannot request the bare plugin id: only
 * build-logic carries the artifact, and an included build exposes nothing but the plugins it registers.
 */
class KotlinSerializationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    target.pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
  }
}
