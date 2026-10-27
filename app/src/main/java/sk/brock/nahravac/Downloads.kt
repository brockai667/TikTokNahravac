package sk.brock.nahravac

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sťahovanie videí do galérie (Movies/TikTokNahravac/), ktoré si robí appka sama (bez DownloadManagera).
 * Čerstvo stiahnuté video je v galérii NAJNOVŠIE, takže naučený krok "vyber prvé video" vyberie správne.
 */
object Downloads {
    const val SUBDIR = "TikTokNahravac"
    private const val RELATIVE_DIR = "Movies/$SUBDIR/"

    /** Stiahne video epizódy do galérie a zapamätá si jeho Uri. Blokuje - volať mimo hlavného vlákna. */
    fun fetchToGallery(ctx: Context, ep: Episode): Uri {
        val uri = if (Build.VERSION.SDK_INT >= 29) fetchViaMediaStore(ctx, ep) else fetchViaFile(ctx, ep)
        Store.setVideoUri(ctx, ep.key, uri.toString())
        return uri
    }

    /** Uri videa stiahnutého appkou, ak ešte existuje a dá sa otvoriť; inak null. */
    fun savedUri(ctx: Context, ep: Episode): Uri? {
        val text = Store.getVideoUri(ctx, ep.key) ?: return null
        val uri = Uri.parse(text)
        return try {
            val afd = ctx.contentResolver.openAssetFileDescriptor(uri, "r")
            if (afd == null) {
                null
            } else {
                afd.close()
                uri
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Android 10+ : MediaStore
    // ------------------------------------------------------------------

    @RequiresApi(29)
    private fun fetchViaMediaStore(ctx: Context, ep: Episode): Uri {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        // Predchádzajúci záznam s rovnakým názvom (ak je od nás) zmažeme, nech v galérii nie sú duplikáty.
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(ep.fileName, "$RELATIVE_DIR%"),
                null,
            )?.use { c ->
                val ids = ArrayList<Long>()
                while (c.moveToNext()) ids.add(c.getLong(0))
                for (id in ids) {
                    try {
                        resolver.delete(ContentUris.withAppendedId(collection, id), null, null)
                    } catch (e: Exception) {
                        // cudzí / už nezmazateľný záznam - nevadí, nový dostane iný názov
                    }
                }
            }
        } catch (e: Exception) {
            // mazanie starého záznamu je len upratovanie
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ep.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore nevytvoril záznam")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Nedá sa zapisovať do galérie")
            out.use { httpCopy(ep.videoUrl, it) }

            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            }
            resolver.update(uri, done, null, null)
            // Skener po dokončení môže prepísať DATE_TAKEN časom z metadát mp4 -> nastavíme ho ešte raz.
            try {
                val taken = ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis()) }
                resolver.update(uri, taken, null, null)
            } catch (e: Exception) {
                // len kozmetika zoradenia v galérii
            }
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (e2: Exception) {
            }
            throw e
        }
        return uri
    }

    // ------------------------------------------------------------------
    // Android 8-9 : súbor v Movies/TikTokNahravac/ + FileProvider
    // ------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun fetchViaFile(ctx: Context, ep: Episode): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Nepodarilo sa vytvoriť priečinok ${dir.absolutePath}")
        val file = File(dir, ep.fileName)
        val part = File(dir, ep.fileName + ".part")
        try {
            part.outputStream().use { httpCopy(ep.videoUrl, it) }
            if (file.exists() && !file.delete()) throw IOException("Nedá sa prepísať ${file.name}")
            if (!part.renameTo(file)) throw IOException("Nedá sa premenovať ${part.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** Prenesie telo odpovede do [out]; presmerovania sleduje, pri neúplnom prenose hodí výnimku. */
    private fun httpCopy(url: String, out: OutputStream) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TikTokNahravac/0.2")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val expected = conn.contentLengthLong
            val copied = conn.inputStream.use { it.copyTo(out, 64 * 1024) }
            out.flush()
            if (expected > 0 && copied != expected) throw IOException("Neúplné sťahovanie ($copied z $expected B)")
        } finally {
            conn.disconnect()
        }
    }
}
