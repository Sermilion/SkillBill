plugins {
  id("skillbill.desktop.kotlin.inject")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    api(libs.androidx.datastore.preferences.core)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}
