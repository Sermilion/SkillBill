plugins {
  id("skillbill.kmp.compose")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    api(compose.material3)
  }
}
