import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
}

// Exporta el esquema de Room a /schemas para poder escribir migraciones reales.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// ── Firma de release ──────────────────────────────────────────────────────────
// La clave NO vive en el repo (que es público). Para firmar de verdad, creá
// `keystore.properties` en la raíz del proyecto —está en .gitignore— con:
//
//     storeFile=/ruta/absoluta/a/digitador-avicola.jks
//     storePassword=...
//     keyAlias=digitador
//     keyPassword=...
//
// Y generá la clave con (te pedirá las contraseñas de forma interactiva):
//
//     keytool -genkeypair -v -keystore digitador-avicola.jks \
//       -alias digitador -keyalg RSA -keysize 4096 -validity 10000
//
// GUARDÁ ESE .jks Y SUS CONTRASEÑAS FUERA DEL EQUIPO. Si se pierden, las
// actualizaciones ya no se pueden instalar encima de la app instalada, y como
// los datos viven solo en el teléfono (allowBackup=false), reinstalar significa
// perder los lotes digitados.
//
// Sin ese archivo el build NO se rompe: release sigue firmando con la clave de
// debug, que sirve para probar pero no para distribuir.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hayKeystorePropia = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    namespace = "com.digitador.avicola"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.digitador.avicola"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.9"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hayKeystorePropia) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Con keystore.properties → firma propia, distribuible.
            // Sin él → firma de debug, para que el build siga funcionando (ver arriba).
            signingConfig = signingConfigs.getByName(if (hayKeystorePropia) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Permite a Room verificar y generar migraciones contra el esquema versionado.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // POI trae duplicados
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
}

// Avisa al armar un release sin clave propia: ese APK sirve para probar, no para
// repartir. Es fácil no darse cuenta, porque el build termina bien igual.
tasks.configureEach {
    if (name == "assembleRelease" && !hayKeystorePropia) {
        doFirst {
            logger.warn(
                "AVISO: no hay keystore.properties → este APK de release va firmado con la " +
                "clave de DEBUG. Sirve para probar, no para distribuir. Ver app/build.gradle.kts."
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    debugImplementation(libs.androidx.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // DataStore (preferencias)
    implementation(libs.androidx.datastore)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // JSON
    implementation(libs.gson)

    // Excel export
    implementation(libs.poi.ooxml)

    // Tests unitarios. El motor de cálculo corre en JVM pura; los repositorios y la
    // importación .davi necesitan Room y un Context, así que van con Robolectric.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
