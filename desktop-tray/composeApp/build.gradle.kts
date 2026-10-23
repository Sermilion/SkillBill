plugins {
  id("skillbill.desktop.application")
  id("skillbill.kotlin.inject")
  id("skillbill.kotlin.serialization")
  id("skillbill.build-identity")
}

group = "dev.skillbill.tray"
version = "0.1.0"

val runtimeRoot = rootDir.parentFile.resolve("runtime-kotlin")

tasks.named<dev.skillbill.buildidentity.WriteBuildIdentityTask>("writeBuildIdentity") {
  capabilityDeclaration.set(
    runtimeRoot.resolve("runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/DesktopReadSchema.kt"),
  )
}

kotlin {
  // The merged application component is generated from the contributions of every module it depends on.
  sourceSets.commonMain.dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.savedstate)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.ui)
    implementation(projects.feature.work)
    implementation(projects.feature.history)
    implementation(projects.feature.detail)
    implementation(projects.feature.settings)
  }
  sourceSets.jvmMain.dependencies {
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.compose.native.tray)
    implementation(libs.nucleus.notification.common)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.autolaunch)
    // The bootstrap loads runtime classes from the installed runtime after checking its build identity.
    compileOnly("dev.skillbill:runtime-core")
    compileOnly("dev.skillbill:runtime-contracts")
    implementation(projects.core.designsystem)
  }
  sourceSets.jvmTest.dependencies {
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation("dev.skillbill:runtime-infra-host")
    implementation("dev.skillbill:runtime-infra-contracts")
    implementation(libs.kotlin.compiler.embeddable)
    implementation(libs.compose.ui.test)
    implementation(projects.core.testing)
  }
}

dependencies {
  add("jvmTestImplementation", testFixtures("dev.skillbill:runtime-infra-sqlite"))
  // Projects without a build script (the implicit ":core" parent) own no sources and no manifest.
  rootProject.subprojects.filter { it.buildFile.exists() }.forEach { add("architectureManifests", project(it.path)) }
}

compose.desktop {
  application {
    mainClass = "dev.skillbill.tray.bootstrap.DesktopBootstrapKt"
  }
}

// Launches the distribution against a disposable home and store with an empty PATH, so no CLI can be found or run. It is not part
// of `check`: it needs a built distribution, so validation and CI invoke it explicitly.
tasks.register<Exec>("packagedDataSmoke") {
  group = "verification"
  description = "Loads work data through the createDistributable image's own launcher, JDK and libraries."
  dependsOn("createDistributable", ":core:data:compileTestKotlinJvm", gradle.includedBuild("runtime-kotlin").task(":runtime-cli:installDist"))
  val python = if (System.getProperty("os.name").contains("windows", true)) "python" else "python3"
  commandLine(
    python,
    rootDir.resolve("scripts/packaged-data-smoke.py").absolutePath,
    runtimeRoot.resolve("runtime-cli/build/install/runtime-cli").absolutePath,
  )
}

tasks.named<Test>("jvmTest") {
  maxHeapSize = "2g"
  systemProperty("desktopRoot", rootDir.absolutePath)
  systemProperty("runtimeRoot", runtimeRoot.absolutePath)
  inputs.files(rootDir.resolve("settings.gradle.kts"), rootDir.resolve("build.gradle.kts")).withPropertyName("desktopRootBuildScripts")
  inputs.files(fileTree(rootDir) {
    include("**/*.kt")
    exclude("**/build/**", "**/.gradle/**", "build-logic/**")
  }).withPropertyName("desktopArchitectureSources")
  inputs.file(runtimeRoot.resolve("runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeModuleCatalog.kt"))
    .withPropertyName("runtimeModuleCatalog")
  inputs.files(fileTree(runtimeRoot) {
    include("**/src/main/kotlin/**/*.kt")
    exclude("**/build/**", "**/.gradle/**")
  }).withPropertyName("runtimeArchitectureProductionSources")
}
