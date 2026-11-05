plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    applicationName = "blinds"
    mainClass.set("dev.dalibor.blinds.cli.MainKt")
    // Faster startup matters more than peak performance for a command that runs for a second.
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-Xshare:auto")
}

dependencies {
    implementation(project(":gateway"))

    testImplementation("junit:junit:4.13.2")
}
