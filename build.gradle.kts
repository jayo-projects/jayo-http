plugins {
    `maven-publish`
    signing
    alias(libs.plugins.release)

    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.jmh) apply false
    alias(libs.plugins.shadow) apply false
}

subprojects {
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    // --------------- publishing ---------------

    publishing {
        repositories {
            maven {
                url = uri(layout.buildDirectory.dir("repos/releases"))
            }
        }

        publications {
            create<MavenPublication>("mavenJava") {
                pom {
                    name.set(project.name)
                    description.set("Jayo HTTP is a fast synchronous HTTP library based on Jayo for the JVM")
                    url.set("https://github.com/jayo-projects/jayo-http")

                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            name.set("pull-vert")
                            url.set("https://github.com/pull-vert")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/jayo-projects/jayo-http")
                        developerConnection.set("scm:git:git://github.com/jayo-projects/jayo-http.git")
                        url.set("https://github.com/jayo-projects/jayo-http.git")
                    }
                }
            }
        }
    }

    signing {
        // Require signing.keyId, signing.password and signing.secretKeyRingFile
        sign(publishing.publications)
    }
}

// when the Gradle version changes:
// -> execute ./gradlew wrapper, then remove .gradle directory, then execute ./gradlew wrapper again
tasks.wrapper {
    gradleVersion = "9.3.0"
    distributionType = Wrapper.DistributionType.ALL
}
