plugins {
  id("skillbill.desktop.kmp.library")
  id("skillbill.desktop.compose")
}

kotlin {
  sourceSets.jvmTest.dependencies {
    implementation(libs.kotlinx.coroutines.test)
  }

  sourceSets.commonMain.dependencies {
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    api(compose.animation)
    implementation(compose.components.resources)
  }
}
