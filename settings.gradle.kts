include(":mobile", "networking")

rootProject.name = "RoboPhish"

rootProject.children.forEach {
    if(file("${it.projectDir}/${it.name}.gradle.kts").exists()) {
        it.buildFileName = "${it.name}.gradle.kts"
    } else {
        it.buildFileName = "${it.name}.gradle"
    }
}
