package dev.skillbill.tray.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.register
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

const val APP_IMAGE_TOOL_VERSION = "1.9.1"

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
    configurations.configureEach {
      exclude(group = "dev.nucleusframework", module = "nucleus.decorated-window-tao")
    }
    extensions.getByType(ComposeExtension::class.java).extensions.getByType(DesktopExtension::class.java).application {
      this.javaHome = javaHome
      nativeDistributions {
        modules("java.sql", "java.net.http", "java.management", "jdk.unsupported")
        targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
        packageName = "Skill Bill"
        this.packageVersion = packageVersion
        macOS {
          bundleID = "dev.skillbill.tray"
          this.packageVersion = macOSPackageVersion
          packageBuildVersion = macOSPackageVersion
        }
        windows { menuGroup = "Skill Bill" }
        linux { appCategory = "Development" }
      }
    }
    val osName = System.getProperty("os.name")
    val isLinux = osName.contains("linux", true)
    val isMac = osName.contains("mac", true)
    registerNativeTasks(javaHome, isLinux, isMac)
    registerAppImageTasks(isLinux)
    registerSharedRuntimeVersionCheck()
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

  private fun Project.registerNativeTasks(javaHome: String, isLinux: Boolean, isMac: Boolean) {
    val native = layout.projectDirectory.dir("src/jvmMain/native")
    val jvmMainResources = extensions.getByType(KotlinMultiplatformExtension::class.java).sourceSets.getByName("jvmMain").resources
    if (isLinux) {
      val linuxTrayResources = layout.buildDirectory.dir("generated/linuxTray/resources")
      val script = native.file("linux/build-tray.sh")
      val compileLinuxTrayNative = tasks.register<Exec>("compileLinuxTrayNative") {
        inputs.file(script)
        outputs.dir(linuxTrayResources)
        commandLine("bash", script.asFile.absolutePath, linuxTrayResources.get().asFile.parentFile.absolutePath, javaHome)
      }
      jvmMainResources.srcDir(files(linuxTrayResources).builtBy(compileLinuxTrayNative))
    }
    val macTrayResources = layout.buildDirectory.dir("generated/macosTray/resources")
    val macSource = native.file("macos/MacStatusItemBridge.m")
    val compileMacTrayNative = tasks.register<Exec>("compileMacTrayNative") {
      inputs.file(macSource)
      outputs.dir(macTrayResources)
      onlyIf { isMac }
      doFirst {
        val library = macTrayResources.get().asFile.resolve("macos/libskillbilltray.dylib")
        library.parentFile.mkdirs()
        commandLine(
          "clang", "-dynamiclib", "-undefined", "dynamic_lookup", "-framework", "AppKit",
          "-I$javaHome/include", "-I$javaHome/include/darwin", macSource.asFile.absolutePath, "-o", library.absolutePath,
        )
      }
    }
    if (isMac) jvmMainResources.srcDir(files(macTrayResources).builtBy(compileMacTrayNative))
  }

  private fun Project.registerAppImageTasks(isLinux: Boolean) {
    val appImageLauncher = layout.buildDirectory.file("generated/appImage/AppRun")
    val launcherSource = layout.projectDirectory.file("src/jvmMain/native/linux/AppRun.c")
    val compileAppImageLauncher = tasks.register<Exec>("compileAppImageLauncher") {
      inputs.file(launcherSource)
      outputs.file(appImageLauncher)
      onlyIf { isLinux }
      doFirst {
        appImageLauncher.get().asFile.parentFile.mkdirs()
        commandLine("cc", "-std=c11", "-O2", launcherSource.asFile.absolutePath, "-o", appImageLauncher.get().asFile.absolutePath)
      }
    }
    val appDirectory = layout.buildDirectory.dir("appimage/AppDir")
    val appIcon = rootProject.layout.projectDirectory.file("../design/App/docs/assets/skill-bill-app-icon.svg")
    val stageAppImage = tasks.register<Sync>("stageAppImage") {
      onlyIf { isLinux }
      dependsOn("createDistributable", compileAppImageLauncher)
      from(layout.buildDirectory.dir("compose/binaries/main/app"))
      into(appDirectory)
      doLast {
        val appDir = appDirectory.get().asFile
        check(appDir.resolve("Skill Bill/bin/Skill Bill").exists()) { "Compose did not stage the Linux application launcher." }
        appImageLauncher.get().asFile.copyTo(appDir.resolve("AppRun"), overwrite = true)
        appDir.resolve("AppRun").setExecutable(true)
        appDir.resolve("skill-bill.desktop").writeText(
          "[Desktop Entry]\nType=Application\nName=Skill Bill\nExec=AppRun\nIcon=skill-bill\nCategories=Development;\nTerminal=false\n",
        )
        appIcon.asFile.copyTo(appDir.resolve("skill-bill.svg"), overwrite = true)
      }
    }
    val tool = rootProject.layout.buildDirectory.file("tools/appimagetool-$APP_IMAGE_TOOL_VERSION-x86_64.AppImage")
    val appImageOutput = layout.buildDirectory.file("compose/binaries/main/Skill-Bill-x86_64.AppImage")
    tasks.register<Exec>("packageAppImage") {
      dependsOn(stageAppImage)
      doFirst {
        check(tool.get().asFile.exists()) {
          "Place verified appimagetool $APP_IMAGE_TOOL_VERSION at ${tool.get().asFile} before packaging."
        }
        tool.get().asFile.setExecutable(true)
      }
      commandLine(tool.get().asFile.absolutePath, appDirectory.get().asFile.absolutePath, appImageOutput.get().asFile.absolutePath)
    }
  }
}
