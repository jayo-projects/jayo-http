import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":jayo-http"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

kotlin {
    explicitApi = ExplicitApiMode.Disabled
}