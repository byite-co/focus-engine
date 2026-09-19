package co.byite.focus.core.gt

import co.byite.focus.core.model.FocusSchema
import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.SecondRecord
import co.byite.focus.core.model.State
import co.byite.focus.core.replay.ReplayResult
import co.byite.focus.core.util.Stats

/** One record aligned to the GT timeline. */
data class AlignedSecond(
    val record: SecondRecord,
    val point: ExpectedPoint,
    val raw: State,
    val final: State,
) {
    val tRelMs: Long get() = point.tRelMs
    val scored: Boolean get() = point.scored
}

/**
 * Second-by-second comparison of `final_state` against the generated `expected_state`
 * (v0-plan 7장 대조 방법). Reaction windows and void spans are excluded from every metric except
 * the scenario-specific "0초" checks in [PassCriteria], which look at whole behaviour intervals.
 */
class GtDiff(
    private val params: ParameterSet,
    private val rules: GtRules = GtRules.DEFAULT,
) {
    private val generator = ExpectedStateGenerator(params, rules)
    private val passCriteria = PassCriteria(params, rules)

    /** Resolve a GT that is already aligned to the session grid (see [GtParser.alignToSession]). */
    fun resolve(gt: GtFile): ResolvedGt = generator.resolve(gt)

    /** Align finalised records (raw and final set) to the GT. Records are sorted by `t_mono_ms`. */
    fun align(resolved: ResolvedGt, records: List<SecondRecord>): List<AlignedSecond> =
        records.sortedBy { it.tMonoMs }.map { r ->
            val raw = requireNotNull(r.rawState) { "record ${r.tMonoMs} has no raw_state" }
            val final = requireNotNull(r.finalState) { "record ${r.tMonoMs} has no final_state" }
            AlignedSecond(r, generator.expectedAt(resolved, r.tMonoMs - resolved.file.startCueTMonoMs), raw, final)
        }

    /**
     * @param primary the run under test.
     * @param baseline optional [co.byite.focus.core.engine.NaiveBaselineEngine] run on the same log.
     * @param loggedRecords the log's own records, to compare recorded `final_state` with the replayed one.
     * @param replayTwiceIdentical outcome of running the primary replay twice; null when not checked.
     */
    fun diff(
        gt: GtFile,
        primary: ReplayResult,
        baseline: ReplayResult? = null,
        loggedRecords: List<SecondRecord>? = null,
        replayTwiceIdentical: Boolean? = null,
        extraWarnings: List<String> = emptyList(),
    ): GtReport {
        val alignedGt = GtParser.alignToSession(gt, primary.header.tStartMonoMs)
        val resolved = resolve(alignedGt.file)
        val aligned = align(resolved, primary.records)
        val scored = aligned.filter { it.scored }
        val states = State.entries

        val warnings = ArrayList<String>()
        if (gt.sessionId != primary.header.sessionId) {
            warnings.add("session_id mismatch: GT '${gt.sessionId}' vs log '${primary.header.sessionId}'")
        }
        warnings.addAll(alignedGt.warnings)
        warnings.addAll(resolved.warnings)
        warnings.addAll(primary.warnings)
        warnings.addAll(extraWarnings)

        // ---- confusion and per-state metrics
        val confusion = states.associateWith { e -> states.associateWith { f -> scored.count { it.point.expected == e && it.final == f } } }
        val n = scored.size
        // v0.2.1 판정 4·9: INVALID and PAUSED leave the ratio denominators; their shares are reported apart.
        val expectedDen = scored.count { it.point.expected !in State.RATIO_EXCLUDED }
        val measuredDen = scored.count { it.final !in State.RATIO_EXCLUDED }
        val perState = states.associateWith { s ->
            val tp = confusion.getValue(s).getValue(s)
            val expectedS = confusion.getValue(s).values.sum()
            val measuredS = states.sumOf { e -> confusion.getValue(e).getValue(s) }
            val fp = measuredS - tp
            val fn = expectedS - tp
            val excluded = s in State.RATIO_EXCLUDED
            val expectedRatio = if (excluded) null else Stats.ratio(expectedS, expectedDen)
            val measuredRatio = if (excluded) null else Stats.ratio(measuredS, measuredDen)
            StateMetrics(
                expectedS = expectedS,
                measuredS = measuredS,
                tp = tp,
                fp = fp,
                fn = fn,
                precision = Stats.ratio(tp, tp + fp),
                recall = Stats.ratio(tp, tp + fn),
                expectedRatio = expectedRatio,
                measuredRatio = measuredRatio,
                ratioErrorPp = if (expectedRatio == null || measuredRatio == null) null else (measuredRatio - expectedRatio) * 100.0,
            )
        }

        val excludedShares = State.RATIO_EXCLUDED.associateWith { s ->
            val e = perState.getValue(s).expectedS
            val m = perState.getValue(s).measuredS
            val es = Stats.ratio(e, n)
            val ms = Stats.ratio(m, n)
            ShareStat(e, m, es, ms, if (es == null || ms == null) null else (ms - es) * 100.0)
        }

        // ---- detection latency (raw_state)
        val latency = detectionLatency(resolved, aligned)

        // ---- flapping within constant-expected runs
        val flapping = flapping(scored)

        // ---- false INVALID / false AWAY
        val falseInvalid = falseRate(scored, State.INVALID)
        val falseAway = falseRate(scored, State.AWAY)

        val invalidByExpected = states.associateWith { e ->
            val den = scored.count { it.point.expected == e }
            val num = scored.count { it.point.expected == e && it.final == State.INVALID }
            RatioStat(num, den, Stats.ratio(num, den))
        }

        val byBehavior = LinkedHashMap<String, BehaviorSummary>()
        for (ri in resolved.intervals) {
            val name = ri.interval.behavior
            if (name in byBehavior) continue
            val secs = scored.filter { it.point.behavior == name }
            byBehavior[name] = BehaviorSummary(secs.size, states.associateWith { f -> secs.count { it.final == f } }.filterValues { it > 0 })
        }

        // ---- seconds summary
        val exclusions = aligned.groupingBy { it.point.exclusion }.eachCount()
        val timeline = generator.timeline(resolved)
        val buckets = HashSet<Long>()
        for (a in aligned) if (a.tRelMs >= 0) buckets.add(a.tRelMs / FocusSchema.RECORD_PERIOD_MS)
        val missing = timeline.count { it.scored && (it.tRelMs / FocusSchema.RECORD_PERIOD_MS) !in buckets }
        val seconds = SecondsSummary(
            records = aligned.size,
            scored = n,
            excludedReactionWindow = exclusions[Exclusion.REACTION_WINDOW] ?: 0,
            excludedVoid = exclusions[Exclusion.VOID] ?: 0,
            excludedUnscoredBehavior = exclusions[Exclusion.UNSCORED_BEHAVIOR] ?: 0,
            outsideGt = (exclusions[Exclusion.BEFORE_START] ?: 0) + (exclusions[Exclusion.NO_INTERVAL] ?: 0),
            missingRecords = missing,
            gtSpanMs = resolved.endMs,
            intervalRecords = primary.intervals.size,
        )

        // ---- reproducibility
        val reproducibility = reproducibility(primary, loggedRecords, replayTwiceIdentical)

        // ---- baseline comparison
        val baselineComparison = baseline?.let {
            BaselineComparison(
                primary = baselineMetrics(primary.engineId, scored),
                baseline = baselineMetrics(it.engineId, align(resolved, it.records).filter { a -> a.scored }),
            )
        }

        val pass = passCriteria.evaluate(
            PassContext(
                scenarioId = gt.scenarioId,
                resolved = resolved,
                aligned = aligned,
                perState = perState,
                latency = latency,
                flapping = flapping,
                falseInvalid = falseInvalid,
                falseAway = falseAway,
                reproducibility = reproducibility,
                sessionEndRelMs = primary.sessionEnd?.let { it.tMonoMs - gt.startCueTMonoMs },
                startCueTMonoMs = gt.startCueTMonoMs,
            ),
        )

        return GtReport(
            sessionId = primary.header.sessionId,
            scenarioId = gt.scenarioId,
            gtType = gt.gtType,
            participantId = primary.header.participantId,
            deviceModel = primary.header.deviceModel,
            osVersion = primary.header.osVersion,
            engineId = primary.engineId,
            parameterSetId = primary.parameterSetId,
            specVersion = primary.header.specVersion,
            featureSchemaVersion = primary.header.featureSchemaVersion,
            gtRules = rules,
            seconds = seconds,
            confusion = confusion,
            perState = perState,
            excludedShares = excludedShares,
            detectionLatency = latency,
            flapping = flapping,
            falseInvalid = falseInvalid,
            falseAway = falseAway,
            invalidRatioByExpected = invalidByExpected,
            byBehavior = byBehavior,
            reproducibility = reproducibility,
            baselineComparison = baselineComparison,
            pass = pass,
            warnings = warnings,
        )
    }

    /**
     * For every interval whose terminal expected state differs from the previous scored interval's,
     * the time from the cue (interval start) to the first record whose `raw_state` equals the
     * terminal state, searched inside the interval.
     */
    private fun detectionLatency(resolved: ResolvedGt, aligned: List<AlignedSecond>): Map<State, LatencyStats> {
        val samples = LinkedHashMap<State, MutableList<LatencySample>>()
        var prevTerminal: State? = null
        for (ri in resolved.intervals) {
            val target = ri.terminalState
            if (target == null) continue
            if (target != prevTerminal) {
                val iv = ri.interval
                val first = aligned.firstOrNull { it.tRelMs >= iv.tStartMs && it.tRelMs < iv.tEndMs && it.raw == target }
                samples.getOrPut(target) { ArrayList() }
                    .add(LatencySample(ri.index, iv.behavior, iv.tStartMs, first?.let { it.tRelMs - iv.tStartMs }))
            }
            prevTerminal = target
        }
        return samples.mapValues { (_, list) ->
            val detected = list.mapNotNull { it.latencyMs }
            LatencyStats(
                n = list.size,
                missed = list.size - detected.size,
                medianMs = Stats.median(detected),
                p95Ms = Stats.percentileNearestRank(detected, 0.95),
                samples = list,
            )
        }
    }

    /** Count `final_state` changes between consecutive scored seconds inside runs of constant expected state. */
    private fun flapping(scored: List<AlignedSecond>): FlappingStats {
        val runs = ArrayList<FlapRun>()
        var runExpected: State? = null
        var runStart = 0L
        var runEnd = 0L
        var runCount = 0
        var runChanges = 0
        var prevFinal: State? = null
        fun closeRun() {
            val e = runExpected ?: return
            runs.add(FlapRun(e, runStart, runEnd + FocusSchema.RECORD_PERIOD_MS, runCount, runChanges))
        }
        for (a in scored) {
            val e = a.point.expected ?: continue
            if (e != runExpected) {
                closeRun()
                runExpected = e
                runStart = a.tRelMs
                runCount = 0
                runChanges = 0
                prevFinal = null
            }
            if (prevFinal != null && prevFinal != a.final) runChanges++
            prevFinal = a.final
            runEnd = a.tRelMs
            runCount++
        }
        closeRun()
        val changes = runs.sumOf { it.changes }
        val scoredS = runs.sumOf { it.scoredS }
        val per10Min = if (scoredS == 0) null else changes / (scoredS / 600.0)
        return FlappingStats(changes, scoredS, per10Min, runs)
    }

    private fun falseRate(scored: List<AlignedSecond>, state: State): RatioStat {
        val den = scored.count { it.point.expected != state }
        val num = scored.count { it.point.expected != state && it.final == state }
        return RatioStat(num, den, Stats.ratio(num, den))
    }

    private fun baselineMetrics(engineId: String, scored: List<AlignedSecond>): BaselineMetrics = BaselineMetrics(
        engineId = engineId,
        falseAbsentS = scored.count { it.point.expected != State.ABSENT && it.final == State.ABSENT },
        falseInvalidS = scored.count { it.point.expected != State.INVALID && it.final == State.INVALID },
        missedAbsentS = scored.count { it.point.expected == State.ABSENT && it.final != State.ABSENT },
    )

    private fun reproducibility(primary: ReplayResult, logged: List<SecondRecord>?, twiceIdentical: Boolean?): Reproducibility {
        if (logged == null) return Reproducibility(twiceIdentical, null, null, null)
        val loggedFinal = HashMap<Long, State>()
        for (r in logged) r.finalState?.let { loggedFinal[r.tMonoMs] = it }
        var compared = 0
        var mismatch = 0
        for (r in primary.records) {
            val lf = loggedFinal[r.tMonoMs] ?: continue
            compared++
            if (lf != r.finalState) mismatch++
        }
        // output events: kind + t_mono_ms multiset, symmetric difference (v0.2.1 판정 5)
        val loggedEvents = logged.flatMap { r -> r.outputEvents.map { it.key } }.groupingBy { it }.eachCount()
        val replayedEvents = primary.records.flatMap { r -> r.outputEvents.map { it.key } }.groupingBy { it }.eachCount()
        var diff = 0
        for (k in loggedEvents.keys + replayedEvents.keys) {
            val a = loggedEvents[k] ?: 0
            val b = replayedEvents[k] ?: 0
            diff += if (a > b) a - b else b - a
        }
        return Reproducibility(
            twiceIdentical, compared, mismatch, Stats.ratio(compared - mismatch, compared),
            loggedOutputEvents = loggedEvents.values.sum(),
            replayedOutputEvents = replayedEvents.values.sum(),
            outputEventMismatches = diff,
        )
    }
}
