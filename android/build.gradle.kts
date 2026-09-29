// AGP goes on the root build script classpath, next to the Kotlin plugin:
// AGP's built-in Kotlin support and the Kotlin plugin have to share one
// class loader. Left out for -Ppyp6.coreOnly=true, which builds the pure
// Kotlin core without Google's Maven repository.
buildscript {
    if (providers.gradleProperty("pyp6.coreOnly").orNull != "true") {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:9.3.2")
        }
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}
