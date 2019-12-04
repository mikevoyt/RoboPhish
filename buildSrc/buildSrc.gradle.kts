plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    google()
    jcenter()
}

dependencies {
    implementation("com.android.tools.build:gradle:3.5.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.60")
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}
