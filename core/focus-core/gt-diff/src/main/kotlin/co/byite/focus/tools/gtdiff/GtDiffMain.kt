package co.byite.focus.tools.gtdiff

import co.byite.focus.core.engine.NaiveBaselineEngine
import co.byite.focus.core.gt.ConsoleReport
import co.byite.focus.core.gt.GtDiff
import co.byite.focus.core.gt.GtFormatException
import co.byite.focus.core.gt.GtParser
import co.byite.focus.core.gt.GtReport
import co.byite.focus.core.log.FocusJson
import co.byite.focus.core.log.JsonlCodec
import co.byite.focus.core.log.LogFormatException
import co.byite.focus.core.log.SessionLog
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.replay.ReplayResult
import co.byite.focus.core.replay.ReplayRunner
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/**
 * `gt-diff --log session.jsonl --gt gt.json [--params params.json] [--engine naive|logged] [--out report.json] [--quiet]`
 *
 * Replays the log, scores it against the GT and prints a console table plus the report JSON.
 * Exit codes: 0 = ran (PASS or INCONCLUSIVE), 1 = usage error, 2 = input error, 3 = ran and FAIL.
 */
object GtDiffMain {
    const val EXIT_OK = 0
    const val EXIT_USAGE = 1
    const val EXIT_INPUT = 2
    const val EXIT_FAIL = 3

    const val ENGINE_NAIVE = "naive"
    const val ENGINE_LOGGED = "logged"
    const val ENGINE_ID_LOGGED = "logged"

    private const val USAGE = """usage: gt-diff --log session.jsonl --gt gt.json [options]

options:
  --params params.json    ParameterSet JSON (default: spec v0.2.0 values, id ps-v0.2.0-default)
  --engine naive|logged   naive  = replay with NaiveBaselineEngine (default; the only engine so far)
                          logged = score the raw_state/final_state recorded in the log, no replay
  --out report.json       write the report JSON here instead of stdout
  --quiet                 do not print the console table
  --print-default-params  print the default ParameterSet JSON and exit
  --help

exit codes: 0 ran (PASS or INCONCLUSIVE), 1 usage error, 2 input error, 3 ran and FAIL"""

    @JvmStatic
    fun main(args: Array<String>) {
        // System.out follows the locale (stdout.encoding); the report has Korean labels, so force UTF-8.
        val out = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        val err = PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8")
        val code = run(args.toList(), out, err)
        out.flush()
        err.flush()
        if (code != EXIT_OK) exitProcess(code)
    }

    data class Options(
        val log: String? = null,
        val gt: String? = null,
        val params: String? = null,
        val engine: String = ENGINE_NAIVE,
        val out: String? = null,
        val quiet: Boolean = false,
        val printDefaultParams: Boolean = false,
        val help: Boolean = false,
    )

    fun parseArgs(args: List<String>): Options {
        var o = Options()
        var i = 0
        fun value(flag: String): String {
            if (i + 1 >= args.size) throw IllegalArgumentException("$flag needs a value")
            return args[++i]
        }
        while (i < args.size) {
            when (val a = args[i]) {
                "--log" -> o = o.copy(log = value(a))
                "--gt" -> o = o.copy(gt = value(a))
                "--params" -> o = o.copy(params = value(a))
                "--engine" -> o = o.copy(engine = value(a))
                "--out" -> o = o.copy(out = value(a))
                "--quiet" -> o = o.copy(quiet = true)
                "--print-default-params" -> o = o.copy(printDefaultParams = true)
                "--help", "-h" -> o = o.copy(help = true)
                else -> throw IllegalArgumentException("unknown argument '$a'")
            }
            i++
        }
        return o
    }

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val o = try {
            parseArgs(args)
        } catch (e: IllegalArgumentException) {
            err.println("gt-diff: ${e.message}")
            err.println(USAGE)
            return EXIT_USAGE
        }
        if (o.help) {
            out.println(USAGE)
            return EXIT_OK
        }
        if (o.printDefaultParams) {
            out.println(FocusJson.pretty.encodeToString(ParameterSet.serializer(), ParameterSet.DEFAULT))
            return EXIT_OK
        }
        if (o.log == null || o.gt == null) {
            err.println("gt-diff: --log and --gt are required")
            err.println(USAGE)
            return EXIT_USAGE
        }
        if (o.engine != ENGINE_NAIVE && o.engine != ENGINE_LOGGED) {
            err.println("gt-diff: unknown engine '${o.engine}' (naive|logged)")
            return EXIT_USAGE
        }

