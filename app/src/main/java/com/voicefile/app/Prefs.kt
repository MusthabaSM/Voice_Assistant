package com.voicefile.app

import android.content.Context
import android.net.Uri
import com.voicefile.app.matching.Language

/**
 * Tiny SharedPreferences wrapper. Stores only:
 *  - the selected language
 *  - the set of user-authorized folder tree URIs
 *  - which speech model variant is installed
 *
 * No audio, no recognized text, no search history is ever persisted.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("voicefile_prefs", Context.MODE_PRIVATE)

    var language: Language
        get() = runCatching { Language.valueOf(sp.getString(KEY_LANG, Language.EN.name) ?: "") }
            .getOrDefault(Language.EN)
        set(value) {
            sp.edit().putString(KEY_LANG, value.name).apply()
        }

    fun folderUris(): List<Uri> =
        sp.getStringSet(KEY_FOLDERS, emptySet())
            .orEmpty()
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    fun addFolder(uri: Uri) {
        val current = sp.getStringSet(KEY_FOLDERS, mutableSetOf())!!.toMutableSet()
        current.add(uri.toString())
        sp.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    fun removeFolder(uri: Uri) {
        val current = sp.getStringSet(KEY_FOLDERS, mutableSetOf())!!.toMutableSet()
        current.remove(uri.toString())
        sp.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    /** Variant id ("tiny"/"small") that finished downloading, or "none". */
    var modelVariant: String
        get() = sp.getString(KEY_MODEL, "none") ?: "none"
        set(value) {
            sp.edit().putString(KEY_MODEL, value).apply()
        }

    private companion object {
        const val KEY_LANG = "language"
        const val KEY_FOLDERS = "folder_uris"
        const val KEY_MODEL = "model_variant"
    }
}
