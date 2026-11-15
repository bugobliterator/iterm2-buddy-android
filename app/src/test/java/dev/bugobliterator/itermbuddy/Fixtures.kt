package dev.bugobliterator.itermbuddy

import java.io.File

/** Reads the repo's golden wire fixtures; the test task passes their location in `fixtures.dir`. */
object Fixtures {
    private val fixturesDir: File by lazy {
        val path = checkNotNull(System.getProperty("fixtures.dir")) {
            "fixtures.dir is not set; run tests through Gradle"
        }
        File(path)
    }

    /** Returns the UTF-8 text of [path], relative to `fixtures/`. */
    fun read(path: String): String {
        val file = fixturesDir.resolve(path)
        check(file.isFile) { "No fixture at $file" }
        return file.readText(Charsets.UTF_8)
    }

    /** Returns the paths, relative to `fixtures/`, of the `.json` files in [dir], sorted by name. */
    fun list(dir: String): List<String> {
        val directory = fixturesDir.resolve(dir)
        check(directory.isDirectory) { "No fixture directory at $directory" }
        return directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }!!
            .map { "$dir/${it.name}" }
            .sorted()
    }
}
