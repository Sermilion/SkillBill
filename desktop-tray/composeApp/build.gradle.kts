plugins {
  id("skillbill.desktop.application")
  id("com.google.devtools.ksp")
  id("org.jetbrains.kotlin.plugin.serialization")
}

group = "dev.skillbill.tray"
version = "0.1.0"

val runtimeRoot = rootDir.parentFile.resolve("runtime-kotlin")

kotlin {
  sourceSets.jvmMain.dependencies {
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
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation(projects.core.common)
    implementation(projects.core.domain)
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
  add("kspJvm", libs.room.compiler)
  add("jvmTestImplementation", testFixtures("dev.skillbill:runtime-infra-sqlite"))
  add("architectureManifests", project(":composeApp"))
  add("architectureManifests", projects.core.common)
  add("architectureManifests", projects.core.domain)
  add("architectureManifests", projects.core.designsystem)
  add("architectureManifests", projects.core.testing)
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
    include("composeApp/src/**/*.kt", "core/*/src/**/*.kt")
    exclude("**/build/**")
  }).withPropertyName("desktopArchitectureSources")
  inputs.file(runtimeRoot.resolve("runtime-core/src/repoTest/kotlin/skillbill/architecture/RuntimeModuleCatalog.kt"))
    .withPropertyName("runtimeModuleCatalog")
  inputs.files(fileTree(runtimeRoot) {
    include("**/src/main/kotlin/**/*.kt")
    exclude("**/build/**", "**/.gradle/**")
  }).withPropertyName("runtimeArchitectureProductionSources")
}
