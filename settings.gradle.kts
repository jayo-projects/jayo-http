pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "jayo-http-root"

include(":jayo-http")

project(":jayo-http").projectDir = file("./core")
