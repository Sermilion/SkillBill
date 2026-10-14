plugins {
  id("skillbill.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(projects.core.domain)
    implementation(projects.core.common)
  }
}
