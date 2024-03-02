import org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION as KOTLIN_VERSION

println("Using Gradle version: ${gradle.gradleVersion}")
println("Using Kotlin compiler version: $KOTLIN_VERSION")
println("Using Java compiler version: ${JavaVersion.current()}")

plugins {
    id("jayo.build.optional-dependencies")
}

dependencies {
    api("dev.jayo:jayo:${property("jayoVersion")}")
    
    optional("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation(project(":jayo-http-testing-support"))
}
