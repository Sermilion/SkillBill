plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
}

group = "dev.skillbill.tray"
version = "0.1.0"

kotlin { jvmToolchain(21) }

val desktopJavaHome = javaToolchains.launcherFor {
  languageVersion.set(JavaLanguageVersion.of(21))
}.get().metadata.installationPath.asFile.absolutePath

configurations.configureEach {
  exclude(group = "dev.nucleusframework", module = "nucleus.decorated-window-tao")
}

dependencies {
  implementation(compose.desktop.currentOs)
  implementation(compose.material3)
  implementation(compose.materialIconsExtended)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.swing)
  implementation(libs.compose.native.tray)
  implementation(libs.nucleus.notification.common)
  implementation(libs.nucleus.application)
  implementation(libs.nucleus.autolaunch)
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
}

tasks.test { useJUnitPlatform() }

compose.desktop {
  application {
    mainClass = "dev.skillbill.tray.composition.MainKt"
    javaHome = desktopJavaHome
    if (System.getProperty("os.name").contains("mac", true)) {
      jvmArgs("-XstartOnFirstThread")
    }
    nativeDistributions {
      targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
      packageName = "Skill Bill"
      packageVersion = "0.1.0"
      macOS { bundleID = "dev.skillbill.tray" }
      windows { menuGroup = "Skill Bill" }
      linux { appCategory = "Development" }
    }
  }
}

val appImageToolVersion = "1.9.1"
if (System.getProperty("os.name").contains("linux", true)) {
  val linuxTrayDirectory = layout.buildDirectory.dir("generated/linuxTray")
  val compileLinuxTrayNative by tasks.registering(Exec::class) {
    inputs.file("src/main/native/linux/build-tray.sh")
    outputs.dir(linuxTrayDirectory.map { it.dir("resources") })
    commandLine("bash", "src/main/native/linux/build-tray.sh",
      linuxTrayDirectory.get().asFile.absolutePath, desktopJavaHome)
  }
  tasks.processResources {
    dependsOn(compileLinuxTrayNative)
    from(linuxTrayDirectory.map { it.dir("resources") })
  }
}
val macTrayNativeOutput = layout.buildDirectory.file("generated/macosTray/libskillbilltray.dylib")
val compileMacTrayNative by tasks.registering(Exec::class) {
  val source = layout.projectDirectory.file("src/main/native/macos/MacStatusItemBridge.m")
  inputs.file(source)
  outputs.file(macTrayNativeOutput)
  onlyIf { System.getProperty("os.name").contains("mac", true) }
  doFirst {
    macTrayNativeOutput.get().asFile.parentFile.mkdirs()
    val javaHome = desktopJavaHome
    commandLine("clang", "-dynamiclib", "-undefined", "dynamic_lookup", "-framework", "AppKit", "-I$javaHome/include", "-I$javaHome/include/darwin",
      source.asFile.absolutePath, "-o", macTrayNativeOutput.get().asFile.absolutePath)
  }
}

if (System.getProperty("os.name").contains("mac", true)) {
  tasks.processResources {
    dependsOn(compileMacTrayNative)
    from(macTrayNativeOutput) { into("macos"); rename { "libskillbilltray.dylib" } }
  }
}

val stageAppImage by tasks.registering(Sync::class) {
  dependsOn("createDistributable")
  from(layout.buildDirectory.dir("compose/binaries/main/app"))
  into(layout.buildDirectory.dir("appimage/AppDir"))
  doLast {
    val appDir = layout.buildDirectory.dir("appimage/AppDir").get().asFile
    val launcher = appDir.resolve("Skill Bill/bin/Skill Bill")
    check(launcher.exists()) { "Compose did not stage the Linux application launcher." }
    appDir.resolve("AppRun").writeText("""#!/bin/sh
HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
exec "${'$'}HERE/Skill Bill/bin/Skill Bill" "${'$'}@"
""")
    appDir.resolve("AppRun").setExecutable(true)
    appDir.resolve("skill-bill.desktop").writeText("[Desktop Entry]\nType=Application\nName=Skill Bill\nExec=AppRun\nIcon=skill-bill\nCategories=Development;\nTerminal=false\n")
    val icon = appDir.resolve("skill-bill.svg")
    rootProject.file("../design/App/docs/assets/skill-bill-app-icon.svg").copyTo(icon, overwrite = true)
  }
}

tasks.register<Exec>("packageAppImage") {
  dependsOn(stageAppImage)
  val tool = layout.buildDirectory.file("tools/appimagetool-$appImageToolVersion-x86_64.AppImage")
  doFirst {
    check(tool.get().asFile.exists()) {
      "Place verified appimagetool $appImageToolVersion at ${tool.get().asFile} before packaging."
    }
    tool.get().asFile.setExecutable(true)
  }
  commandLine(tool.get().asFile.absolutePath,
    layout.buildDirectory.file("appimage/AppDir").get().asFile.absolutePath,
    layout.buildDirectory.file("compose/binaries/main/Skill-Bill-x86_64.AppImage").get().asFile.absolutePath)
}
