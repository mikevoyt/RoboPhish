package robophish.model

import okio.BufferedSource
import okio.buffer
import okio.source

val showJson get() = loadFile("show.json")
val showsJson get() = loadFile("shows.json")
val yearsJson get() = loadFile("years.json")

private fun loadFile(fileName: String): BufferedSource =
        ClassLoader.getSystemResourceAsStream(fileName)!!
                .source()
                .buffer()
