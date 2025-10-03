pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "jayo-http-root"

include(":jayo-http")
include(":jayo-http-logging-interceptor")

project(":jayo-http").projectDir = file("./core")
project(":jayo-http-logging-interceptor").projectDir = file("./logging-interceptor")
