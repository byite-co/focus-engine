package co.byite.focus.tools.gtdiff

import co.byite.focus.core.gt.GtReport
import co.byite.focus.core.log.FocusJson
import co.byite.focus.core.log.JsonlCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GtDiffMainTest {
    private val samples = File("../samples")

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = GtDiffMain.run(args.toList(), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    @Test
    fun sampleT2RunsEndToEnd() {
        assertTrue(samples.isDirectory, "samples dir missing: ${samples.absolutePath}")
        val outFile = File.createTempFile("gt-diff", ".json")
        outFile.deleteOnExit()
        val (code, out, err) = run(
            "--log", File(samples, "T2-session.jsonl").path,
            "--gt", File(samples, "T2-gt.json").path,
            "--params", File(samples, "params.json").path,
            "--out", outFile.path,
        )
        assertEquals("", err)
        assertEquals(GtDiffMain.EXIT_OK, code, out)
        assertTrue(out.contains("confusion matrix"), out)
        assertTrue(out.contains("overall: PASS"), out)
        val report = FocusJson.pretty.decodeFromString(GtReport.serializer(), outFile.readText())
        assertEquals("T2", report.scenarioId)
        assertEquals(true, report.pass.overall)
        assertEquals(true, report.reproducibility.replayTwiceIdentical)
        assertEquals(1.0, report.reproducibility.loggedFinalMatchRatio)
    }

    @Test
    fun loggedEngineScoresTheRecordedStates() {
        val (code, out, _) = run("--log", File(samples, "T2-session.jsonl").path, "--gt", File(samples, "T2-gt.json").path, "--engine", "logged", "--quiet")
        assertEquals(GtDiffMain.EXIT_OK, code, out)
        val report = FocusJson.pretty.decodeFromString(GtReport.serializer(), out)
        assertEquals("logged", report.engineId)
        assertEquals(null, report.reproducibility.replayTwiceIdentical)
    }

    @Test
    fun usageAndInputErrors() {
        assertEquals(GtDiffMain.EXIT_USAGE, run("--log", "x").first)
        assertEquals(GtDiffMain.EXIT_USAGE, run("--bogus").first)
        assertEquals(GtDiffMain.EXIT_USAGE, run("--log", "a", "--gt", "b", "--engine", "magic").first)
        assertEquals(GtDiffMain.EXIT_INPUT, run("--log", "/nonexistent.jsonl", "--gt", File(samples, "T2-gt.json").path).first)
        assertEquals(GtDiffMain.EXIT_OK, run("--help").first)
        val (code, out, _) = run("--print-default-params")
        assertEquals(GtDiffMain.EXIT_OK, code)
        assertTrue(out.contains("\"parameter_set_id\": \"ps-v0.2.1-default\""))
    }

    @Test
    fun failingSessionExitsWithThree() {
        // a log whose face never disappears cannot satisfy the T2 ABSENT rows
        val log = JsonlCodec.decode(File(samples, "T2-session.jsonl").readText())
        val allPresent = log.copy(records = log.records.map { it.copy(faceDetectRatio = 1.0) })
        val tmp = File.createTempFile("t2-present", ".jsonl")
        tmp.deleteOnExit()
        tmp.writeText(JsonlCodec.encode(allPresent))
        val (code, out, _) = run("--log", tmp.path, "--gt", File(samples, "T2-gt.json").path, "--quiet")
        assertEquals(GtDiffMain.EXIT_FAIL, code, out)
    }
}
