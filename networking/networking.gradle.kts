plugins {
    kotlin("jvm")
    kotlin("kapt")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    // api to expose networkingModule.kt to consumers
    api("org.kodein.di:kodein-di-generic-jvm:6.4.0")

    // api to expose Interceptors and HttpUrl to consumers
    api("com.squareup.okhttp3:okhttp:4.2.2")

    implementation("com.jakewharton.byteunits:byteunits:0.9.1")

    implementation("com.squareup.okio:okio:2.4.1")

    implementation("com.squareup.retrofit2:retrofit:2.6.2")
    implementation("com.squareup.retrofit2:converter-moshi:2.6.2")

    implementation("com.squareup.moshi:moshi:1.9.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.9.2")
    implementation("com.squareup.moshi:moshi-adapters:1.9.2")
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.9.2")

    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.5.2")
    testImplementation("com.google.truth:truth:1.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.2.2")
}
