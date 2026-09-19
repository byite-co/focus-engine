package co.byite.focus.core.gt

import co.byite.focus.core.model.ParameterSet
import co.byite.focus.core.model.State
import co.byite.focus.core.util.Stats
import kotlin.math.abs

/** Everything [PassCriteria] needs from a diff. */
data class PassContext(
    val scenarioId: String,
    val resolved: ResolvedGt,
    /** All aligned records (scored or not). */
    val aligned: List<AlignedSecond>,
    val perState: Map<State, StateMetrics>,
    val latency: Map<State, LatencyStats>,
    val flapping: FlappingStats,
    val falseInvalid: RatioStat,
    val falseAway: RatioStat,
    val reproducibility: Reproducibility,
    /** Session end relative to the start cue, if the session closed. */
    val sessionEndRelMs: Long?,
)

/**
 * v0 pass line (v0-plan 7장 v0 합격선), evaluated per scenario id. `scenario_id` may name a
 * variant (`T4b`); the base id (`T4`) decides which rows apply and the variant narrows them.
 *
 * Rows the record schema cannot support exactly are evaluated through a proxy and say so in
 * their note: T5b "재거치 확정 0회" (no re-dock event in the schema) and T7c "camera_occluded 0초"
 * (no INVALID reason in the schema).
 */
