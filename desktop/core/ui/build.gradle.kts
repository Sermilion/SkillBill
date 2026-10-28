plugins {
  id("skillbill.desktop.kmp.library")
  id("skillbill.desktop.compose")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(projects.core.designsystem)
    implementation(projects.core.domain)
  }
}
