import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

plugins {
  id("skillbill.desktop.application")
  id("skillbill.build-identity")
  id("skillbill.desktop.kotlin.inject")
}

group = "dev.skillbill.desktop"
version = "0.1.0"

tasks.named<dev.skillbill.buildidentity.WriteBuildIdentityTask>("writeBuildIdentity") {
  capabilityDeclaration.set(
    rootDir.parentFile.resolve("runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/DesktopReadSchema.kt"),
  )
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(compose.material3)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.feature.terminal)
    implementation(projects.feature.work)
    implementation(projects.feature.history)
    implementation(projects.feature.detail)
    implementation(projects.feature.specs)
    implementation(projects.feature.settings)
    implementation(projects.feature.config)
    implementation(projects.feature.tray)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
  }
  sourceSets.jvmMain.dependencies {
    implementation(libs.compose.native.tray)
    implementation(libs.nucleus.notification.common)
    implementation(libs.nucleus.application)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.nucleus.autolaunch)
    // The merged component names the session's runtime graph; its classes come from the installed runtime at run time.
    compileOnly("dev.skillbill:runtime-core")
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
    // The architecture test parses the per-module manifests.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlin.compiler.embeddable)
  }
}

dependencies {
  constraints {
    add("jvmMainImplementation", libs.kotlinx.serialization.core) {
      because("Compose lifecycle pulls kotlinx-serialization 1.7.3; the installed runtime supplies the catalog version at run time.")
    }
  }
  // Projects without a build script (the implicit ":core" parent) own no sources and no manifest.
  rootProject.subprojects.filter { it.buildFile.exists() }.forEach { add("architectureManifests", project(it.path)) }
}

compose.desktop {
  application {
    mainClass = "dev.skillbill.desktop.app.DesktopLauncherKt"
  }
}

val trayJavaHome = extensions.getByType<JavaToolchainService>().launcherFor {
  languageVersion.set(JavaLanguageVersion.of(21))
  vendor.set(JvmVendorSpec.ADOPTIUM)
}.map { it.metadata.installationPath.asFile.absolutePath }
val trayNative = layout.projectDirectory.dir("src/jvmMain/native")
val linuxTrayResources = layout.buildDirectory.dir("generated/linuxTray/resources")
val compileLinuxTrayNative = tasks.register<Exec>("compileLinuxTrayNative") {
  val script = trayNative.file("linux/build-tray.sh")
  inputs.file(script)
  outputs.dir(linuxTrayResources)
  onlyIf { System.getProperty("os.name").contains("linux", true) }
  doFirst { commandLine("bash", script.asFile.absolutePath, linuxTrayResources.get().asFile.parentFile.absolutePath, trayJavaHome.get()) }
}
kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(linuxTrayResources).builtBy(compileLinuxTrayNative))

val macTrayResources = layout.buildDirectory.dir("generated/macosTray/resources")
val compileMacTrayNative = tasks.register<Exec>("compileMacTrayNative") {
  val source = trayNative.file("macos/MacStatusItemBridge.m")
  inputs.file(source)
  outputs.dir(macTrayResources)
  onlyIf { System.getProperty("os.name").contains("mac", true) }
  doFirst {
    val library = macTrayResources.get().file("macos/libskillbilltray.dylib").asFile
    library.parentFile.mkdirs()
    commandLine("clang", "-dynamiclib", "-undefined", "dynamic_lookup", "-framework", "AppKit", "-I${trayJavaHome.get()}/include", "-I${trayJavaHome.get()}/include/darwin", source.asFile.absolutePath, "-o", library.absolutePath)
  }
}
if (System.getProperty("os.name").contains("mac", true)) {
  kotlin.sourceSets.getByName("jvmMain").resources.srcDir(files(macTrayResources).builtBy(compileMacTrayNative))
}

tasks.named<Test>("jvmTest") {
  systemProperty("desktopRoot", rootDir.absolutePath)
  inputs.files(rootDir.resolve("settings.gradle.kts"), rootDir.resolve("build.gradle.kts")).withPropertyName("desktopRootBuildScripts")
}
