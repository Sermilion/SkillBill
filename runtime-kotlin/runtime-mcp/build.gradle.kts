plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")
  id("skillbill.governed-resources")

  id("skillbill.runtime-image")
  id("skillbill.build-identity")
}

dependencies {

  implementation(project(":runtime-application"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-core"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-engine"))
  implementation(project(":runtime-ports"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.json.schema.validator)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  constraints { runtimeOnly(libs.jetbrains.annotations) }
  ksp(libs.kotlin.inject.compiler)
  kspTest(libs.kotlin.inject.compiler)

  testImplementation(project(":runtime-infra:host"))
  testImplementation(project(":runtime-infra:workflow"))
  testImplementation(project(":runtime-infra:contracts"))
  testImplementation(project(":runtime-cli"))
  testImplementation(project(":runtime-infra:sqlite"))
  testImplementation(testFixtures(project(":runtime-infra:sqlite")))

  testImplementation(testFixtures(project(":runtime-core")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

application {
  mainClass.set("skillbill.mcp.core.MainKt")
}

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
}

governedResources {
  sourceRoot.set(rootProject.layout.projectDirectory.dir("../orchestration/contracts"))

  copy(
    "copyTelemetryEventSchema",
    "telemetry-event-schema.yaml",
    "SKILL-48: canonical telemetry-event schema",
    "skillbill/mcp/contracts",
  )
}

runtimeImage {
  imageBaseName.set("runtime-mcp")
}

tasks.named<dev.skillbill.buildidentity.WriteBuildIdentityTask>("writeBuildIdentity") {
  capabilityDeclaration.set(
    rootProject.layout.projectDirectory.file(
      "runtime-contracts/src/main/kotlin/skillbill/contracts/persistence/DesktopReadSchema.kt",
    ),
  )
}
