package dev.nicotv.detection

/**
 * 自分のユーザー補助サービスが実際に接続されているかを、同一プロセス内だけで共有する目印。
 *
 * AccessibilityManager の有効サービス一覧は setServiceInfo の直後などに自分を一時的に外すことがある。
 * その瞬断だけでセッションを止めると、録画再生中にコメントが落ちてしまう。実際の接続状態と
 * 切断直後の猶予を併せて見ることで、瞬断では止めず、本当に無効化された時だけ止める。
 *
 * 外部から書き込む経路は無く、OS がバインドした自分のサービスだけが値を更新する。
 */
object AccessibilityLink {
    const val GRACE_MS = 5_000L

    @Volatile private var disconnectedAtMs = 0L

    @Volatile var connected: Boolean = false
        private set

    fun markConnected() {
        disconnectedAtMs = 0L
        connected = true
    }

    fun markDisconnected(nowMs: Long) {
        disconnectedAtMs = nowMs
        connected = false
    }

    /** 接続中、または切断直後の猶予内なら true。 */
    fun live(nowMs: Long): Boolean =
        connected || (disconnectedAtMs > 0L && nowMs - disconnectedAtMs in 0L until GRACE_MS)

    /** テスト用。 */
    fun reset() {
        connected = false
        disconnectedAtMs = 0L
    }
}
