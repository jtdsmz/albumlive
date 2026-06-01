plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

group = providers.gradleProperty("group").getOrElse("com.github.jtdsmz")
version = providers.gradleProperty("version").getOrElse("0.1.0")

android {
    namespace = "io.github.jtdsmz.albumlive"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "albumlive"
            version = project.version.toString()

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("AlbumLive")
                description.set("Android album and live photo capability library.")
                url.set("https://github.com/jtdsmz/albumlive")
            }
        }
    }

    repositories {
        maven {
            name = "albumLiveBuild"
            url = uri(layout.buildDirectory.dir("maven-repo"))
        }
    }
}
