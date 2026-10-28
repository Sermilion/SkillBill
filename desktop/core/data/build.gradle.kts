plugins {
  id("skillbill.desktop.kmp.library")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
  }
  sourceSets.jvmMain.dependencies {
    // Runtime classes come from the installed runtime's class loader at run time, never from the app image.
    compileOnly("dev.skillbill:runtime-core")
    compileOnly("dev.skillbill:runtime-contracts")
  }
}
