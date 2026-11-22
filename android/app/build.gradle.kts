plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = System.getenv("KEYSTORE_PATH").orEmpty()
val keystorePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
// Nom de la clé dans le fichier et son mot de passe : secrets KEY_ALIAS et KEY_PASSWORD, sinon tariki et le mot de passe du fichier.
val keyAliasName = System.getenv("KEY_ALIAS").orEmpty().ifEmpty { "tariki" }
val keyPasswordValue = System.getenv("KEY_PASSWORD").orEmpty().ifEmpty { keystorePassword }

android {
    namespace = "com.dztrafic.carte"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dztrafic.carte"
        minSdk = 24
        targetSdk = 34
        versionCode = providers.gradleProperty("versionCodeOverride").map { it.toInt() }.getOrElse(10)
        versionName = "1.0"
        // Fichiers téléchargés par l'appli au premier lancement (Releases du dépôt, données par le workflow).
        val releases = "https://github.com/Mertah/Tariki/releases/download"
        buildConfigField("String", "MAP_URL", "\"${providers.gradleProperty("mapUrl").getOrElse("$releases/map-latest/algeria-map.mbtiles")}\"")
        buildConfigField("String", "BASE_URL", "\"${providers.gradleProperty("baseUrl").getOrElse("$releases/map-latest/algeria-base.geojson.gz")}\"")
        buildConfigField("String", "ROUTING_URL", "\"${providers.gradleProperty("routingUrl").getOrElse("$releases/routes-latest/algeria-routing.bin.gz")}\"")
        buildConfigField("String", "ALERTS_URL", "\"${providers.gradleProperty("alertsUrl").getOrElse("https://jarti.app/wp-admin/admin-ajax.php")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clé de signature fixe (fournie par GitHub) : indispensable pour mettre à jour par-dessus l'appli déjà installée.
    signingConfigs {
        if (keystorePath.isNotEmpty() && keystorePassword.isNotEmpty()) {
            create("tariki") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("tariki")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.1")
    implementation("androidx.core:core:1.13.1")
}
