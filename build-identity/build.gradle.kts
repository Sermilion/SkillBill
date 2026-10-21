plugins {
  `kotlin-dsl`
}

group = "dev.skillbill.buildidentity"

kotlin {
  jvmToolchain(21)
}

tasks.validatePlugins {
  enableStricterValidation = true
  failOnWarning = true
}

gradlePlugin {
  plugins {
    create("buildIdentity") {
      id = "skillbill.build-identity"
      implementationClass = "dev.skillbill.buildidentity.BuildIdentityPlugin"
    }
  }
}
