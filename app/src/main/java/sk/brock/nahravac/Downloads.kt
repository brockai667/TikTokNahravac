package sk.brock.nahravac

import android.app.DownloadManager
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File

/** Sťahovanie videí cez DownloadManager do verejného Movies/TikTokNahravac/ a získanie Uri na zdieľanie. */
object Downloads {
    const val SUBDIR = "TikTokNahravac"
    const val NONE = -1

    /** Spustí sťahovanie; predchádzajúce sťahovanie tej istej epizódy (ak je) sa zruší aj so súborom. */
    @Suppress("DEPRECATION")
    fun enqueue(ctx: Context, ep: Episode): Long {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val oldId = Store.getDownloadId(ctx, ep.key)
        if (oldId > 0) {
            try {
                dm.remove(oldId)
            } catch (e: Exception) {
            }
        }
        val request = DownloadManager.Request(Uri.parse(ep.videoUrl))
            .setTitle(ep.title)
            .setDescription(ep.channel.name)
            .setMimeType("video/mp4")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "$SUBDIR/${ep.fileName}")
        if (Build.VERSION.SDK_INT < 29) request.allowScanningByMediaScanner()
        val id = dm.enqueue(request)
        Store.setDownloadId(ctx, ep.key, id)
        return id
    }

    /** DownloadManager.STATUS_* alebo NONE, ak sťahovanie neexistuje. */
    fun status(ctx: Context, id: Long): Int {
        if (id <= 0) return NONE
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) return c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        }
        return NONE
    }

    private fun localPath(ctx: Context, id: Long): String? {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val s = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)) ?: return null
                return Uri.parse(s).path
            }
        }
        return null
    }

    /**
     * Uri stiahnutého videa vhodné na zdieľanie s inou aplikáciou (s FLAG_GRANT_READ_URI_PERMISSION).
     * Poradie: MediaStore (Android 10+) -> FileProvider nad súborom -> Uri z DownloadManagera.
     */
    @Suppress("DEPRECATION")
    fun resolveShareUri(ctx: Context, ep: Episode, id: Long): Uri? {
        val path = localPath(ctx, id)
        val name = path?.let { File(it).name } ?: ep.fileName

        if (Build.VERSION.SDK_INT >= 29) {
            mediaStoreUri(ctx, name)?.let { return it }
        }

        val file = if (path != null) {
            File(path)
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "$SUBDIR/$name")
        }
        if (file.isFile && file.canRead()) {
            try {
                return FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            } catch (e: IllegalArgumentException) {
                // súbor mimo ciest z file_paths.xml -> skúsime posledný spôsob
            }
        }

        return try {
            ctx.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id)
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(29)
    private fun mediaStoreUri(ctx: Context, displayName: String): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        ctx.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(displayName, "Movies/$SUBDIR/%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0))
        }
        return null
    }
}
