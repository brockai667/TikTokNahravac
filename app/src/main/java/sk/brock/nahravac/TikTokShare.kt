package sk.brock.nahravac

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Zdieľanie videa do TikToku cez ACTION_SEND. */
object TikTokShare {
    /** Hlavná aplikácia, potom regionálna verzia. */
    val PACKAGES = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")

    /** Pošle video priamo do TikToku; ak nie je nainštalovaný, otvorí bežný výber aplikácií. */
    fun send(ctx: Context, videoUri: Uri, chooserTitle: String) {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            clipData = ClipData.newRawUri("video", videoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (pkg in PACKAGES) {
            try {
                ctx.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // skúsime ďalší balík
            }
        }
        ctx.startActivity(Intent.createChooser(base, chooserTitle))
    }
}
