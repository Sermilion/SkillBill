import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation(projects.feature.chat)
    implementation(projects.feature.work)
    implementation(projects.feature.history)
    implementation(projects.feature.detail)
    implementation(projects.feature.changes)
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
    implementation(libs.compose.ui.test)
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation("dev.skillbill:runtime-infra-host")
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
    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
      macOS {
        iconFile.set(layout.projectDirectory.file("packaging/icons/sona.icns"))
      }
      windows {
        menuGroup = "Sona"
        upgradeUuid = "e9c88238-8cb5-4be4-bb0a-17a1d6de7671"
        iconFile.set(layout.projectDirectory.file("packaging/icons/sona.ico"))
      }
      linux {
        packageName = "sona"
        appCategory = "Development"
        iconFile.set(rootProject.layout.projectDirectory.file("core/designsystem/src/commonMain/composeResources/drawable/sona.png"))
      }
    }
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
  inputs.files(
    rootDir.resolve("gradlew"),
    rootDir.resolve("build-logic/convention/build.gradle.kts"),
    rootDir.resolve("build-logic/convention/src/main/kotlin"),
    rootProject.subprojects.flatMap { module ->
      listOf(module.file("src/commonMain/kotlin"), module.file("src/jvmMain/kotlin"))
    },
  ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("sharedTempRootSources")
}

tasks.register<Exec>("packagedDataSmoke") {
  // Keep the launcher smoke opt-in so check never starts a packaged desktop process.
  group = "verification"
  description = "Runs the packaged desktop image against a seeded runtime store."
  dependsOn("createDistributable", "compileTestKotlinJvm", gradle.includedBuild("runtime-kotlin").task(":runtime-cli:installDist"))
  val script = layout.projectDirectory.file("scripts/packaged-data-smoke.py")
  val runtimeInstall = rootDir.parentFile.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli")
  doFirst {
    val python = if (System.getProperty("os.name").contains("windows", true)) "python" else "python3"
    commandLine(python, script.asFile.absolutePath, runtimeInstall.absolutePath)
  }
}

// Compose registers run and prepareAppResources in its afterEvaluate callback.
afterEvaluate {
  val sourceRunRoot = layout.buildDirectory.dir("source-run")
  val composeRun = tasks.named<JavaExec>("run")
  val prepareAppResources = tasks.named("prepareAppResources")

  val stageSourceRun = tasks.register<dev.skillbill.desktop.buildlogic.StageSourceRunTask>("stageSourceRun") {
    dependsOn(gradle.includedBuild("runtime-kotlin").task(":runtime-cli:installDist"), prepareAppResources)
    checkoutRoot.set(rootDir.parentFile)
    runtimeInstall.set(rootDir.parentFile.resolve("runtime-kotlin/runtime-cli/build/install/runtime-cli"))
    appResources.fileProvider(prepareAppResources.map { it.outputs.files.singleFile })
    outputRoot.set(sourceRunRoot)
  }

  tasks.register<JavaExec>("runFromSource") {
    // Opt-in QA launch: not wired into check, build or assemble.
    group = "application"
    description = "Runs the desktop app against a runtime built from this checkout, with all state under build/source-run."
    dependsOn(stageSourceRun, gradle.includedBuild("runtime-kotlin").task(":runtime-core:writeDesktopFixtureDb"))
    val run = composeRun.get()
    mainClass.set(run.mainClass)
    classpath = run.classpath
    run.executable?.let { setExecutable(it) }
    val root = sourceRunRoot.get().asFile
    val home = root.resolve("home")
    val state = home.resolve(".skill-bill")
    environment("SKILL_BILL_RUNTIME_DIR", root.resolve("runtime").absolutePath)
    environment("SKILL_BILL_STATE_DIR", state.absolutePath)
    environment("SKILL_BILL_REVIEW_DB", state.resolve("review-metrics.db").absolutePath)
    if (System.getProperty("os.name").contains("linux", true)) {
      environment("SKIKO_RENDER_API", "SOFTWARE_COMPAT")
    }
    mapOf(
      "HOME" to home,
      "USERPROFILE" to home,
      "TMPDIR" to home.resolve("tmp"),
      "TEMP" to home.resolve("tmp"),
      "TMP" to home.resolve("tmp"),
      "APPDATA" to home.resolve("AppData/Roaming"),
      "LOCALAPPDATA" to home.resolve("AppData/Local"),
      "XDG_CONFIG_HOME" to home.resolve(".config"),
      "XDG_CACHE_HOME" to home.resolve(".cache"),
      "XDG_DATA_HOME" to home.resolve(".local/share"),
      "XDG_STATE_HOME" to home.resolve(".local/state"),
      "CLAUDE_CONFIG_DIR" to home.resolve(".claude"),
      "CODEX_HOME" to home.resolve(".codex"),
      "CURSOR_CONFIG_DIR" to home.resolve(".cursor"),
    ).forEach { (name, directory) -> environment(name, directory.absolutePath) }
    doFirst {
      val fixtureState =
        rootDir.parentFile.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
      dev.skillbill.desktop.buildlogic.SourceRunPreparation.seed(rootDir.parentFile, root, fixtureState)
      listOf(home, state, home.resolve("preferences"), home.resolve("tmp")).forEach { directory ->
        if (!directory.mkdirs() && !directory.isDirectory) {
          throw GradleException("Could not create scratch directory: ${directory.absolutePath}")
        }
      }
      // A blank TERM makes the app import the login shell's environment, which could replace the SKILL_BILL_* values.
      if ((environment["TERM"] as? String).isNullOrBlank()) environment("TERM", "dumb")
      jvmArgs = run.jvmArgs.orEmpty().filterNot { it.startsWith("-Dcompose.application.resources.dir=") } + listOf(
        "-Dcompose.application.resources.dir=${root.resolve("app-resources").absolutePath}",
        "-Duser.home=${home.absolutePath}",
        "-Djava.util.prefs.userRoot=${home.resolve("preferences").absolutePath}",
        "-Djava.io.tmpdir=${home.resolve("tmp").absolutePath}",
      ) + if (System.getProperty("os.name").contains("linux", true)) {
        listOf("-Dskiko.renderApi=SOFTWARE_COMPAT")
      } else {
        emptyList()
      }
    }
  }
}
