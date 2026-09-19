package co.byite.focus.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** The core's main sources must not touch JVM-only packages so they can move to Kotlin Multiplatform. */
class NoJavaImportTest {
    private val forbidden = Regex("(^|[^A-Za-z0-9_.\"'])javax?\\.[a-z]")

    @Test
    fun mainSourcesDoNotUseJvmPackages() {
        val root = File("src/main/kotlin")
        assertTrue(root.isDirectory, "run from the focus-core module directory: ${root.absolutePath}")
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (forbidden.containsMatchIn(line)) offenders.add("${file.path}:${i + 1}: $line")
            }
        }
        if (offenders.isNotEmpty()) fail("JVM package usage in main sources:\n" + offenders.joinToString("\n"))
    }
}
