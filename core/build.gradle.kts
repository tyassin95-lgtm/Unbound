plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlin.stdlib)
    api(libs.coroutines.core)
    api(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

tasks.withType<Test> {
    useJUnit()
    maxHeapSize = "2g"
    testLogging { showStandardStreams = false }
}
