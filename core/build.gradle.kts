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

    optional("org.jetbrains.kotlin:kotlin-stdlib")

    testImplementation(testFixtures("dev.jayo:jayo:${catalogVersion("jayo")}"))
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
        // Build jayo.http/internal/publicsuffix/EmbeddedPublicSuffixList.kt
        "publicSuffixListBytes" to listBytes
    )
}

sourceSets.main {
    java.srcDir(copyJavaTemplates.map { it.outputs })
}
