package dev.nicotv.app

import android.Manifest
import android.app.*
import android.content.pm.PackageManager
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.nicotv.comment.NicoLiveCommentSource
import dev.nicotv.comment.NxJikkyoCommentSource
import dev.nicotv.core.*
import dev.nicotv.detection.BraviaStationDetector
import dev.nicotv.detection.StationDetectionBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: SettingsRepository
    private lateinit var controller: SessionController
    private lateinit var window: OverlayWindow
    private var detector: Job? = null
    private var braviaGate: BraviaVisibilityGate? = null
    private var watchdog: Job? = null
    private var detectorEpoch = 0L
    private var promoted = false
    private var receiverRegistered = false
    private var stopping = false
    private val stopAction = Runnable { stopAll() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopAll("画面OFFのため停止")
        }
    }
    override fun onCreate() {
        super.onCreate()
        repository = SettingsRepository(this)
        window = OverlayWindow(this) { stopAll("重ね合わせ表示が許可されていないか、端末が対応していません") }
        controller = SessionController(scope, SystemClock::elapsedRealtime,
            { if (it == Backend.NX) NxJikkyoCommentSource() else NicoLiveCommentSource() }, window) {
            RuntimeSession.publish(it)
            if (promoted && it.active) notifyState(it)
        }
        // SCREEN_OFF is a protected system broadcast. Before API33 no exported flag is available.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiverRegistered = true
        RuntimeSession.registerStopHandler(stopAction)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopAll(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> stopAll()
            ACTION_START -> {
                if (!RuntimeSession.consume(intent.getStringExtra(EXTRA_TICKET))) {
                    if (!controller.state.active) stopAll("画面から開始してください")
                    return START_NOT_STICKY
                }
                begin()
            }
            ACTION_RECONFIGURE -> {
                if (controller.state.active && repository.preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false)) reconfigure()
                else stopAll()
            }
            else -> if (!controller.state.active) stopAll()
        }
        return START_NOT_STICKY
    }
    private fun begin() {
        stopping = false
        val config = repository.read()
        PlatformPermissions.block(this, config)?.let { stopAll(it); return }
        if (SettingsValidator.validate(config).isNotEmpty()) { stopAll("設定に不正な値があります。設定を保存し直してください"); return }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "実況オーバーレイ", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "ユーザーが開始した実況表示。いつでも停止できます。"; setShowBadge(false)
                })
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(SessionUiState(active = true, message = "開始中")),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            promoted = true
            repository.setSessionActive(true)
            controller.start(config)
            launchDetector(config)
            watchdog?.cancel()
            watchdog = scope.launch {
                while (isActive) {
                    delay(1000)
                    if (!repository.preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false)) { stopAll(); break }
                    val blocked = PlatformPermissions.block(this@OverlayService, repository.read())
                    if (blocked != null) { stopAll(blocked); break }
                    controller.tick()
                }
            }
        } catch (_: RuntimeException) { stopAll("サービスを開始できません。端末の許可と設定を確認してください") }
    }
    private fun reconfigure() {
        val config = repository.read()
        PlatformPermissions.block(this, config)?.let { stopAll(it); return }
        controller.configure(config)
        launchDetector(config)
    }
    private fun launchDetector(config: AppSettings) {
        braviaGate?.close(); braviaGate = null
        val epoch = ++detectorEpoch
        detector?.cancel(); detector = null
        StationDetectionBus.publish(StationObservation(null, DetectionOrigin.ACCESSIBILITY, SystemClock.elapsedRealtime(), false, "設定変更"))
        when (config.mode) {
            PreferenceContract.MODE_ACCESSIBILITY -> {
                if (!config.calibrated) return
                detector = scope.launch {
                    StationDetectionBus.observation.collect {
                        if (epoch == detectorEpoch && repository.preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false)) controller.observation(it)
                    }
                }
            }
            PreferenceContract.MODE_BRAVIA -> {
                braviaGate = BraviaVisibilityGate(scope, SystemClock::elapsedRealtime, StationDetectionBus.observation,
                    platformReady = {
                        epoch == detectorEpoch && repository.preferences.getBoolean(PreferenceContract.SESSION_ACTIVE, false) &&
                            repository.read() == config && PlatformPermissions.block(this, config) == null
                    },
                    rest = { flow {
                        // Decrypt and create the cold REST flow only while visibility + local-host guards are valid.
                        val psk = EncryptedPskStore(this@OverlayService).read()
                        val map = SettingsValidator.stationMap(config.braviaMapJson, true)
                        if (psk.isNullOrEmpty() || map.isEmpty()) throw IllegalStateException("BRAVIA設定が未確認")
                        emitAll(BraviaStationDetector(channelMap = map).observations(config.braviaHost, psk))
                    } },
                    deliver = { if (epoch == detectorEpoch) controller.observation(it) }
                ).also { it.start() }
            }
        }
    }
    private fun unavailableBravia() {
        controller.observation(StationObservation(null, DetectionOrigin.BRAVIA, SystemClock.elapsedRealtime(), false, "未設定・接続失敗"))
    }
    private fun notification(state: SessionUiState): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val station = StationCatalog.find(state.stationId)?.name ?: "局未検出"
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("NicoTVOverlay · $station").setContentText("${state.backend.label} · ${state.message}")
            .setPriority(NotificationCompat.PRIORITY_LOW).setSilent(true).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open).addAction(R.drawable.ic_notification, "停止", stop).build()
    }
    private fun notifyState(state: SessionUiState) {
        // Posting is optional when notification permission is denied; startForeground still supplied one.
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        try { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state)) } catch (_: SecurityException) { }
    }
    private fun stopAll(message: String = "停止中") {
        if (stopping) return
        stopping = true
        RuntimeSession.invalidate()
        repository.setSessionActive(false)
        controller.stop(message) // invalidate rendering before any upstream cancellation can suspend
        braviaGate?.close(); braviaGate = null
        ++detectorEpoch
        detector?.cancel(); detector = null
        watchdog?.cancel(); watchdog = null
        scope.coroutineContext.cancelChildren()
        StationDetectionBus.publish(StationObservation(null, DetectionOrigin.ACCESSIBILITY, SystemClock.elapsedRealtime(), false, "停止"))
        promoted = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent: Intent?) { stopAll("アプリを終了したため停止"); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        RuntimeSession.unregisterStopHandler(stopAction)
        stopAll()
        window.clear()
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val ACTION_START = "dev.nicotv.app.START"
        const val ACTION_STOP = "dev.nicotv.app.STOP"
        const val ACTION_RECONFIGURE = "dev.nicotv.app.RECONFIGURE"
        const val EXTRA_TICKET = "start_ticket"
        private const val CHANNEL = "live_overlay"
        private const val NOTIFICATION_ID = 41
    }
}
