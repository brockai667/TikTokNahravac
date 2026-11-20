package sk.brock.nahravac

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Spustenie bez dotyku obrazovky: broadcast [ACTION] robí to isté ako veľké tlačidlo v appke (obnoví zoznamy
 * všetkých kanálov a nahrá najnovší nový diel každého). Beží len keď je zapnutá služba dostupnosti, inak len zapíše
 * do logu (tag [TAG]). Chránené signature povolením (aplikácia sk.brock.terminal je podpísaná rovnakým kľúčom);
 * pre `adb shell` pozri [RunAllShellReceiver].
 */
open class RunAllReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "sk.brock.nahravac.RUN_ALL"
        const val TAG = "NahravacRunAll"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val svc = TikTokAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "RUN_ALL ignorovane: sluzba dostupnosti nie je zapnuta")
            return
        }
        if (TikTokShare.installedPackage(app) == null) {
            Log.w(TAG, "RUN_ALL ignorovane: TikTok nie je nainstalovany")
            return
        }
        // Sieť mimo hlavného vlákna; broadcast sa skončí hneď (proces drží služba dostupnosti).
        Thread {
            try {
                val items = ArrayList<Replayer.Item>()
                for (ch in Repo.loadChannels(app)) {
                    if (!Repo.isSupported(ch)) continue
                    try {
                        val newest = Repo.fetchEpisodes(ch, 3).firstOrNull() ?: continue
                        if (!Store.isDone(app, newest.key)) items.add(Replayer.Item(newest, newest.text))
                    } catch (e: Exception) {
                        Log.w(TAG, "kanal ${ch.name}: ${e.message}")
                    }
                }
                if (items.isEmpty()) {
                    Log.i(TAG, "RUN_ALL: ziadny novy diel")
                } else if (TikTokAccessibilityService.instance?.runAll(items) == true) {
                    Log.i(TAG, "RUN_ALL: spustene (${items.size})")
                } else {
                    Log.w(TAG, "RUN_ALL: sluzba je zaneprazdnena")
                }
            } catch (e: Exception) {
                Log.w(TAG, "RUN_ALL zlyhalo: ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }
}

/** To isté pre `adb shell am broadcast -n ...` (shell má povolenie DUMP, nie naše signature povolenie). */
class RunAllShellReceiver : RunAllReceiver() {
    companion object {
        /** Skúška odoslania do kampane: extras url, caption, account (bez @), dry (default true). */
        const val ACTION_SUBMIT_TEST = "sk.brock.nahravac.SUBMIT_TEST"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SUBMIT_TEST) {
            super.onReceive(context, intent)
            return
        }
        val svc = TikTokAccessibilityService.instance
        if (svc == null) {
            Log.w(TAG, "SUBMIT_TEST ignorovane: sluzba dostupnosti nie je zapnuta")
            return
        }
        val url = intent.getStringExtra("url") ?: ""
        val caption = intent.getStringExtra("caption") ?: ""
        val account = intent.getStringExtra("account") ?: ""
        val dry = intent.getBooleanExtra("dry", true)
        if (url.isBlank() || caption.isBlank() || account.isBlank()) {
            Log.w(TAG, "SUBMIT_TEST ignorovane: chyba url, caption alebo account")
            return
        }
        if (svc.submitTest(url, caption, account, dry)) {
            Log.i(TAG, "SUBMIT_TEST: spustene (dry=$dry)")
        } else {
            Log.w(TAG, "SUBMIT_TEST: sluzba je zaneprazdnena")
        }
    }
}
