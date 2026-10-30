plugins {
  id("skillbill.desktop.kmp.library")
}

kotlin {
  sourceSets.jvmMain.dependencies {
    api(projects.core.common)
    api(projects.core.domain)
    api(projects.core.data)
    api(libs.kotlinx.coroutines.test)
  }
}
