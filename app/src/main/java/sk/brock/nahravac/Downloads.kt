package sk.brock.nahravac

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Čo o videu naozaj vidí galéria (MediaStore) po stiahnutí. [durationMs] null / 0 = galéria ho ešte neindexovala. */
data class GalleryInfo(
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val pending: Boolean,
    val folder: String,
    /** Odkiaľ je obal uložený spolu s videom ("z fabriky" / "snímka z videa" / "žiadny"); null = nezisťovalo sa. */
    val cover: String? = null,
) {
    /** Jednoriadkový popis pre používateľa, napr. "ep-001.mp4 · 17,6 MB · DCIM/TikTokNahravac · dĺžka 0:58 · obal: z fabriky". */
    fun summary(): String {
        val size = String.format(Locale.forLanguageTag("sk"), "%.1f MB", sizeBytes / 1048576.0)
        val dur = durationMs
        val length = if (dur != null && dur > 0) {
            val s = (dur + 500) / 1000
            String.format(Locale.ROOT, "dĺžka %d:%02d", s / 60, s % 60)
        } else {
            "dĺžka ? (galéria ho ešte neindexovala)"
        }
        val coverText = if (cover != null) " · obal: $cover" else ""
        return "$name · $size · $folder · $length$coverText"
    }
}

/**
 * Sťahovanie videí do galérie (DCIM/TikTokNahravac/), ktoré si robí appka sama (bez DownloadManagera).
 * DCIM ukazuje každá galéria aj výber súborov v TikToku (Movies niektoré telefóny, napr. Samsung, v pickeri nezobrazia).
 * Čerstvo stiahnuté video je v galérii NAJNOVŠIE, takže naučený krok "vyber prvé video" vyberie správne.
 * Pred videom sa do galérie uloží aj obal epizódy (obrázok): z fabriky, alebo snímka z videa. Obal je vždy o viac než sekundu
 * starší než video, takže najnovší OBRÁZOK je obal tejto epizódy a najnovšia vec celkovo je video.
 */
object Downloads {
    const val SUBDIR = "TikTokNahravac"
    private const val RELATIVE_DIR = "DCIM/$SUBDIR/"

    /** Zdroj obalu do súhrnu ("obal: ..."). */
    private const val COVER_FACTORY = "z fabriky"
    private const val COVER_FRAME = "snímka z videa"
    private const val COVER_NONE = "žiadny"

    /** Pauza medzi uložením obalu a videa: DATE_ADDED má rozlíšenie 1 s, video musí byť presne novšie než obal. */
    private const val COVER_GAP_MS = 1_100L

    /** Najväčší prijatý obal z fabriky (B); väčšie súbory sa neberú. */
    private const val COVER_MAX_BYTES = 20 * 1024 * 1024

    /** Ako často (v bajtoch) sa hlási priebeh sťahovania. */
    private const val PROGRESS_STEP = 512 * 1024L

    /** Najdlhšie čakanie na dokončenie skenovania súboru médiá skenerom. */
    private const val SCAN_WAIT_SECONDS = 10L

    /**
     * Stiahne video epizódy (najprv do dočasného súboru), do galérie uloží obal a potom video, overí, že video galéria
     * naozaj vidí, a zapamätá si jeho Uri. Obal je z fabriky, inak snímka z videa (1 s); ak sa nepodarí ani to, video
     * sa uloží bez obalu.
     * [onProgress] dostane percentá 0-100 (-1 = dĺžka súboru nie je známa); volá sa z toho istého vlákna.
     * Blokuje - volať mimo hlavného vlákna. Pri neúspešnom uložení videa hodí [IOException].
     */
    fun fetchToGallery(ctx: Context, ep: Episode, onProgress: ((Int) -> Unit)? = null): Uri {
        val progress = percentCallback(onProgress)
        val cache = File(ctx.cacheDir, "dl-${ep.fileName}.part")
        try {
            cache.outputStream().use { httpCopy(ep.videoUrl, it, progress) }

            // Obal ide do galérie PRED videom: najnovší obrázok = obal tejto epizódy, najnovšia vec celkovo = video.
            var coverSource = COVER_NONE
            var coverUri: Uri? = null
            val cover = loadCover(ep, cache)
            if (cover != null) {
                try {
                    coverUri = if (Build.VERSION.SDK_INT >= 29) saveCoverViaMediaStore(ctx, ep, cover.bytes) else saveCoverViaFile(ctx, ep, cover.bytes)
                    coverSource = cover.source
                } catch (e: Exception) {
                    // obal je len bonus - video sa uloží aj bez neho
                }
            }
            if (coverUri != null) Thread.sleep(COVER_GAP_MS)

            val uri = if (Build.VERSION.SDK_INT >= 29) fetchViaMediaStore(ctx, ep, cache) else fetchViaFile(ctx, ep, cache)

            val info = verify(ctx, uri).copy(cover = coverSource)
            if (info.pending) throw IOException("Video sa neuložilo do galérie (${info.name} je stále rozpracované)")
            if (info.sizeBytes <= 0L) throw IOException("Video sa neuložilo do galérie (${info.name} má 0 B)")

            Store.setVideoUri(ctx, ep.key, uri.toString())
            Store.setCoverUri(ctx, ep.key, coverUri?.toString())
            Store.setLastDownload(ctx, info.summary(), uri.toString())
            return uri
        } finally {
            cache.delete()
        }
    }

