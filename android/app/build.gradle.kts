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
        // Nom de l'appli sur le Play Store (elle remplace la première version de test, faite en TWA).
        applicationId = "app.tariki.twa"
        minSdk = 24
        targetSdk = 36
        // Sur le Play Store, le dernier versionCode de la version de test est 6 : le workflow donne 100 + n° du build.
        versionCode = providers.gradleProperty("versionCodeOverride").map { it.toInt() }.getOrElse(100)
        versionName = "1.0"
        // Fichiers téléchargés par l'appli au premier lancement (Releases du dépôt, données par le workflow).
        val releases = "https://github.com/SamEntertainmentGroup/Tariki/releases/download"
        buildConfigField("String", "MAP_URL", "\"${providers.gradleProperty("mapUrl").getOrElse("$releases/map-latest/algeria-map.mbtiles")}\"")
        buildConfigField("String", "BASE_URL", "\"${providers.gradleProperty("baseUrl").getOrElse("$releases/map-latest/algeria-base.geojson.gz")}\"")
        buildConfigField("String", "ROUTING_URL", "\"${providers.gradleProperty("routingUrl").getOrElse("$releases/routes-latest/algeria-routing.bin.gz")}\"")
        buildConfigField("String", "ALERTS_URL", "\"${providers.gradleProperty("alertsUrl").getOrElse("https://tariki.app/wp-admin/admin-ajax.php")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clé d'envoi du Play Store (secrets GitHub) : la même pour l'APK de test et l'AAB du Play Store.
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
        getByName("release") {
            isMinifyEnabled = false
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
