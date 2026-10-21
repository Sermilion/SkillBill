package dev.skillbill.runtime.buildlogic

import org.beryx.runtime.BaseTask
import org.beryx.runtime.data.RuntimePluginExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import java.io.File

private const val LINK_JDK_VERSION = 21

private const val UNSUPPORTED_HOST_SEGMENT = "unsupported-host"

private const val CC_OPT_OUT_REASON =
  "Badass Runtime is not configuration-cache compatible (serializes Gradle model objects)."

private const val AUTHORED_JAVA_GUARD =
  "runtime-infra/host/src/main/resources/skillbill/infrastructure/host/jvm/skill-bill-java-guard.sh"

private val IMAGE_MODULES =
  listOf(
    "java.base",
    "java.logging",
    "java.management",
    "java.naming",
    "java.net.http",
    "java.sql",
    "java.xml",
    "java.desktop",
    "jdk.crypto.ec",
    "jdk.unsupported",
  )

private class RuntimeImageHost(
  private val osName: String = System.getProperty("os.name").orEmpty(),
  private val osArch: String = System.getProperty("os.arch").orEmpty(),
) {
  val token: String? = resolveHostRuntimeToken(osName, osArch)

  val tokenSegment: String = token ?: UNSUPPORTED_HOST_SEGMENT

  fun unsupportedMessage(): String =
    "Cannot build a self-contained runtime image on this host (os.name='$osName', os.arch='$osArch'). " +
      "This is a known-gap target; build it on a matching CI runner."
}

class RuntimeImageConventionPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    with(target) {
      pluginManager.apply("org.beryx.runtime")

      val extension = extensions.create("runtimeImage", RuntimeImageExtension::class.java)
      val host = RuntimeImageHost()
      val zipName =
        extension.imageBaseName.zip(provider { version.toString() }) { baseName, imageVersion ->
          "$baseName-$imageVersion-${host.tokenSegment}.zip"
        }
      val imageZipFile = layout.buildDirectory.file(zipName.map { name -> "runtime-image/$name" })
      val repositoryLicense = rootProject.projectDir.parentFile.resolve("LICENSE")

      configureStartScriptJavaGuard(
        objects.fileProperty().convention(
          rootProject.layout.projectDirectory.file(AUTHORED_JAVA_GUARD),
        ),
      )
      configureStaticRuntimeWiring(host, imageZipFile)
      packageRepositoryLicense(repositoryLicense)
      configureRuntimeZipTask(
        host,
        registerLicenseVerification(repositoryLicense),
        registerSidecarTask(imageZipFile, zipName),
      )
      pluginManager.withPlugin(BUILD_IDENTITY_PLUGIN_ID) {
        packageBuildIdentity()
      }
    }
  }

  private fun Project.configureStaticRuntimeWiring(
    host: RuntimeImageHost,
    imageZipFile: Provider<RegularFile>,
  ) {
    val toolchains = extensions.getByType<JavaToolchainService>()
    val linkJavaHome: Provider<String> =
      toolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(LINK_JDK_VERSION)) }
        .map { launcher -> launcher.metadata.installationPath.asFile.absolutePath }

    extensions.configure<RuntimePluginExtension>("runtime") {
      javaHome.set(linkJavaHome)

      addOptions("--no-header-files", "--no-man-pages", "--strip-debug", "--compress", "2")

      additive.set(true)
      modules.set(IMAGE_MODULES)
      imageZip.set(imageZipFile)
    }

    tasks.withType<BaseTask>().configureEach {
      notCompatibleWithConfigurationCache(CC_OPT_OUT_REASON)
      if (host.token == null) {
        doFirst {
          error(host.unsupportedMessage())
        }
      }
    }
  }

  private fun Project.packageRepositoryLicense(licenseSource: File) {
    extensions.configure<DistributionContainer> {
      named("main") {
        contents {
          from(licenseSource)
        }
      }
    }
  }

  private fun Project.packageBuildIdentity() {
    val writeBuildIdentity = tasks.named(WRITE_BUILD_IDENTITY_TASK)
    extensions.configure<DistributionContainer> {
      named("main") {
        contents {
          from(writeBuildIdentity)
        }
      }
    }
    val installedIdentity =
      tasks.named<Sync>("installDist").map { install ->
        File(install.destinationDir, BUILD_IDENTITY_RECORD)
      }
    val verification =
      tasks.register<VerifyRuntimeImageBuildIdentityTask>("verifyRuntimeImageBuildIdentity") {
        group = "verification"
        description = "Verify the install and runtime images carry this build's identity record unchanged."
        dependsOn("installDist", "runtime")
        buildIdentity.fileProvider(writeBuildIdentity.map { task -> task.outputs.files.singleFile })
        packagedIdentities.from(installedIdentity, layout.buildDirectory.file("image/$BUILD_IDENTITY_RECORD"))
      }
    tasks.named("runtimeZip") {
      dependsOn(verification)
    }
  }

  private fun Project.registerLicenseVerification(licenseSource: File): TaskProvider<VerifyRuntimeImageLicenseTask> {
    val installedLicense =
      tasks.named<Sync>("installDist").map { install ->
        File(install.destinationDir, "LICENSE")
      }
    val imageLicense = layout.buildDirectory.file("image/LICENSE")
    return tasks.register<VerifyRuntimeImageLicenseTask>("verifyRuntimeImageLicense") {
      group = "verification"
      description = "Verify the install and runtime images carry the repository LICENSE unchanged."
      dependsOn("installDist", "runtime")
      repositoryLicense.set(licenseSource)
      packagedLicenses.from(installedLicense, imageLicense)
    }
  }

  private fun Project.registerSidecarTask(
    archiveFile: Provider<RegularFile>,
    zipName: Provider<String>,
  ): TaskProvider<Sha256SidecarTask> {
    val sidecarFile = layout.buildDirectory.file(zipName.map { name -> "runtime-image/$name.sha256" })
    return tasks.register<Sha256SidecarTask>("runtimeZipSha256") {
      group = "distribution"
      description = "Write a SHA-256 sidecar next to the runtime image zip."
      dependsOn("runtimeZip")
      archive.set(archiveFile)
      sidecar.set(sidecarFile)
      notCompatibleWithConfigurationCache("Sidecar follows the not-cacheable runtimeZip task.")
    }
  }

  private fun Project.configureRuntimeZipTask(
    host: RuntimeImageHost,
    licenseVerification: TaskProvider<*>,
    sidecar: TaskProvider<*>,
  ) {
    tasks.named("runtimeZip") {
      group = "distribution"
      description =
        "Build the self-contained runtime image and a versioned image zip (${host.tokenSegment})."
      dependsOn(licenseVerification)
      finalizedBy(sidecar)
    }
  }
}