        val report = try {
            val params = o.params?.let { readParams(it) } ?: ParameterSet.DEFAULT
            val log = JsonlCodec.decode(readFile(o.log))
            val gt = GtParser.parse(readFile(o.gt))
            buildReport(log, gt, params, o.engine)
        } catch (e: InputException) {
            err.println("gt-diff: ${e.message}")
            return EXIT_INPUT
        } catch (e: LogFormatException) {
            err.println("gt-diff: log: ${e.message}")
            return EXIT_INPUT
        } catch (e: GtFormatException) {
            err.println("gt-diff: gt: ${e.message}")
            return EXIT_INPUT
        } catch (e: IllegalArgumentException) {
            err.println("gt-diff: ${e.message}")
            return EXIT_INPUT
        } catch (e: IllegalStateException) {
            err.println("gt-diff: ${e.message}")
            return EXIT_INPUT
        }

        if (!o.quiet) out.print(ConsoleReport.render(report))
        val json = FocusJson.pretty.encodeToString(GtReport.serializer(), report)
        if (o.out != null) {
            File(o.out).writeText(json + "\n")
            if (!o.quiet) out.println("report written to ${o.out}")
        } else {
            if (!o.quiet) out.println()
            out.println(json)
        }
        return if (report.pass.overall == false) EXIT_FAIL else EXIT_OK
    }

    /** Replay (or take the logged states), run the baseline, and diff. */
    fun buildReport(log: SessionLog, gt: co.byite.focus.core.gt.GtFile, params: ParameterSet, engine: String): GtReport {
        val baseline = ReplayRunner(NaiveBaselineEngine(params), params).run(log)
        val diff = GtDiff(params)
        return when (engine) {
            ENGINE_LOGGED -> {
                val missing = log.records.count { it.rawState == null || it.finalState == null }
                if (missing > 0) throw InputException("--engine logged needs raw_state and final_state on every record; $missing record(s) lack them")
                val primary = ReplayResult(
                    header = log.header,
                    engineId = ENGINE_ID_LOGGED,
                    parameterSetId = log.header.parameterSetId,
                    records = log.records.sortedBy { it.tMonoMs },
                    intervals = log.intervals.sortedBy { it.tStartMonoMs },
                    sessionEnd = log.sessionEnd,
                    droppedAfterSessionEnd = 0,
                    warnings = emptyList(),
                )
                diff.diff(gt, primary, baseline = baseline, loggedRecords = null, replayTwiceIdentical = null)
            }
            else -> {
                val runner = ReplayRunner(NaiveBaselineEngine(params), params)
                val first = runner.run(log)
                val second = runner.run(log)
                diff.diff(gt, first, baseline = baseline, loggedRecords = log.records, replayTwiceIdentical = first.sameOutcomeAs(second))
            }
        }
    }

    class InputException(message: String) : RuntimeException(message)

    private fun readFile(path: String): String {
        val f = File(path)
        if (!f.isFile) throw InputException("file not found: $path")
        return f.readText()
    }

    private fun readParams(path: String): ParameterSet = try {
        FocusJson.compact.decodeFromString(ParameterSet.serializer(), readFile(path))
    } catch (e: SerializationException) {
        throw InputException("params: ${e.message}")
    }
}
