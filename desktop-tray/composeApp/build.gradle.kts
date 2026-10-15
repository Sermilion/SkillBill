plugins {
  id("skillbill.desktop.application")
  id("skillbill.kotlin.inject")
}

group = "dev.skillbill.tray"
version = "0.1.0"

val runtimeRoot = rootDir.parentFile.resolve("runtime-kotlin")

kotlin {
  // The merged application component is generated from the contributions of every module it depends on.
  sourceSets.commonMain.dependencies {
    implementation(libs.kotlinx.coroutines.core)
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
    // The generated component names the runtime component type that the data layer provides per database session.
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation(projects.core.designsystem)
  }
  sourceSets.jvmTest.dependencies {
    implementation("dev.skillbill:runtime-infra-host")
    implementation("dev.skillbill:runtime-infra-contracts")
    implementation(libs.kotlin.compiler.embeddable)
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
    mainClass = "dev.skillbill.tray.composition.MainKt"
  }
}

tasks.named<Test>("jvmTest") {
  maxHeapSize = "2g"
  systemProperty("desktopRoot", rootDir.absolutePath)
  systemProperty("runtimeRoot", runtimeRoot.absolutePath)
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
