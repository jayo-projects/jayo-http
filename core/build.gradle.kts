import org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION as KOTLIN_VERSION
import kotlin.jvm.optionals.getOrNull

println("Using Gradle version: ${gradle.gradleVersion}")
println("Using Kotlin compiler version: $KOTLIN_VERSION")
println("Using Java compiler version: ${JavaVersion.current()}")

plugins {
    id("jayo-commons")
    id("jayo.build.optional-dependencies")
}

val versionCatalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(lib: String) =
    versionCatalog.findVersion(lib).getOrNull()?.requiredVersion
        ?: throw GradleException("Version '$lib' is not specified in the toml version catalog")

dependencies {
    api("dev.jayo:jayo:${catalogVersion("jayo")}")

    optional("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation(project(":jayo-http-testing-support"))
}
