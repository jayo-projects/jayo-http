import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode.Disabled
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
    implementation("org.jsoup:jsoup:${catalogVersion("jsoup")}")
    implementation("com.squareup.okhttp3:mockwebserver3-junit5:${catalogVersion("okhttp")}")

    testImplementation(testFixtures(project(":jayo-http")))
    testImplementation("org.apache.httpcomponents.client5:httpclient5:${catalogVersion("httpClient5")}") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    testImplementation("org.eclipse.jetty:jetty-client:${catalogVersion("jetty")}")
    testImplementation("org.asynchttpclient:async-http-client:${catalogVersion("asyncHttpClient")}")
}

kotlin {
    // for all targets: main / test / testFixtures
    compilerOptions {
        explicitApi = Disabled
    }
}
