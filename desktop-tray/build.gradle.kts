plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.serialization)
}

group = "dev.skillbill.tray"
version = "0.1.0"

kotlin { jvmToolchain(21) }

// Compose packaging rejects some vendors' JDKs (Homebrew), so pin the bundled runtime to the
// vendor CI releases with; the foojay resolver provisions it on machines that lack one.
val desktopJavaHome = javaToolchains.launcherFor {
  languageVersion.set(JavaLanguageVersion.of(21))
  vendor.set(JvmVendorSpec.ADOPTIUM)
}.get().metadata.installationPath.asFile.absolutePath
val desktopPackageVersion = System.getenv("RELEASE_VERSION")?.substringBefore('-') ?: "0.1.0"
val macOSPackageVersion = desktopPackageVersion.takeUnless { it.startsWith("0.") } ?: "1.0.0"

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
  implementation(libs.room.runtime)
  implementation(libs.sqlite.api)
  implementation(libs.sqlite.jdbc)
  implementation(libs.datastore.core)
  implementation(libs.paging.common)
  ksp(libs.room.compiler)
  implementation("dev.skillbill:runtime-core")
  implementation("dev.skillbill:runtime-contracts")
  testImplementation("dev.skillbill:runtime-infra-host")
  testImplementation("dev.skillbill:runtime-infra-contracts")
  testImplementation(testFixtures("dev.skillbill:runtime-infra-sqlite"))
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.21")
  testImplementation(kotlin("test"))
  testImplementation(libs.junit.jupiter)
}

tasks.test {
  useJUnitPlatform()
  maxHeapSize = "2g"
}

tasks.withType<Test>().configureEach {
  inputs.dir("src/main/kotlin").withPropertyName("desktopArchitectureProductionSources")
  inputs.file("../runtime-kotlin/runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeModuleCatalog.kt")
    .withPropertyName("runtimeModuleCatalog")
  inputs.files(fileTree("../runtime-kotlin") {
    include("**/src/main/kotlin/**/*.kt")
    exclude("**/build/**", "**/.gradle/**")
  }).withPropertyName("runtimeArchitectureProductionSources")
}

compose.desktop {
  application {
    mainClass = "dev.skillbill.tray.composition.MainKt"
    javaHome = desktopJavaHome
    nativeDistributions {
      modules("java.sql", "java.net.http", "java.management", "jdk.unsupported")
      targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
        org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm)
      packageName = "Skill Bill"
      packageVersion = desktopPackageVersion
      macOS {
        bundleID = "dev.skillbill.tray"
        packageVersion = macOSPackageVersion
        packageBuildVersion = macOSPackageVersion
      }
      windows { menuGroup = "Skill Bill" }
      linux { appCategory = "Development" }
    }
  }
}

val appImageToolVersion = "1.9.1"
val appImageLauncher = layout.buildDirectory.file("generated/appImage/AppRun")
val compileAppImageLauncher by tasks.registering(Exec::class) {
  val source = layout.projectDirectory.file("src/main/native/linux/AppRun.c")
  inputs.file(source)
  outputs.file(appImageLauncher)
  onlyIf { System.getProperty("os.name").contains("linux", true) }
  doFirst {
    appImageLauncher.get().asFile.parentFile.mkdirs()
    commandLine("cc", "-std=c11", "-O2", source.asFile.absolutePath, "-o", appImageLauncher.get().asFile.absolutePath)
  }
}
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
  onlyIf { System.getProperty("os.name").contains("linux", true) }
  dependsOn("createDistributable")
  dependsOn(compileAppImageLauncher)
  from(layout.buildDirectory.dir("compose/binaries/main/app"))
  into(layout.buildDirectory.dir("appimage/AppDir"))
  doLast {
    val appDir = layout.buildDirectory.dir("appimage/AppDir").get().asFile
    val launcher = appDir.resolve("Skill Bill/bin/Skill Bill")
    check(launcher.exists()) { "Compose did not stage the Linux application launcher." }
    appImageLauncher.get().asFile.copyTo(appDir.resolve("AppRun"), overwrite = true)
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
