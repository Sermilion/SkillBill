plugins {
  id("skillbill.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(projects.core.domain)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    implementation(projects.core.common)
  }
}
