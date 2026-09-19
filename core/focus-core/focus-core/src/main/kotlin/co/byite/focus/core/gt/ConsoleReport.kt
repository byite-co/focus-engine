package co.byite.focus.core.gt

import co.byite.focus.core.model.State
import co.byite.focus.core.util.Stats

/** Plain-text rendering of a [GtReport] for the console. */
object ConsoleReport {
    fun render(r: GtReport): String = buildString {
        appendLine("gt-diff report")
        appendLine("  session     : ${r.sessionId}")
        appendLine("  scenario    : ${r.scenarioId} (${r.gtType})")
        appendLine("  participant : ${r.participantId}")
        appendLine("  device      : ${r.deviceModel} / ${r.osVersion}")
        appendLine("  engine      : ${r.engineId}   params: ${r.parameterSetId}   spec: ${r.specVersion}")
        val s = r.seconds
        appendLine(
            "  seconds     : records=${s.records} scored=${s.scored} reaction=${s.excludedReactionWindow} void=${s.excludedVoid} " +
                "unscored=${s.excludedUnscoredBehavior} outside=${s.outsideGt} missing=${s.missingRecords} intervals=${s.intervalRecords} gt_span=${s.gtSpanMs / 1000}s",
        )
        appendLine()

        val states = State.entries
        appendLine("confusion matrix (rows = expected, cols = final, seconds)")
        appendLine(
            table(
                listOf("exp\\fin") + states.map { it.name },
                states.map { e -> listOf(e.name) + states.map { f -> r.confusion[e]?.get(f)?.toString() ?: "0" } },
            ),
        )

        appendLine("per state")
        appendLine(
            table(
                listOf("state", "exp_s", "meas_s", "precision", "recall", "exp_ratio", "meas_ratio", "err_pp"),
                states.map { st ->
                    val m = r.perState.getValue(st)
                    listOf(
                        st.name, m.expectedS.toString(), m.measuredS.toString(), Stats.pct(m.precision), Stats.pct(m.recall),
                        Stats.pct(m.expectedRatio), Stats.pct(m.measuredRatio), m.ratioErrorPp?.let { Stats.fmt(it, 2) } ?: "-",
                    )
                },
            ),
        )

        appendLine("detection latency (cue -> first raw_state, ms)")
        if (r.detectionLatency.isEmpty()) {
            appendLine("  (no transitions)")
        } else {
            appendLine(
                table(
                    listOf("state", "n", "missed", "median", "p95"),
                    r.detectionLatency.entries.map { (st, l) ->
                        listOf(st.name, l.n.toString(), l.missed.toString(), l.medianMs?.let { Stats.fmt(it, 0) } ?: "-", l.p95Ms?.toString() ?: "-")
                    },
                ),
            )
        }

        appendLine("flapping      : changes=${r.flapping.changes} scored_s=${r.flapping.scoredS} per_10min=${Stats.fmt(r.flapping.per10Min, 2)}")
        appendLine("false INVALID : ${r.falseInvalid.numerator}/${r.falseInvalid.denominator} (${Stats.pct(r.falseInvalid.ratio)})")
        appendLine("false AWAY    : ${r.falseAway.numerator}/${r.falseAway.denominator} (${Stats.pct(r.falseAway.ratio)})")
        val rep = r.reproducibility
        appendLine(
            "reproducibility: replay_twice=${rep.replayTwiceIdentical?.let { if (it) "identical" else "DIFFERS" } ?: "n/a"}" +
                (rep.loggedFinalComparedS?.let { "  logged_final match=${Stats.pct(rep.loggedFinalMatchRatio)} (${rep.loggedFinalMismatchS} mismatch / $it)" } ?: ""),
        )
        appendLine()

        r.baselineComparison?.let { b ->
            appendLine("baseline comparison (scored seconds)")
            appendLine(
                table(
                    listOf("role", "engine", "false_absent", "false_invalid", "missed_absent"),
                    listOf("primary" to b.primary, "baseline" to b.baseline).map { (role, m) ->
                        listOf(role, m.engineId, m.falseAbsentS.toString(), m.falseInvalidS.toString(), m.missedAbsentS.toString())
                    },
                    rightAlignFrom = 2,
                ),
            )
        }

        appendLine("pass line (v0 합격선)")
        appendLine(
            table(
                listOf("item", "result", "measured", "threshold", "note"),
                r.pass.items.map {
                    listOf(
                        it.id,
                        when {
                            !it.applicable -> "n/a"
                            it.passed == null -> "?"
                            it.passed -> "PASS"
                            else -> "FAIL"
                        },
                        it.measured ?: "-",
                        it.threshold,
                        it.note ?: "",
                    )
                },
                rightAlignFrom = Int.MAX_VALUE,
            ),
        )
        appendLine("overall: " + when (r.pass.overall) { true -> "PASS"; false -> "FAIL"; null -> "INCONCLUSIVE" })

        if (r.warnings.isNotEmpty()) {
            appendLine()
            appendLine("warnings")
            for (w in r.warnings) appendLine("  - $w")
        }
    }

    /** Fixed-width table; columns from [rightAlignFrom] on are right-aligned. */
    fun table(header: List<String>, rows: List<List<String>>, rightAlignFrom: Int = 1): String {
        val all = listOf(header) + rows
        val widths = header.indices.map { c -> all.maxOf { it.getOrElse(c) { "" }.length } }
        fun line(cells: List<String>) = cells.indices.joinToString(" | ") { c ->
            val cell = cells.getOrElse(c) { "" }
            if (c >= rightAlignFrom) cell.padStart(widths[c]) else cell.padEnd(widths[c])
        }
        return buildString {
            appendLine("  " + line(header))
            appendLine("  " + widths.joinToString("-+-") { "-".repeat(it) })
            for (row in rows) appendLine("  " + line(row))
        }
    }
}