    /**
     * Zistí z galérie (MediaStore) údaje o uloženom videu. Hodí [IOException], ak záznam neexistuje.
     * Dĺžka je null / 0, kým video neprešlo skenerom médií - to nie je chyba.
     */
    fun verify(ctx: Context, uri: Uri): GalleryInfo {
        try {
            if (uri.authority != MediaStore.AUTHORITY) return verifyFileUri(ctx, uri)

            val columns = arrayListOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.Media.DURATION,
            )
            if (Build.VERSION.SDK_INT >= 29) {
                columns.add(MediaStore.MediaColumns.IS_PENDING)
                columns.add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            ctx.contentResolver.query(uri, columns.toTypedArray(), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun text(column: String): String? {
                        val i = c.getColumnIndex(column)
                        return if (i >= 0 && !c.isNull(i)) c.getString(i) else null
                    }

                    fun number(column: String): Long? {
                        val i = c.getColumnIndex(column)
                        return if (i >= 0 && !c.isNull(i)) c.getLong(i) else null
                    }
                    return GalleryInfo(
                        name = text(MediaStore.MediaColumns.DISPLAY_NAME) ?: "?",
                        sizeBytes = number(MediaStore.MediaColumns.SIZE) ?: 0L,
                        durationMs = number(MediaStore.Video.Media.DURATION),
                        pending = (number(MediaStore.MediaColumns.IS_PENDING) ?: 0L) != 0L,
                        folder = text(MediaStore.MediaColumns.RELATIVE_PATH)?.trimEnd('/') ?: "?",
                    )
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Video sa neuložilo do galérie (kontrola zlyhala: ${e.javaClass.simpleName}: ${e.message ?: ""})")
        }
        throw IOException("Video sa neuložilo do galérie (záznam v galérii neexistuje)")
    }

    /** Android 8-9: Uri z FileProvideru nie je v MediaStore, preto názov a veľkosť z OpenableColumns a dĺžka zo samotného súboru. */
    private fun verifyFileUri(ctx: Context, uri: Uri): GalleryInfo {
        var name: String? = null
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
            }
        }
        if (size < 0) throw IOException("Video sa neuložilo do galérie (súbor neexistuje)")

        var duration: Long? = null
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            // dĺžka sa nezistila - nie je to dôvod na chybu
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
        return GalleryInfo(name ?: "?", size, duration, false, "DCIM/$SUBDIR")
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
    private fun fetchViaMediaStore(ctx: Context, ep: Episode, cached: File): Uri {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return insertViaMediaStore(ctx, collection, ep.fileName, "video/mp4") { out ->
            cached.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
    }

    @RequiresApi(29)
    private fun saveCoverViaMediaStore(ctx: Context, ep: Episode, bytes: ByteArray): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return insertViaMediaStore(ctx, collection, coverName(ep), "image/jpeg") { out -> out.write(bytes) }
    }

