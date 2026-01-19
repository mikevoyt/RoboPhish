plugins {
    kotlin("jvm")
    kotlin("kapt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    // api to expose networkingModule.kt to consumers
    api("org.kodein.di:kodein-di-generic-jvm:6.4.0")

    // api to expose Interceptors and HttpUrl to consumers
    api("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.jakewharton.byteunits:byteunits:0.9.1")

    implementation("com.squareup.okio:okio:3.9.0")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")

    implementation("com.squareup.moshi:moshi:1.15.1")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    implementation("com.squareup.moshi:moshi-adapters:1.15.1")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.google.truth:truth:1.4.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
