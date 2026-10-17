plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
  `java-test-fixtures`
}

dependencies {
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-ports"))
  implementation(project(":runtime-contracts"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.sqlite.jdbc)
  testImplementation(libs.junit.jupiter)
  testImplementation(project(":runtime-infra:contracts"))
  testImplementation(libs.kotlin.test)
  testFixturesImplementation(project(":runtime-ports"))
  testFixturesImplementation(project(":runtime-contracts"))
  testFixturesImplementation(project(":runtime-domain"))
  testFixturesImplementation(libs.sqlite.jdbc)
}
