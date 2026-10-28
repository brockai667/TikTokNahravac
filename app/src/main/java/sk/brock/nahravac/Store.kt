package sk.brock.nahravac

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Malé úložisko v SharedPreferences: hotovo, Uri stiahnutého videa, potvrdenie pred uverejnením, požiadavka na výpis stromu. */
object Store {
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nahravac", Context.MODE_PRIVATE)

    fun isDone(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("done_$key", false)

    fun setDone(ctx: Context, key: String, done: Boolean) {
        prefs(ctx).edit().putBoolean("done_$key", done).apply()
    }

    /** Uri videa, ktoré appka sama uložila do galérie (text), alebo null. */
    fun getVideoUri(ctx: Context, key: String): String? = prefs(ctx).getString("uri_$key", null)

    fun setVideoUri(ctx: Context, key: String, uri: String) {
        prefs(ctx).edit().putString("uri_$key", uri).apply()
    }

    /** Jednoriadkový popis posledného úspešného stiahnutia (názov, veľkosť, priečinok, dĺžka), alebo null. */
    fun getLastDownload(ctx: Context): String? = prefs(ctx).getString("last_download", null)

    /** Uri posledného úspešne stiahnutého videa (text), alebo null. */
    fun getLastDownloadUri(ctx: Context): String? = prefs(ctx).getString("last_download_uri", null)

    /** Čas (ms) posledného úspešného stiahnutia; 0 = žiadne. */
    fun getLastDownloadAt(ctx: Context): Long = prefs(ctx).getLong("last_download_at", 0L)

    fun setLastDownload(ctx: Context, summary: String, uri: String) {
        prefs(ctx).edit()
            .putString("last_download", summary)
            .putString("last_download_uri", uri)
            .putLong("last_download_at", System.currentTimeMillis())
            .apply()
    }

    /** Pred stlačením "Uverejniť" sa pýtať na potvrdenie (predvolene nie; nový kľúč, aby sa starý uložený "áno" ignoroval). */
    fun confirmBeforePost(ctx: Context): Boolean = prefs(ctx).getBoolean("confirm_before_post_v2", false)

    fun setConfirmBeforePost(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean("confirm_before_post_v2", value).apply()
    }

    /** Čas (ms), kedy používateľ požiadal o výpis stromu obrazovky; 0 = žiadna požiadavka. */
    fun dumpRequestedAt(ctx: Context): Long = prefs(ctx).getLong("dump_requested_at", 0L)

    fun requestDump(ctx: Context) {
        prefs(ctx).edit().putLong("dump_requested_at", System.currentTimeMillis()).apply()
    }

    fun clearDump(ctx: Context) {
        prefs(ctx).edit().putLong("dump_requested_at", 0L).apply()
    }
}

/** Adresár s výpismi stromu obrazovky (Android/data/sk.brock.nahravac/files/dumps). */
object Dumps {
    fun dir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(base, "dumps").also { it.mkdirs() }
    }

    fun latest(ctx: Context): File? =
        dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
}