    /**
     * Vloží súbor [name] ([mime]) do DCIM/TikTokNahravac/ v [collection] (obrázky alebo videá): starý záznam s rovnakým
     * názvom zmaže, obsah zapíše cez [write], pošle ho skeneru médií a nastaví DATE_TAKEN na "teraz". Pri chybe záznam zmaže.
     */
    @RequiresApi(29)
    private fun insertViaMediaStore(ctx: Context, collection: Uri, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver

        // Predchádzajúci záznam s rovnakým názvom (ak je od nás) zmažeme, nech v galérii nie sú duplikáty.
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf(name, "$RELATIVE_DIR%"),
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
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore nevytvoril záznam")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Nedá sa zapisovať do galérie")
            out.use { write(it) }

            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            }
            resolver.update(uri, done, null, null)

            // Výslovne pošleme súbor skeneru médií (dĺžka, miniatúra) a počkáme, kým skončí.
            val path = filePathOf(ctx, uri)
            if (path != null) scanAndWait(ctx, path, mime)

            // Skener môže prepísať DATE_TAKEN časom z metadát súboru -> nastavíme ho ešte raz.
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

    /** Absolútna cesta k súboru záznamu (DATA je zastaraný, ale pre vlastné záznamy čitateľný); null, ak sa nedá zistiť. */
    @Suppress("DEPRECATION")
    private fun filePathOf(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** Pošle súbor skeneru médií a počká (najviac [SCAN_WAIT_SECONDS] s) na jeho dokončenie. Chyby sa ignorujú. */
    private fun scanAndWait(ctx: Context, path: String, mime: String) {
        val latch = CountDownLatch(1)
        try {
            MediaScannerConnection.scanFile(ctx, arrayOf(path), arrayOf(mime)) { _, _ -> latch.countDown() }
            latch.await(SCAN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // skenovanie je len pomoc, overenie záznamu ukáže skutočný stav
        }
    }

    // ------------------------------------------------------------------
    // Android 8-9 : súbor v DCIM/TikTokNahravac/ + FileProvider
    // ------------------------------------------------------------------

    private fun fetchViaFile(ctx: Context, ep: Episode, cached: File): Uri =
        storeViaFile(ctx, ep.fileName, "video/mp4") { out -> cached.inputStream().use { it.copyTo(out, 64 * 1024) } }

    private fun saveCoverViaFile(ctx: Context, ep: Episode, bytes: ByteArray): Uri =
        storeViaFile(ctx, coverName(ep), "image/jpeg") { out -> out.write(bytes) }

    /** Zapíše súbor [name] ([mime]) cez [write] do DCIM/TikTokNahravac/ (najprv ako ".part"), pošle ho skeneru médií a vráti jeho Uri. */
    @Suppress("DEPRECATION")
    private fun storeViaFile(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Nepodarilo sa vytvoriť priečinok ${dir.absolutePath}")
        val file = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            part.outputStream().use { write(it) }
            if (file.exists() && !file.delete()) throw IOException("Nedá sa prepísať ${file.name}")
            if (!part.renameTo(file)) throw IOException("Nedá sa premenovať ${part.name}")
        } catch (e: Exception) {
            part.delete()
            throw e
        }
        scanAndWait(ctx, file.absolutePath, mime)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }

    // ------------------------------------------------------------------
    // Obal epizódy
    // ------------------------------------------------------------------

    /** Obal (JPEG) a odkiaľ je: [COVER_FACTORY] alebo [COVER_FRAME]. */
    private class Cover(val bytes: ByteArray, val source: String)

    /** Názov súboru obalu v galérii, napr. "plainsight_004-catalog-dress-39-cover.jpg". */
    private fun coverName(ep: Episode): String = ep.fileName.replace(Regex("\\.mp4$", RegexOption.IGNORE_CASE), "") + "-cover.jpg"

    /** Obal z fabriky; keď ho epizóda nemá alebo sa nedá stiahnuť, snímka z [video]; keď ani tá nejde, null. */
    private fun loadCover(ep: Episode, video: File): Cover? {
        val url = ep.coverUrl
        if (url != null) {
            try {
                return Cover(httpImage(url), COVER_FACTORY)
            } catch (e: Exception) {
                // obal z fabriky chýba / nie je obrázok -> snímka z videa
            }
        }
        val frame = frameJpeg(video) ?: return null
        return Cover(frame, COVER_FRAME)
    }

    /** Stiahne obrázok (HTTP 200, čitateľné rozmery); inak hodí výnimku. */
    private fun httpImage(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.useCaches = false
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TikTokNahravac/0.2")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")

            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > COVER_MAX_BYTES) throw IOException("Obal je príliš veľký")
                }
            }
            val bytes = out.toByteArray()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Obal nie je obrázok")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    /** Snímka z videa v čase 1 s ako JPEG (kvalita 90); null, ak sa ju nepodarilo vybrať. */
    private fun frameJpeg(video: File): ByteArray? {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val frame = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            try {
                val out = ByteArrayOutputStream()
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
                return out.toByteArray()
            } finally {
                frame.recycle()
            }
        } catch (e: Exception) {
            return null
        } finally {
            try {
                mmr.release()
            } catch (e: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** Premení hlásenie (skopírované B, celkové B) na percentá 0-100; -1, ak dĺžka nie je známa. */
    private fun percentCallback(onProgress: ((Int) -> Unit)?): ((Long, Long) -> Unit)? {
        if (onProgress == null) return null
        return { copied, total ->
            onProgress(if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else -1)
        }
    }

    /**
     * Prenesie telo odpovede do [out]; presmerovania sleduje, pri neúplnom prenose hodí výnimku.
     * [progress] (skopírované B, celkové B alebo -1) sa volá približne každých 512 KB a raz na konci.
     */
    private fun httpCopy(url: String, out: OutputStream, progress: ((Long, Long) -> Unit)? = null) {
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

            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            var reported = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    copied += n
                    if (progress != null && copied - reported >= PROGRESS_STEP) {
                        reported = copied
                        progress(copied, expected)
                    }
                }
            }
            out.flush()
            if (expected > 0 && copied != expected) throw IOException("Neúplné sťahovanie ($copied z $expected B)")
            progress?.invoke(copied, expected)
        } finally {
            conn.disconnect()
        }
    }
}
