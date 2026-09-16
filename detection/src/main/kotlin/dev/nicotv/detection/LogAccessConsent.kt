package dev.nicotv.detection

/**
 * Android 13+ の「すべてのデバイスログへのアクセスを許可しますか？」を自分で承認するための判定。
 *
 * 対象は SystemUI が出すログアクセス確認だけ。ほかの画面・ほかのボタンには触らない。
 */
internal object LogAccessConsent {
    /** ログアクセス確認を出すのは SystemUI だけ。 */
    private const val DIALOG_PACKAGE = "com.android.systemui"
    private const val ALLOW_VIEW_ID = "com.android.systemui:id/log_access_dialog_allow_button"
    private val ALLOW_LABELS = setOf(
        "1回限りのアクセスを許可",
        "1 回限りのアクセスを許可",
        "Allow one time access",
        "Allow one-time access",
    )

    fun dialogPackage(packageName: String?): Boolean = packageName == DIALOG_PACKAGE

    /** 許可ボタンだけを見分ける（IDを優先し、IDが取れない端末向けに既知のラベルも許容）。 */
    fun isAllow(viewId: String?, text: String?): Boolean {
        if (viewId == ALLOW_VIEW_ID) return true
        if (viewId != null) return false
        val label = text?.trim().orEmpty()
        return label.isNotEmpty() && label in ALLOW_LABELS
    }
}
