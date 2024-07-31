pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "jayo-http-root"

include(":jayo-http")
include(":jayo-http-testing-support")

project(":jayo-http").projectDir = file("./core")
project(":jayo-http-testing-support").projectDir = file("./testing-support")
