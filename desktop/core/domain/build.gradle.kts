plugins {
  id("skillbill.desktop.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(projects.core.common)
    api(libs.kotlinx.coroutines.core)
  }
}
