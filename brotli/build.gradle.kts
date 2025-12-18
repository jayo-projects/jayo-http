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
    api("org.brotli:dec:${catalogVersion("brotli")}")
}
