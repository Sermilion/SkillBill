plugins {
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")
  id("skillbill.governed-resources")
}

dependencies {
  implementation(project(":runtime-ports"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-infra:skills"))
  implementation(project(":runtime-infra:contracts"))
  implementation(project(":runtime-infra:host"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.jackson.databind)
  implementation(libs.jackson.dataformat.yaml)
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-infra:host")))
  testImplementation(testFixtures(project(":runtime-infra:skills")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.jimfs)
  testImplementation(libs.kotlin.test)
}

tasks.named<Test>("repoTest") {
  inputs.files(
    fileTree(rootProject.layout.projectDirectory.dir("..")) {
      include("**/agent/history.md", "**/agent/decisions.md")
      exclude("**/build/**", "**/.gradle/**", "**/node_modules/**", ".git/**")
    },
  ).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("boundaryMemoryFiles")
}

governedResources {
  sourceRoot.set(rootProject.layout.projectDirectory.dir("../orchestration/review-orchestrator"))
  destination.set("skillbill/review")

  copy(
    "copySpecialistContract",
    "specialist-contract.md",
    "Authoritative delegated-review specialist contract",
  )
}
