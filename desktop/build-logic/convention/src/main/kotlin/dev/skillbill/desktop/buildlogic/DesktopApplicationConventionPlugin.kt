package dev.skillbill.desktop.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

/**
 * The `:app` module: Compose Desktop application packaging on the Adoptium 21 toolchain, the Compose native-peer recovery patch,
 * the shared-runtime version and runtime-free image checks, and the build identity record as an app resource. Target formats and
 * platform packaging blocks belong to the packaging work, not here.
 */
class DesktopApplicationConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) = with(target) {
    pluginManager.apply(KmpComposeConventionPlugin::class.java)
    configureComposePeerRecovery()
    val javaHome = extensions.getByType(JavaToolchainService::class.java).launcherFor {
      languageVersion.set(JavaLanguageVersion.of(JVM_TOOLCHAIN_VERSION))
      vendor.set(JvmVendorSpec.ADOPTIUM)
    }.get().metadata.installationPath.asFile.absolutePath
    val packageVersion = providers.environmentVariable("RELEASE_VERSION").orNull?.substringBefore('-') ?: "0.1.0"
    val macOSPackageVersion = packageVersion.takeUnless { it.startsWith("0.") } ?: "1.0.0"
    extensions.getByType(ComposeExtension::class.java).extensions.getByType(DesktopExtension::class.java).application {
      this.javaHome = javaHome
      nativeDistributions {
        modules("java.sql", "java.net.http", "java.management", "jdk.unsupported")
        packageName = "Skill Bill"
        this.packageVersion = packageVersion
        macOS {
          bundleID = "dev.skillbill.desktop"
          this.packageVersion = macOSPackageVersion
          packageBuildVersion = macOSPackageVersion
        }
      }
    }
    registerSharedRuntimeVersionCheck()
    tasks.withType(AbstractJPackageTask::class.java).configureEach {
      inputs.dir(appResourcesDir).withPropertyName("applicationResources").optional().withPathSensitivity(PathSensitivity.RELATIVE)
    }
    pluginManager.withPlugin("skillbill.build-identity") { packageBuildIdentity() }
  }

  // The record is an app resource, so the packaged app and install.sh both read it as a plain file. Compose's own
  // prepareAppResources copies it into the distribution and points `compose.application.resources.dir` at it for `run`.
  private fun Project.packageBuildIdentity() {
    val resourcesRoot = layout.buildDirectory.dir("generated/app-resources")
    val stage = tasks.register<Sync>("stageBuildIdentityResource") {
      from(tasks.named("writeBuildIdentity"))
      into(resourcesRoot.map { it.dir("common") })
    }
    extensions.getByType(ComposeExtension::class.java).extensions.getByType(DesktopExtension::class.java).application {
      nativeDistributions { appResourcesRootDir.set(resourcesRoot) }
    }
    tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stage) }
  }

  private fun Project.configureComposePeerRecovery() {
    val recovered = Attribute.of("dev.skillbill.compose.peer-recovery", Boolean::class.javaObjectType)
    dependencies.artifactTypes.getByName(ArtifactTypeDefinition.JAR_TYPE).attributes.attribute(recovered, false)
    dependencies.registerTransform(ComposePeerRecoveryTransform::class.java) {
      from.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(recovered, false)
      to.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE).attribute(recovered, true)
    }
    configurations.matching { it.name == "jvmRuntimeClasspath" || it.name == "jvmTestRuntimeClasspath" }.configureEach {
      attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
      attributes.attribute(recovered, true)
    }
  }
}
