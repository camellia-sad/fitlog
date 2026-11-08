import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
val socialConfig = Properties().apply {
    rootProject.file(".local-tools/social.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun socialString(name: String): String = "\"" + socialConfig.getProperty(name, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.lzk.fitlog"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.lzk.fitlog"
        minSdk = 26
        targetSdk = 36
        versionCode = 29
        versionName = "0.9.0-dev"
        val updateUrl=socialConfig.getProperty("UPDATE_MANIFEST_URL")
            ?: (socialConfig.getProperty("SUPABASE_URL","").trimEnd('/')+"/storage/v1/object/public/fitlog-updates/latest.json")
        buildConfigField("String","UPDATE_MANIFEST_URL","\""+updateUrl.replace("\\","\\\\").replace("\"","\\\"")+"\"")
        buildConfigField("String", "SUPABASE_URL", socialString("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", socialString("SUPABASE_PUBLISHABLE_KEY"))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test)
    androidTestImplementation(libs.androidx.test.junit)
}
