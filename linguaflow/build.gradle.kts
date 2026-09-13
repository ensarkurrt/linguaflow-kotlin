plugins {
  id("com.android.library")
  id("com.vanniktech.maven.publish")
}

group = "dev.linguaflow"
version = "0.1.0"

android {
  namespace = "dev.linguaflow"
  compileSdk = 36
  defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
  testOptions { unitTests.all { it.useJUnitPlatform() } }
}

mavenPublishing {
  publishToMavenCentral(automaticRelease = true)
  if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
  coordinates(group.toString(), "linguaflow-android", version.toString())
  pom {
    name.set("LinguaFlow Android SDK")
    description.set("Type-safe remote localization runtime for Android")
    url.set("https://github.com/ensarkurrt/linguaflow-kotlin")
    licenses {
      license {
        name.set("MIT License")
        url.set("https://opensource.org/license/mit")
        distribution.set("repo")
      }
    }
    developers {
      developer {
        id.set("ensarkurrt")
        name.set("Ensar Kurt")
      }
    }
    scm {
      url.set("https://github.com/ensarkurrt/linguaflow-kotlin")
      connection.set("scm:git:git://github.com/ensarkurrt/linguaflow-kotlin.git")
      developerConnection.set("scm:git:ssh://git@github.com/ensarkurrt/linguaflow-kotlin.git")
    }
  }
}

dependencies {
  implementation("com.google.code.gson:gson:2.13.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
  implementation("com.google.android.play:integrity:1.6.0")
  testImplementation("org.junit.jupiter:junit-jupiter:6.0.1")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
  testImplementation("org.json:json:20260814")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.1")
}
