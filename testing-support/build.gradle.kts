import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import kotlin.jvm.optionals.getOrNull

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

val versionCatalog: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun catalogVersion(lib: String) =
    versionCatalog.findVersion(lib).getOrNull()?.requiredVersion
        ?: throw GradleException("Version '$lib' is not specified in the toml version catalog")

dependencies {
    implementation(platform("org.junit:junit-bom:${catalogVersion("junit")}"))

    api(project(":jayo-http"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.junit.jupiter:junit-jupiter-params")
    api("org.hamcrest:hamcrest:${catalogVersion("hamcrest")}")
    api("org.bouncycastle:bcprov-jdk18on:${catalogVersion("bouncycastle")}")
    implementation("org.bouncycastle:bcpkix-jdk18on:${catalogVersion("bouncycastle")}")
    implementation("org.bouncycastle:bctls-jdk18on:${catalogVersion("bouncycastle")}")
    api("org.conscrypt:conscrypt-openjdk-uber:${catalogVersion("conscrypt")}")
    // classifier for AmazonCorrettoCryptoProvider = linux-x86_64
    api("software.amazon.cryptools:AmazonCorrettoCryptoProvider:${catalogVersion("amazonCorretto")}:linux-x86_64")

    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

kotlin {
    explicitApi = ExplicitApiMode.Disabled
}
