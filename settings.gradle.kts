pluginManagement {
    val releasePluginVersion: String by settings
    
    plugins {
        id("net.researchgate.release") version releasePluginVersion
    }
}

rootProject.name = "jayo-http-root"

include(":jayo-http")

project(":jayo-http").projectDir = file("./core")
