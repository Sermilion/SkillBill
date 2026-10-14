plugins {
  id("skillbill.kotlin.inject")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.core)
  }
}
