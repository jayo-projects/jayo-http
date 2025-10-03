import kotlin.jvm.optionals.getOrNull

plugins {
    id("jayo-commons")
}

val versionCatalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(lib: String) =
    versionCatalog.findVersion(lib).getOrNull()?.requiredVersion
        ?: throw GradleException("Version '$lib' is not specified in the toml version catalog")

dependencies {
    api(project(":jayo-http"))

    testImplementation(testFixtures(project(":jayo-http")))
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:${catalogVersion("okhttp")}")
}
