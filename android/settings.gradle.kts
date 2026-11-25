// La copie de Maven Central chez Google est essayée en premier : Maven Central refuse parfois les builds
// GitHub quand ils sont trop nombreux (erreur 429). Maven Central reste en secours.
pluginManagement {
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "dz-carte"
include(":app")
