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

// Liftoff IDs are build-time configuration, not credentials. Empty values disable ads
// until the publisher has created and verified app/placement IDs in Liftoff Monetize.
fun liftoffProperty(key: String): String = providers.gradleProperty(key)
    .orElse(providers.environmentVariable(key)).getOrElse("").trim()
val liftoffAppId = liftoffProperty("ROADCONQUEST_LIFTOFF_APP_ID")
val liftoffBannerId = liftoffProperty("ROADCONQUEST_LIFTOFF_BANNER_ID")
val liftoffRewardedId = liftoffProperty("ROADCONQUEST_LIFTOFF_REWARDED_ID")
val liftoffRewardPoints = providers.gradleProperty("ROADCONQUEST_LIFTOFF_REWARD_POINTS")
    .getOrElse("25").toInt()
require(liftoffRewardPoints in 1..1000) { "Liftoff rewarded ad points must be between 1 and 1000." }
val liftoffIds = listOf(liftoffAppId, liftoffBannerId, liftoffRewardedId)
require(liftoffIds.all { it.isEmpty() } || liftoffIds.all { it.isNotEmpty() }) {
    "Provide all three ROADCONQUEST_LIFTOFF IDs, or leave all three unset."
}

android {
    namespace = "com.roadconquest.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.roadconquest.app"
        minSdk = 31
        targetSdk = 37
        versionCode = 101
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["timeout_msec"] = "120000"
        val escapedAccountApiUrl = accountApiUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        val escapedPlaceOverlayConfigUrl = placeOverlayConfigUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "ACCOUNT_API_URL", "\"$escapedAccountApiUrl\"")
        buildConfigField("String", "MAP_GATEWAY_URL", "\"${mapGatewayUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PLACE_OVERLAY_CONFIG_URL", "\"$escapedPlaceOverlayConfigUrl\"")
        buildConfigField("boolean", "LEADERBOARDS_VISIBLE", leaderboardsVisible.toString())
        buildConfigField("String", "LIFTOFF_APP_ID", "\"$liftoffAppId\"")
        buildConfigField("String", "LIFTOFF_BANNER_ID", "\"$liftoffBannerId\"")
        buildConfigField("String", "LIFTOFF_REWARDED_ID", "\"$liftoffRewardedId\"")
        buildConfigField("int", "LIFTOFF_REWARD_POINTS", liftoffRewardPoints.toString())
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8 and optimized resource shrinking remove unused shipped code/resources.
            // MapLibre's own consumer rules protect JNI/reflection entry points.
            isMinifyEnabled = true
            isShrinkResources = true
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

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
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
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("com.android.billingclient:billing:9.1.0")
    implementation("com.vungle:vungle-ads:7.8.0")
    implementation("com.google.android.gms:play-services-ads-identifier:18.3.0")
    implementation("com.google.android.play:integrity:1.6.0")
    implementation("androidx.core:core:1.19.1")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
