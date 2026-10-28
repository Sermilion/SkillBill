plugins {
  `kotlin-dsl`
}

group = "dev.skillbill.desktop.buildlogic"

kotlin {
  jvmToolchain(21)
}

dependencies {
  implementation(libs.kotlin.gradle.plugin)
  implementation(libs.kotlin.compose.compiler.gradle.plugin)
  implementation(libs.kotlin.serialization.gradle.plugin)
  implementation(libs.compose.gradle.plugin)
  implementation(libs.ksp.gradle.plugin)
  implementation(libs.asm)

  testImplementation(gradleTestKit())
  // Build logic compiles with Gradle's embedded Kotlin, which cannot read the app's newer kotlin-test metadata.
  testImplementation(embeddedKotlin("test"))
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
      id = "skillbill.desktop.kmp.library"
      implementationClass = "dev.skillbill.desktop.buildlogic.KmpLibraryConventionPlugin"
    }
    register("compose") {
      id = "skillbill.desktop.compose"
      implementationClass = "dev.skillbill.desktop.buildlogic.KmpComposeConventionPlugin"
    }
    register("kotlinInject") {
      id = "skillbill.desktop.kotlin.inject"
      implementationClass = "dev.skillbill.desktop.buildlogic.KotlinInjectConventionPlugin"
    }
    register("kotlinSerialization") {
      id = "skillbill.desktop.kotlin.serialization"
      implementationClass = "dev.skillbill.desktop.buildlogic.KotlinSerializationConventionPlugin"
    }
    register("feature") {
      id = "skillbill.desktop.feature"
      implementationClass = "dev.skillbill.desktop.buildlogic.FeatureConventionPlugin"
    }
    register("desktopApplication") {
      id = "skillbill.desktop.application"
      implementationClass = "dev.skillbill.desktop.buildlogic.DesktopApplicationConventionPlugin"
    }
  }
}
