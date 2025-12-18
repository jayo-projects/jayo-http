plugins {
    id("jayo-commons")
}

dependencies {
    api(project(":jayo-http"))

    testImplementation(testFixtures(project(":jayo-http")))
}
