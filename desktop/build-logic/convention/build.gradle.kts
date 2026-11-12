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
  inputs.file("../../app/build.gradle.kts").withPropertyName("sourceRunLauncher")
  inputs.file("../../../runtime-kotlin/runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/RuntimeDatabaseEnvironmentKeys.kt")
    .withPropertyName("runtimeDatabaseEnvironmentKeys")
  systemProperty("skillbill.desktop.catalog", desktopCatalog.absolutePath)
  maxHeapSize = "1g"
  val taskKey = "${rootDir.absolutePath}$path".hashCode().toUInt().toString(radix = 16).padStart(8, '0')
  val fallbackTempDir = temporaryDir
  doFirst {
    val runScratch =
      System.getenv("SKILL_BILL_RUN_SCRATCH_DIR")?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isDirectory)
    val tempDir = runScratch?.resolve("t-$taskKey") ?: fallbackTempDir
    tempDir.mkdirs()
    (this as Test).jvmArgs("-Djava.io.tmpdir=${tempDir.absolutePath}")
    environment.remove("SKILL_BILL_RUN_SCRATCH_DIR")
  }
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
