package dev.nicotv.detection

import dev.nicotv.core.DetectionOrigin
import dev.nicotv.core.StationObservation

/** Main-thread policy. Only station IDs/identity/times survive a scan; no screen strings do. */
internal class DetectionPolicy(private val now: () -> Long, private val publish: (StationObservation) -> Unit) {
    data class Pending(val generation: Long, val stationId: String, val foreground: ForegroundIdentity,
                       val startedAt: Long, val dueAt: Long)
    /** Last positively confirmed station on this app, used only to resume after a list/guide screen. */
    data class Restore(val stationId: String, val packageName: String, val at: Long)
    var pending: Pending? = null
        private set
    var observation = StationObservation(null, DetectionOrigin.ACCESSIBILITY, 0, false, "未検出")
        private set
    private var generation = 0L
    private var authorized = false
    private var confirmedForeground: ForegroundIdentity? = null
    private var restore: Restore? = null
    private var resumeSince: Long? = null

    fun authorize(enabled: Boolean) {
        authorized = enabled
        restore = null
        resumeSince = null
        invalidate(if (enabled) "局の確認を待っています" else "自動検出は停止中です")
    }

    /** A tuning action makes the remembered station unsafe: the next screen may be another station. */
    fun forget() { restore = null; resumeSince = null }

    fun invalidate(reason: String) {
        generation++
        pending = null
        confirmedForeground = null
        set(StationObservation(null, DetectionOrigin.ACCESSIBILITY, now(), false, reason))
    }

    fun evidence(value: StationEvidence): Pending? {
        if (!authorized) return null
        val station = value.stationId
        val foreground = value.foreground
        if (station == null || foreground == null) {
            val age = now() - observation.observedAtMs
            val heldScreen = foreground != null && foreground == confirmedForeground &&
                pending == null && observation.stationId != null && age in 0..DetectionLimits.GUARD_TTL_MS
            // A calibrated label that is not a known station proves another station is on screen.
            if (value.unknownStation) forget()
            val resumable = restore
            if (value.retainStation && heldScreen) remember(observation.stationId, foreground)
            // AQUOS shows no channel-call OSD when the program guide is dismissed, so a station
            // confirmed before the guide can never be re-read afterwards. It is resumed only after an
            // uninterrupted RESUME_GRACE_MS streak of calibrated fullscreen-live scans without any OSD
            // on the same app: tuning from the guide raises its own channel-call OSD well inside that
            // window, and any label read at any moment overrides the resumed station immediately.
            val eligible = value.retainStation && foreground != null && pending == null &&
                observation.stationId == null
            if (!eligible) resumeSince = null else if (resumeSince == null) resumeSince = now()
            val streak = resumeSince
            val canResume = resumable != null && foreground != null && streak != null &&
                foreground.packageName == resumable.packageName &&
                now() - streak >= DetectionLimits.RESUME_GRACE_MS &&
                now() - resumable.at in 0..DetectionLimits.RESTORE_TTL_MS
            when {
                value.retainStation && heldScreen ->
                    set(observation.copy(observedAtMs = now(), watchingTv = true, detail = "AQUOS全画面ライブの継続を再確認"))
                // A tree that mutates mid-read must not destroy a confirmed station. The evidence time is
                // deliberately NOT refreshed, so an unreadable screen still expires inside the guard window.
                value.transient && heldScreen ->
                    set(observation.copy(watchingTv = true, detail = "画面を一時的に読み取れません・局を保持中"))
                resumable != null && canResume -> {
                    confirmedForeground = foreground
                    remember(resumable.stationId, foreground)
                    set(StationObservation(resumable.stationId, DetectionOrigin.ACCESSIBILITY, now(), true,
                        "OSDが出ないため直前の局を暫定復元"))
                }
                else -> invalidate(value.reason)
            }
            return null
        }
        val time = now()
        if (time < observation.observedAtMs) { invalidate("時刻の整合性を確認できません"); return null }
        if (observation.stationId == station && confirmedForeground == foreground) {
            remember(station, foreground)
            set(StationObservation(station, DetectionOrigin.ACCESSIBILITY, time, true, "校正済み局ラベルで確認"))
            return null
        }
        pending?.let { if (it.stationId == station && it.foreground == foreground) return it }
        invalidate("選局変更を確認中です") // Clear old station BEFORE debounce, never keep it while switching.
        return Pending(generation, station, foreground, time, time + DetectionLimits.DEBOUNCE_MS)
            .also { pending = it }
    }

    /** Only a FRESH root read may confirm. Old callbacks cannot clear or publish a newer candidate. */
    fun confirm(token: Long, fresh: StationEvidence): Pending? {
        val candidate = pending ?: return null
        if (!authorized || token != generation || token != candidate.generation) return pending
        val time = now()
        if (time < candidate.dueAt) return candidate
        if (time - candidate.startedAt >= DetectionLimits.EVIDENCE_TTL_MS) {
            invalidate("選局確認の期限が切れました"); return null
        }
        if (candidate.stationId != fresh.stationId || candidate.foreground != fresh.foreground) return evidence(fresh)
        pending = null
        confirmedForeground = candidate.foreground
        remember(candidate.stationId, candidate.foreground)
        set(StationObservation(candidate.stationId, DetectionOrigin.ACCESSIBILITY, time, true, "校正済み局ラベルで確認"))
        return null
    }

    /** Root PACKAGE/WINDOW inspection only. Never changes the last station-evidence time. */
    fun heartbeat(foreground: ForegroundIdentity?, usable: Boolean) {
        if (!authorized) return
        if (!usable || foreground == null) { invalidate("テレビ画面が前面でないか画面が無効です"); return }
        val expected = pending?.foreground ?: confirmedForeground
        if (expected != null && expected != foreground) { invalidate("テレビ画面から離れました"); return }
        expire()
    }

    /** Dedicated deadline, independent of foreground polling and without reading any root. */
    fun expire() {
        if (!authorized) return
        val time = now()
        if (observation.stationId != null &&
            (time < observation.observedAtMs || time - observation.observedAtMs >= DetectionLimits.EVIDENCE_TTL_MS)) {
            invalidate("局情報の有効期限が切れました")
        }
    }

    private fun remember(stationId: String?, foreground: ForegroundIdentity?) {
        if (stationId == null || foreground == null) return
        restore = Restore(stationId, foreground.packageName, now())
    }

    private fun set(value: StationObservation) { observation = value; publish(value) }
}
