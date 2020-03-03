plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    maven { url = uri("https://maven.fabric.io/public") }
    jcenter()
}

dependencies {
    implementation("com.android.tools.build:gradle:3.5.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.60")
    implementation("com.google.gms:google-services:4.3.3")
    implementation("com.google.firebase:firebase-crashlytics-gradle:2.0.0-beta02")
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}
