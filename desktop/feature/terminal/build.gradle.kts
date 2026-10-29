plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
  }
  sourceSets.jvmMain.dependencies {
    implementation(libs.pty4j)
    implementation(libs.jediterm.core)
    implementation(libs.jediterm.ui)
  }
}

dependencies {
  // pty4j and jediterm pull newer annotations and slf4j-api than the runtime; verifySharedRuntimeVersions keeps them on the runtime's versions.
  constraints {
    add("jvmMainImplementation", libs.jetbrains.annotations) {
      version { strictly(libs.versions.jetbrains.annotations.get()) }
    }
    add("jvmMainImplementation", libs.slf4j.api)
  }
}
