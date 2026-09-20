package co.byite.focus.engine

import co.byite.focus.core.aggregate.AggregatedSecond
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Data boundary of spec 9장 and directive C, enforced on the engine's sources:
 * 1. frame, bitmap, MediaPipe and landmark types exist only under `co.byite.focus.engine.pipeline.*`;
 * 2. the FeatureLogger accepts focus-core log models only (no array, buffer or bitmap parameter);
 * 3. the engine manifest declares no network permission, and its INTERNET line is a removal;
 * 4. the sample types handed out of the pipelines carry scalars only;
 * 5. the datatransport stubs (README "MediaPipe 원격 통계 로깅 차단") are exactly the documented set, and nothing else lives in src/main/java.
 */
class DataBoundaryTest {
    private val root = File("src/main/kotlin/co/byite/focus/engine")
    private val pipelinePrefix = File(root, "pipeline").path

    private val forbiddenOutsidePipeline = listOf(
        "com.google.mediapipe",
        "androidx.camera.core.ImageProxy",
        "android.graphics.Bitmap",
        "android.media.Image",
        "co.byite.focus.engine.pipeline.RgbaFrame",
        "java.nio.ByteBuffer",
    )

    @Test
    fun frameAndLandmarkTypesStayInsideThePipelinePackages() {
        assertTrue(root.isDirectory, "run from the engine module directory: ${root.absolutePath}")
        val offenders = ArrayList<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.startsWith(pipelinePrefix) }.forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("import ") && forbiddenOutsidePipeline.any { t.contains(it) }) offenders.add("${file.path}:${i + 1}: $t")
            }
        }
        if (offenders.isNotEmpty()) fail("frame/landmark types outside the pipeline packages:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun loggerAcceptsFocusCoreLogModelsOnly() {
        val allowedPackages = listOf("co.byite.focus.core.model", "co.byite.focus.core.aggregate")
        val methods = FeatureLogger::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue(methods.any { it.name == "append" && it.parameterTypes.contentEquals(arrayOf(AggregatedSecond::class.java)) })
        for (m in methods) for (p in m.parameterTypes) {
            val ok = p.isPrimitive || p == String::class.java || p == java.io.File::class.java || allowedPackages.any { p.name.startsWith(it) }
            if (!ok) fail("FeatureLogger.${m.name} takes ${p.name}; only focus-core log models are allowed")
            if (p.isArray) fail("FeatureLogger.${m.name} takes an array")
        }
        val fields = FeatureLogger::class.java.declaredFields
        for (f in fields) if (f.type.isArray) fail("FeatureLogger field ${f.name} is an array")
    }

    @Test
    fun manifestDeclaresNoNetworkPermission() {
        val manifest = File("src/main/AndroidManifest.xml").readLines()
        for ((i, line) in manifest.withIndex()) {
            if (line.contains("android.permission.INTERNET") || line.contains("android.permission.ACCESS_NETWORK_STATE")) {
                assertTrue(line.contains("tools:node=\"remove\""), "AndroidManifest.xml:${i + 1} must remove, not declare, the permission: $line")
            }
        }
    }

    @Test
    fun datatransportStubsAreExactlyTheDocumentedSet() {
        val javaRoot = File("src/main/java")
        val expected = listOf(
            "com/google/android/datatransport/Encoding.java",
            "com/google/android/datatransport/Event.java",
            "com/google/android/datatransport/Transformer.java",
            "com/google/android/datatransport/Transport.java",
            "com/google/android/datatransport/TransportFactory.java",
            "com/google/android/datatransport/cct/CCTDestination.java",
            "com/google/android/datatransport/runtime/Destination.java",
            "com/google/android/datatransport/runtime/TransportRuntime.java",
        ).sorted()
        val actual = javaRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(javaRoot).path.replace(File.separatorChar, '/') }.sorted().toList()
        assertTrue(actual == expected, "src/main/java must hold exactly the 8 datatransport stubs, got:\n" + actual.joinToString("\n"))
        for (f in expected) {
            val text = File(javaRoot, f).readText()
            assertTrue("import java.net" !in text && "HttpURLConnection" !in text && "Socket" !in text, "$f must not touch the network")
        }
    }

    @Test
    fun pipelineOutputsAreScalarsOnly() {
        val outputs = listOf(
            co.byite.focus.core.aggregate.FrameSample::class.java,
            co.byite.focus.core.aggregate.PoseSample::class.java,
            co.byite.focus.core.aggregate.SceneSample::class.java,
            co.byite.focus.core.aggregate.ImuSample::class.java,
            co.byite.focus.core.aggregate.DeviceSample::class.java,
            Class.forName("co.byite.focus.engine.pipeline.face.FaceFeatures"),
            Class.forName("co.byite.focus.engine.pipeline.pose.PoseScalars"),
            Class.forName("co.byite.focus.engine.pipeline.scene.SceneStats"),
        )
        for (c in outputs) for (f in c.declaredFields) {
            val t = f.type
            val boxed = setOf(Double::class.javaObjectType, Int::class.javaObjectType, Long::class.javaObjectType, Boolean::class.javaObjectType)
            val scalar = t.isPrimitive || t == String::class.java || t.isEnum || t in boxed || t.name.startsWith("co.byite.focus.core.model") ||
                t.name.startsWith("co.byite.focus.engine.pipeline.pose.PoseScalars")
            if (!scalar || t.isArray) fail("${c.simpleName}.${f.name} is ${t.name}; pipeline outputs must be scalars")
        }
    }
}
