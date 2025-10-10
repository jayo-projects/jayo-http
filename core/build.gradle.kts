import java.util.Base64
import org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION as KOTLIN_VERSION
import kotlin.jvm.optionals.getOrNull

println("Using Gradle version: ${gradle.gradleVersion}")
println("Using Kotlin compiler version: $KOTLIN_VERSION")
println("Using Java compiler version: ${JavaVersion.current()}")

plugins {
    id("jayo-commons")
    id("jayo.build.optional-dependencies")
    `java-test-fixtures`
}

val versionCatalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(lib: String) =
    versionCatalog.findVersion(lib).getOrNull()?.requiredVersion
        ?: throw GradleException("Version '$lib' is not specified in the toml version catalog")

dependencies {
    api("dev.jayo:jayo:${catalogVersion("jayo")}")
    api("dev.jayo:jayo-scheduler:${catalogVersion("jayo")}")

    optional("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation(testFixtures("dev.jayo:jayo:${catalogVersion("jayo")}"))
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:${catalogVersion("okhttp")}")
    testImplementation("org.hamcrest:hamcrest-library:${catalogVersion("hamcrest")}")
    testImplementation(project(":jayo-http-logging-interceptor"))

    testFixturesApi(testFixtures("dev.jayo:jayo:${catalogVersion("jayo")}"))
    testFixturesApi(testFixtures("dev.jayo:jayo-scheduler:${catalogVersion("jayo")}"))
    testFixturesImplementation("com.squareup.okhttp3:mockwebserver3:${catalogVersion("okhttp")}")
    testFixturesImplementation("org.junit.jupiter:junit-jupiter:${catalogVersion("junit")}")
    testFixturesImplementation("org.assertj:assertj-core:${catalogVersion("assertj")}")
}

fun ByteArray.toByteStringExpression(): String {
    return "\"${Base64.getEncoder().encodeToString(this@toByteStringExpression)}\""
}

val copyJavaTemplates by tasks.registering(Copy::class) {
    val javaTemplatesOutput = layout.buildDirectory.dir("generated/sources/javaTemplates")

    from("src/main/javaTemplates")
    into(javaTemplatesOutput)

    // Tag as an input to regenerate after an update
    inputs.file("src/test/resources/jayo/http/internal/publicsuffix/PublicSuffixDatabase.gz")

    val databaseGz = project.file("src/test/resources/jayo/http/internal/publicsuffix/PublicSuffixDatabase.gz")
    val listBytes = databaseGz.readBytes().toByteStringExpression()

    expand(
        // For jayo.http/internal/publicsuffix/EmbeddedPublicSuffixList.java
        "publicSuffixListBytes" to listBytes,
        // For jayo.http/internal/InternalVersion.java
        "projectVersion" to project.version,
    )
}

sourceSets.main {
    java.srcDir(copyJavaTemplates.map { it.outputs })
}
