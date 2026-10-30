plugins {
  id("skillbill.desktop.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(libs.kotlinx.coroutines.core)
  }
}
