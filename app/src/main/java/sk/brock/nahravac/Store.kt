package sk.brock.nahravac

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Malé úložisko v SharedPreferences: hotovo, ID sťahovania, požiadavka na výpis stromu. */
object Store {
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("nahravac", Context.MODE_PRIVATE)

    fun isDone(ctx: Context, key: String): Boolean = prefs(ctx).getBoolean("done_$key", false)

    fun setDone(ctx: Context, key: String, done: Boolean) {
        prefs(ctx).edit().putBoolean("done_$key", done).apply()
    }

    fun getDownloadId(ctx: Context, key: String): Long = prefs(ctx).getLong("dl_$key", 0L)

    fun setDownloadId(ctx: Context, key: String, id: Long) {
        prefs(ctx).edit().putLong("dl_$key", id).apply()
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
