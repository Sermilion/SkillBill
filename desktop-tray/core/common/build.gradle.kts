plugins {
  id("skillbill.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(libs.kotlinx.coroutines.core)
  }
}
