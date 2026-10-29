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
    implementation(libs.navigation3.ui)
    implementation(libs.lifecycle.viewmodel.navigation3)
  }
  sourceSets.jvmMain.dependencies {
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // The merged component names the session's runtime graph; its classes come from the installed runtime at run time.
    compileOnly("dev.skillbill:runtime-core")
  }
  sourceSets.jvmTest.dependencies {
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

tasks.named<Test>("jvmTest") {
  systemProperty("desktopRoot", rootDir.absolutePath)
  inputs.files(rootDir.resolve("settings.gradle.kts"), rootDir.resolve("build.gradle.kts")).withPropertyName("desktopRootBuildScripts")
}
