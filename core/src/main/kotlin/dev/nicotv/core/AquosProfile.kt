package dev.nicotv.core

/** Exact resource IDs observed on AQUOS-4KTVJ25-2, Android 14. Never infer a station from a remote number. */
object AquosProfile {
    const val PACKAGE = "jp.co.sharp.av.android.aquostvapp"
    const val STATION = "$PACKAGE:id/channel_call_tv_channel_name"
    const val LIVE = "$PACKAGE:id/main_tv_view"
    // Regional/subchannel labels are exact aliases, not arbitrary substring matching.
    val aliasesJson = """{"NHK総合1・東京":"jk1","NHK総合2・東京":"jk1","NHKEテレ1東京":"jk2","NHKEテレ2東京":"jk2","NHKEテレ3東京":"jk2","日テレ1":"jk4","日テレ2":"jk4","テレビ朝日":"jk5","テレビ朝日1":"jk5","TBS1":"jk6","TBS2":"jk6","テレ東":"jk7","テレビ東京1":"jk7","フジテレビ":"jk8","フジテレビ1":"jk8","TOKYO MX1":"jk9","TOKYO MX2":"jk9","NHK BS":"jk101","BS11イレブン":"jk211"}"""
}
