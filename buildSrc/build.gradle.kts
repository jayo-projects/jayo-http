plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
}

val kotlinVersion by extra(property("kotlinVersion"))
val dokkaPluginVersion by extra(property("dokkaPluginVersion"))
val koverPluginVersion by extra(property("koverPluginVersion"))

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:$kotlinVersion"))
    
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable")
    implementation("org.jetbrains.kotlin:kotlin-serialization")
    implementation("org.jetbrains.dokka:dokka-gradle-plugin:$dokkaPluginVersion")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:$koverPluginVersion")
}

gradlePlugin {
    plugins {
        create("optionalDependenciesPlugin") {
            id = "jayo.build.optional-dependencies"
            implementationClass = "jayo.build.OptionalDependenciesPlugin"
        }
    }
}
