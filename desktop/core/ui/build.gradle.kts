plugins {
  id("skillbill.desktop.kmp.library")
  id("skillbill.desktop.compose")
  id("skillbill.desktop.kotlin.inject")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(projects.core.designsystem)
    api(projects.core.domain)
    api(libs.navigation3.runtime)
    api(libs.lifecycle.viewmodel)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.kotlinx.coroutines.core)
  }
}
