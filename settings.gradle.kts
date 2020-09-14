plugins {
    id("com.gradle.enterprise") version "3.1.1"
}

include(":mobile", "networking")

rootProject.name = "Never-Ending-Splendor"

rootProject.children.forEach {
    if(file("${it.projectDir}/${it.name}.gradle.kts").exists()) {
        it.buildFileName = "${it.name}.gradle.kts"
    } else {
        it.buildFileName = "${it.name}.gradle"
    }
}

gradleEnterprise {
    buildScan {
        publishAlways()
        buildScan {
            termsOfServiceUrl = "https://gradle.com/terms-of-service"

            // pass in to gradle with -DROBOPHISH_ACCEPT_BUILD_SCAN_AGREEMENT=yes or add to
            // gradle.properties with systemProp.ROBOPHISH_ACCEPT_BUILD_SCAN_AGREEMENT=yes
            termsOfServiceAgree = System.getProperty("ROBOPHISH_ACCEPT_BUILD_SCAN_AGREEMENT", "no")
        }
    }
}
