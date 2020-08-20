plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.fabric.io/public")
    jcenter()
}

dependencies {
    implementation("com.android.tools.build:gradle:4.0.1")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.0")
    implementation("com.google.gms:google-services:4.3.3")
    implementation("com.google.firebase:firebase-crashlytics-gradle:2.2.0")
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}
