package sk.brock.nahravac

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Zistenie nainštalovaného TikToku, jeho spustenie na domovskej stránke a ručné zdieľanie videa (ACTION_SEND). */
object TikTokShare {
    /** Hlavná aplikácia, regionálna verzia, potom Go verzia. */
    val PACKAGES = listOf(
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.zhiliaoapp.musically.go",
    )

    /** Prvý nainštalovaný balík TikToku, alebo null. */
    @Suppress("DEPRECATION")
    fun installedPackage(ctx: Context): String? = PACKAGES.firstOrNull { pkg ->
        try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Spustí TikTok vždy od začiatku (domovský feed). Vráti false, ak sa to nepodarilo. */
    fun launchHome(ctx: Context): Boolean {
        val pkg = installedPackage(ctx) ?: return false
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Pošle video do nainštalovaného TikToku; ak ho nevie otvoriť priamo, otvorí bežný výber aplikácií.
     * Ostatné výnimky (nie ActivityNotFound) idú volajúcemu, aby mohol ukázať konkrétny dôvod.
     */
    fun send(ctx: Context, videoUri: Uri, chooserTitle: String) {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, videoUri)
            clipData = ClipData.newRawUri("video", videoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pkg = installedPackage(ctx)
        if (pkg != null) {
            try {
                ctx.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (e: ActivityNotFoundException) {
                // TikTok nemá cieľ pre zdieľanie videa -> bežný výber
            }
        }
        ctx.startActivity(Intent.createChooser(base, chooserTitle))
    }
}
