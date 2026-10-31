import java.net.URI

plugins {
    id("com.android.application")
    id("com.google.firebase.crashlytics")
    id("com.google.gms.google-services")
}

val accountApiUrl = providers.gradleProperty("ROADCONQUEST_ACCOUNT_API_URL").getOrElse(
    "https://roadconquest-api.22abongiovanni.workers.dev"
)
val mapGatewayUrl = providers.gradleProperty("ROADCONQUEST_MAP_GATEWAY_URL").getOrElse(
    "https://roadconquest-maps.22abongiovanni.workers.dev"
)
for (endpoint in listOf(mapGatewayUrl).filter { it.isNotBlank() }) {
    val uri = URI(endpoint)
    require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) {
        "Map service origins must use HTTPS without embedded credentials, query or fragment."
    }
}
val placeOverlayConfigUrl = providers.gradleProperty("ROADCONQUEST_PLACE_OVERLAY_CONFIG_URL").getOrElse(
    "https://raw.githubusercontent.com/AntnyGamer/Road-Conquest/main/OVERLAY_PROVIDER.txt"
)
// Explicit opt-in for an internal Play build; ordinary builds remain hidden.
val leaderboardsVisible = providers.gradleProperty("ROADCONQUEST_LEADERBOARDS_VISIBLE")
    .getOrElse("false").toBooleanStrict()

android {
    namespace = "com.roadconquest.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roadconquest.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 97
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "120000"
        val escapedAccountApiUrl = accountApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPlaceOverlayConfigUrl = placeOverlayConfigUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "ACCOUNT_API_URL", "\"$escapedAccountApiUrl\"")
        buildConfigField("String", "MAP_GATEWAY_URL", "\"${mapGatewayUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PLACE_OVERLAY_CONFIG_URL", "\"$escapedPlaceOverlayConfigUrl\"")
        buildConfigField("boolean", "LEADERBOARDS_VISIBLE", leaderboardsVisible.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-3940256099942544/9214589741\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"ca-app-pub-3940256099942544/5224354917\"")
        }
        release {
            manifestPlaceholders["admobAppId"] = "ca-app-pub-4489303482171969~1528361127"
            buildConfigField("String", "ADMOB_BANNER_ID", "\"ca-app-pub-4489303482171969/1295506962\"")
            buildConfigField("String", "ADMOB_REWARDED_ID", "\"ca-app-pub-4489303482171969/2621950945\"")
            // R8 reduces shipped bytecode and embeds the mapping metadata Play needs to
            // deobfuscate Java/Kotlin crashes. Keep resource shrinking off to avoid changing
            // resource reachability/packaging behavior solely for size.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

tasks.withType<Test>().configureEach {
    // Isolate Robolectric's legacy UI, native Canvas and SQLite backends on every host.
    // Sharing their native runtime can corrupt JNI bindings on Windows and Linux.
    forkEvery = 1
    doFirst {
        require(javaLauncher.get().metadata.languageVersion.asInt() >= 21) {
            "The Android 17 Robolectric tests require JDK 21 or newer. Set Android Studio's Gradle JDK or JAVA_HOME accordingly."
        }
    }
    // Robolectric's Android 17 shared-memory interceptor accesses file-descriptor internals.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:35.1.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.google.android.gms:play-services-ads:25.5.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("androidx.core:core:1.19.1")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
