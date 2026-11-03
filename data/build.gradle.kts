plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}
android {
    namespace = "dev.lzk.fitlog.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
// Migration fixtures must include the schema emitted by the current compilation.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }.configureEach {
    dependsOn("ksp" + name.removePrefix("merge").removeSuffix("AndroidTestAssets") + "Kotlin")
}
dependencies {
    implementation(project(":domain"))
    api(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
