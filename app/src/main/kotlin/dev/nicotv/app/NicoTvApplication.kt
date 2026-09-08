package dev.nicotv.app

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class NicoTvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository(this).setSessionActive(false)
        RuntimeSession.reset()
    }
}

/** Volatile authorization is never restored from saved Activity state or preferences. */
object RuntimeSession {
    private var ticket: String? = null
    private var stopHandler: Runnable? = null
    private val mutable = MutableStateFlow(SessionUiState())
    val state = mutable.asStateFlow()
    @Synchronized fun authorize(visible: Boolean): String? {
        if (!visible) return null
        return UUID.randomUUID().toString().also { ticket = it }
    }
    @Synchronized fun consume(value: String?): Boolean {
        if (value == null || value != ticket) return false
        ticket = null
        return true
    }
    @Synchronized fun invalidate() { ticket = null }
    fun publish(value: SessionUiState) { mutable.value = value }
    @Synchronized fun registerStopHandler(handler: Runnable) { stopHandler = handler }
    @Synchronized fun unregisterStopHandler(handler: Runnable) {
        // An old Service's destruction must not unregister a replacement Service.
        if (stopHandler === handler) stopHandler = null
    }
    fun stopRegisteredService() {
        val handler = synchronized(this) { stopHandler }
        handler?.run()
    }
    fun reset() { invalidate(); synchronized(this) { stopHandler = null }; mutable.value = SessionUiState() }
}