class PassCriteria(
    private val params: ParameterSet,
    private val rules: GtRules = GtRules.DEFAULT,
) {
    fun evaluate(ctx: PassContext): PassSummary {
        val base = scenarioBase(ctx.scenarioId)
        val variant = scenarioVariant(ctx.scenarioId)
        val items = ArrayList<PassItem>()
        val res = ctx.resolved

        fun has(vararg names: String) = res.intervalsWithBehavior(names.toSet()).isNotEmpty()
        fun variantIs(v: Char) = variant == null || variant == v

        // ---- ABSENT (T2, T7c, T8)
        run {
            val applicable = base in setOf("T2", "T7", "T8")
            val m = ctx.perState.getValue(State.ABSENT)
            items.add(thresholdItem("absent_recall", "ABSENT recall (T2, T7c, T8)", applicable, m.recall, rules.absentRecallMin, ">=", "no ABSENT second expected"))
            items.add(thresholdItem("absent_precision", "ABSENT precision (T2, T7c, T8)", applicable, m.precision, rules.absentPrecisionMin, ">=", "no ABSENT second measured"))
        }

        // ---- T2: leave shorter than the confirm time is never ABSENT
        run {
            val applicable = base == "T2"
            val short = res.intervals.filter { it.rule == ExpectedRule.LeaveSeat && it.interval.durationMs < params.absentConfirmMs }
            val count = countFinalIn(ctx.aligned, short, State.ABSENT)
            items.add(
                zeroItem(
                    "t2_short_leave_absent_zero", "2초 자리 비움: ABSENT 0초 (T2)", applicable,
                    if (short.isEmpty()) null else count, "no leave_seat interval shorter than ${params.absentConfirmMs} ms",
                ),
            )
        }

        // ---- T3
        run {
            val applicable = base == "T3"
            val ivs = res.intervalsWithBehavior(setOf(BehaviorCatalog.DEEP_BOW_WRITING))
            val note = "no ${BehaviorCatalog.DEEP_BOW_WRITING} interval"
            items.add(zeroItem("t3_absent_zero", "깊이 숙여 필기: ABSENT 0초 (T3)", applicable, ivs.ifEmptyNull { countFinalIn(ctx.aligned, ivs, State.ABSENT) }, note))
            items.add(zeroItem("t3_prone_zero", "깊이 숙여 필기: PRONE 0초 (T3)", applicable, ivs.ifEmptyNull { countFinalIn(ctx.aligned, ivs, State.PRONE) }, note))
        }

        // ---- T4a
        run {
            val applicable = base == "T4" && variantIs('a') && has(BehaviorCatalog.PRONE_HEAD_VISIBLE)
            val m = ctx.perState.getValue(State.PRONE)
            items.add(thresholdItem("t4a_prone_recall", "PRONE recall (T4a)", applicable, m.recall, rules.proneRecallMin, ">=", "no PRONE second expected"))
        }

        // ---- T4b
        run {
            val applicable = base == "T4" && variantIs('b') && has(BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME)
            val iv = res.intervalsWithBehavior(setOf(BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME)).firstOrNull()?.interval
            if (iv == null || iv.durationMs < params.autoPauseMs) {
                items.add(PassItem("t4b_pause_timing", "자동 일시정지 시각 (T4b)", applicable, null, null, "${params.autoPauseMs} ± ${rules.pauseToleranceMs} ms", "no ${BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME} interval of at least ${params.autoPauseMs} ms"))
                items.add(PassItem("t4b_resume_timing", "복귀 뒤 자동 재개 (T4b)", applicable, null, null, "<= ${rules.resumeWithinMs} ms", "no ${BehaviorCatalog.PRONE_HEAD_OUT_OF_FRAME} interval of at least ${params.autoPauseMs} ms"))
            } else {
                val firstPaused = ctx.aligned.firstOrNull { it.tRelMs >= iv.tStartMs && it.tRelMs < iv.tEndMs && it.final == State.PAUSED }
                val pauseAt = firstPaused?.let { it.tRelMs - iv.tStartMs }
                items.add(
                    PassItem(
                        "t4b_pause_timing", "자동 일시정지 시각 (T4b)", applicable,
                        pauseAt?.let { abs(it - params.autoPauseMs) <= rules.pauseToleranceMs } ?: false,
                        pauseAt?.let { "$it ms" } ?: "never PAUSED",
                        "${params.autoPauseMs} ± ${rules.pauseToleranceMs} ms",
                    ),
                )
                val firstResumed = ctx.aligned.firstOrNull { it.tRelMs >= iv.tEndMs && it.final != State.PAUSED }
                val hasAfter = ctx.aligned.any { it.tRelMs >= iv.tEndMs }
                val resumeAt = firstResumed?.let { it.tRelMs - iv.tEndMs }
                items.add(
                    PassItem(
                        "t4b_resume_timing", "복귀 뒤 자동 재개 (T4b)", applicable,
                        if (!hasAfter) null else (resumeAt != null && resumeAt <= rules.resumeWithinMs),
                        resumeAt?.let { "$it ms" } ?: if (hasAfter) "never resumed" else null,
                        "<= ${rules.resumeWithinMs} ms",
                        if (!hasAfter) "no record after the return cue" else null,
                    ),
                )
            }
        }

        // ---- PHONE (T5a, T9a, T9c)
        run {
            val applicable = base in setOf("T5", "T9")
            val m = ctx.perState.getValue(State.PHONE)
            items.add(thresholdItem("phone_recall", "PHONE recall (T5a, T9a, T9c)", applicable, m.recall, rules.phoneRecallMin, ">=", "no PHONE second expected"))
            items.add(thresholdItem("phone_precision", "PHONE precision (T5a, T9a, T9c)", applicable, m.precision, rules.phonePrecisionMin, ">=", "no PHONE second measured"))
        }
        run {
            val ivs = res.intervalsWithBehavior(setOf(BehaviorCatalog.DESK_BUMP))
            items.add(zeroItem("t5c_desk_bump_phone_zero", "책상 충격: PHONE 0초 (T5c)", base == "T5", ivs.ifEmptyNull { countFinalIn(ctx.aligned, ivs, State.PHONE) }, "no ${BehaviorCatalog.DESK_BUMP} interval"))
        }
        run {
            val ivs = res.intervalsWithBehavior(setOf(BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE))
            val runs = ivs.ifEmptyNull { countRunsIn(ctx.aligned, ivs) { it.final != State.PHONE && it.final != State.INVALID } }
            items.add(
                PassItem(
                    "t5b_redock_confirm_zero", "평평한 곳에 두고 사용: 재거치 확정 0회 (T5b)", base == "T5",
                    runs?.let { it == 0 }, runs?.let { "$it run(s)" }, "== 0",
                    if (runs == null) "no ${BehaviorCatalog.PHONE_USE_ON_FLAT_SURFACE} interval" else "proxy: runs of final_state outside {PHONE, INVALID}; the schema has no re-dock event",
                ),
            )
        }
        run {
            val ivs = res.intervalsWithBehavior(setOf(BehaviorCatalog.NOTIFICATION_SCREEN_ON))
            items.add(zeroItem("t9b_notification_phone_zero", "알림으로 화면만 켜짐: PHONE 0초 (T9b)", base == "T9", ivs.ifEmptyNull { countFinalIn(ctx.aligned, ivs, State.PHONE) }, "no ${BehaviorCatalog.NOTIFICATION_SCREEN_ON} interval"))
        }

        // ---- AWAY (T6b·c recall; T1, T6a·d·e false AWAY)
        run {
            val m = ctx.perState.getValue(State.AWAY)
            items.add(thresholdItem("away_recall", "AWAY recall (T6b, T6c)", base == "T6", m.recall, rules.awayRecallMin, ">=", "no AWAY second expected"))
            items.add(thresholdItem("false_away", "거짓 AWAY 비율 (T1, T6a·d·e)", base in setOf("T1", "T6"), ctx.falseAway.ratio, rules.falseAwayMax, "<=", "no non-AWAY second scored"))
        }

        // ---- 환경 (T7)
        run {
            val ivs = res.intervalsWithBehavior(setOf(BehaviorCatalog.LIGHTS_OFF, BehaviorCatalog.CAMERA_COVERED))
            items.add(zeroItem("t7ab_absent_zero", "불 끄기·카메라 가림: ABSENT 0초 (T7a, T7b)", base == "T7", ivs.ifEmptyNull { countFinalIn(ctx.aligned, ivs, State.ABSENT) }, "no ${BehaviorCatalog.LIGHTS_OFF}/${BehaviorCatalog.CAMERA_COVERED} interval"))
            val plain = res.intervalsWithBehavior(setOf(BehaviorCatalog.LEAVE_SEAT_PLAIN_BACKGROUND))
            val count = plain.ifEmptyNull { countFinalIn(ctx.aligned, plain, State.INVALID, scoredOnly = true) }
            items.add(
                PassItem(
                    "t7c_occluded_zero", "매끈한 배경에서 자리 비움: camera_occluded 0초 (T7c)", base == "T7",
                    count?.let { it == 0 }, count?.let { "$it s" }, "== 0 s",
                    if (count == null) "no ${BehaviorCatalog.LEAVE_SEAT_PLAIN_BACKGROUND} interval" else "proxy: scored INVALID seconds; the schema has no INVALID reason",
                ),
            )
        }

        // ---- 거짓 INVALID (T1)
        items.add(thresholdItem("false_invalid", "거짓 INVALID 비율 (T1)", base == "T1", ctx.falseInvalid.ratio, rules.falseInvalidMax, "<=", "no non-INVALID second scored"))

        // ---- flapping
        items.add(thresholdItem("flapping_per_10min", "flapping: 기대 상태 일정 구간에서 10분당 변화 횟수", base != "T11", ctx.flapping.per10Min, rules.flappingPer10MinMax, "<=", "no scored second", format = { "${Stats.fmt(it, 2)} /10min" }))

        // ---- detection latency (raw_state, P95)
        for ((state, maxMs) in listOf(State.ABSENT to rules.latencyAbsentP95MaxMs, State.PHONE to rules.latencyPhoneP95MaxMs, State.AWAY to rules.latencyAwayP95MaxMs)) {
            val stats = ctx.latency[state]
            val applicable = base != "T11" && stats != null && stats.n > 0
            val passed = when {
                stats == null || stats.n == 0 -> null
                stats.missed > 0 -> false
                else -> stats.p95Ms!! <= maxMs
            }
            items.add(
                PassItem(
                    "latency_${state.name.lowercase()}_p95", "검출 지연 P95, raw_state → $state", applicable,
                    passed,
                    stats?.let { if (it.missed > 0) "${it.missed}/${it.n} missed, p95=${it.p95Ms ?: "-"} ms" else "p95=${it.p95Ms} ms (n=${it.n})" },
                    "<= $maxMs ms",
                ),
            )
        }

        // ---- state ratio error
        run {
            val worst = ctx.perState.entries.filter { it.value.ratioErrorPp != null }.maxByOrNull { abs(it.value.ratioErrorPp!!) }
            val err = worst?.value?.ratioErrorPp
            items.add(
                PassItem(
                    "ratio_error_3pp", "상태 비율 오차: 모든 상태 ± ${Stats.fmt(rules.ratioErrorMaxPp, 1)}%p", base != "T11",
                    err?.let { abs(it) <= rules.ratioErrorMaxPp },
                    err?.let { "max |err| = ${Stats.fmt(abs(it), 2)}%p (${worst.key})" },
                    "|err| <= ${Stats.fmt(rules.ratioErrorMaxPp, 1)}%p",
                    if (err == null) "no scored second" else null,
                ),
            )
        }

        // ---- reproducibility
        items.add(
            PassItem(
                "reproducibility", "재현성: 같은 로그 재생 시 final_state 100% 일치", true,
                ctx.reproducibility.replayTwiceIdentical,
                ctx.reproducibility.replayTwiceIdentical?.let { if (it) "identical" else "differs" },
                "identical",
                if (ctx.reproducibility.replayTwiceIdentical == null) "replay not run (logged states used)" else null,
            ),
        )

        // ---- T10
        run {
            val applicable = base == "T10"
            val kill = res.intervalsWithBehavior(setOf(BehaviorCatalog.APP_KILLED)).firstOrNull()?.interval
            if (kill == null) {
                items.add(PassItem("t10_session_close", "프로세스 종료: 마지막 기록 시각으로 세션 종료, 손실 60초 이내 (T10)", applicable, null, null, "loss <= ${rules.processDeathMaxLossMs} ms", "no ${BehaviorCatalog.APP_KILLED} interval"))
            } else {
                val lastBefore = ctx.aligned.filter { it.tRelMs < kill.tStartMs }.maxOfOrNull { it.tRelMs }
                val after = ctx.aligned.count { it.tRelMs >= kill.tStartMs && it.tRelMs < kill.tEndMs }
                val loss = lastBefore?.let { kill.tStartMs - it }
                val closed = ctx.sessionEndRelMs != null && ctx.sessionEndRelMs <= kill.tEndMs
                val passed = loss != null && loss <= rules.processDeathMaxLossMs && after == 0 && closed
                items.add(
                    PassItem(
                        "t10_session_close", "프로세스 종료: 마지막 기록 시각으로 세션 종료, 손실 60초 이내 (T10)", applicable, passed,
                        "loss=${loss ?: "-"} ms, records inside gap=$after, session_end=${ctx.sessionEndRelMs ?: "none"}",
                        "loss <= ${rules.processDeathMaxLossMs} ms, 0 records after kill, session closed",
                    ),
                )
            }
        }

        val applicable = items.filter { it.applicable }
        val overall = when {
            applicable.isEmpty() -> null
            applicable.any { it.passed == false } -> false
            applicable.any { it.passed == null } -> null
            else -> true
        }
        return PassSummary(overall, items)
    }

    private fun thresholdItem(
        id: String,
        description: String,
        applicable: Boolean,
        value: Double?,
        threshold: Double,
        op: String,
        missingNote: String,
        format: (Double) -> String = { Stats.pct(it, 2) },
    ): PassItem {
        val passed = value?.let { if (op == ">=") it >= threshold else it <= threshold }
        return PassItem(
            id, description, applicable, passed,
            value?.let(format),
            "$op ${format(threshold)}",
            if (value == null) missingNote else null,
        )
    }

    private fun zeroItem(id: String, description: String, applicable: Boolean, count: Int?, missingNote: String): PassItem =
        PassItem(id, description, applicable, count?.let { it == 0 }, count?.let { "$it s" }, "== 0 s", if (count == null) missingNote else null)

    /** Seconds with `final == state` inside the given intervals, excluding void spans (and, optionally, every non-scored second). */
    private fun countFinalIn(aligned: List<AlignedSecond>, intervals: List<ResolvedInterval>, state: State, scoredOnly: Boolean = false): Int {
        val idx = intervals.map { it.index }.toSet()
        return aligned.count { it.point.intervalIndex in idx && it.final == state && it.point.exclusion != Exclusion.VOID && (!scoredOnly || it.scored) }
    }

    /** Maximal runs of consecutive records (inside the intervals, void excluded) satisfying [pred]. */
    private fun countRunsIn(aligned: List<AlignedSecond>, intervals: List<ResolvedInterval>, pred: (AlignedSecond) -> Boolean): Int {
        val idx = intervals.map { it.index }.toSet()
        var runs = 0
        var inRun = false
        for (a in aligned) {
            val inside = a.point.intervalIndex in idx && a.point.exclusion != Exclusion.VOID
            val hit = inside && pred(a)
            if (hit && !inRun) runs++
            inRun = hit
        }
        return runs
    }

    private inline fun <T> List<ResolvedInterval>.ifEmptyNull(block: () -> T): T? = if (isEmpty()) null else block()

    companion object {
        private val SCENARIO = Regex("^([Tt]\\d+)([A-Za-z])?$")

        /** "T4b" → "T4"; anything else is returned upper-cased as-is. */
        fun scenarioBase(scenarioId: String): String =
            SCENARIO.matchEntire(scenarioId.trim())?.groupValues?.get(1)?.uppercase() ?: scenarioId.trim().uppercase()

        /** "T4b" → 'b'; null when no variant letter. */
        fun scenarioVariant(scenarioId: String): Char? =
            SCENARIO.matchEntire(scenarioId.trim())?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.lowercase()?.get(0)
    }
}
