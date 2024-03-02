pluginManagement {
    val releasePluginVersion: String by settings
    
    plugins {
        id("net.researchgate.release") version releasePluginVersion
    }
}

rootProject.name = "jayo-http-root"

include(":jayo-http")
include(":jayo-http-testing-support")

project(":jayo-http").projectDir = file("./core")
project(":jayo-http-testing-support").projectDir = file("./testing-support")
