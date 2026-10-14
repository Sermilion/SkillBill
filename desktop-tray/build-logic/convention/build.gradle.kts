plugins {
  `kotlin-dsl`
}

group = "dev.skillbill.tray.buildlogic"

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.compose.compiler.gradle.plugin)
  implementation(libs.kotlin.serialization.gradle.plugin)
  implementation(libs.compose.gradle.plugin)
  implementation(libs.ksp.gradle.plugin)

  testImplementation(gradleTestKit())
  testImplementation(libs.kotlin.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(platform(libs.junit.bom))
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val desktopCatalog = layout.projectDirectory.file("../../gradle/libs.versions.toml").asFile

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  inputs.file(desktopCatalog).withPropertyName("desktopVersionCatalog")
  systemProperty("skillbill.desktop.catalog", desktopCatalog.absolutePath)
  maxHeapSize = "1g"
}

tasks.validatePlugins {
  enableStricterValidation = true
  failOnWarning = true
}

gradlePlugin {
  plugins {
    register("kmpLibrary") {
      id = "skillbill.kmp.library"
      implementationClass = "dev.skillbill.tray.buildlogic.KmpLibraryConventionPlugin"
    }
    register("kmpCompose") {
      id = "skillbill.kmp.compose"
      implementationClass = "dev.skillbill.tray.buildlogic.KmpComposeConventionPlugin"
    }
    register("kotlinInject") {
      id = "skillbill.kotlin.inject"
      implementationClass = "dev.skillbill.tray.buildlogic.KotlinInjectConventionPlugin"
    }
    register("room") {
      id = "skillbill.room"
      implementationClass = "dev.skillbill.tray.buildlogic.RoomConventionPlugin"
    }
    register("quality") {
      id = "skillbill.quality"
      implementationClass = "dev.skillbill.tray.buildlogic.QualityConventionPlugin"
    }
    register("desktopApplication") {
      id = "skillbill.desktop.application"
      implementationClass = "dev.skillbill.tray.buildlogic.DesktopApplicationConventionPlugin"
    }
  }
}
