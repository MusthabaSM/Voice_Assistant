package com.voicefile.app.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.voicefile.app.Prefs

/**
 * Manages the set of folders the user authorized through the Storage Access
 * Framework. Permissions are taken persistently so the user is never asked to
 * re-select the same folder on later launches.
 */
class StorageRepository(private val context: Context, private val prefs: Prefs) {

    fun folders(): List<Uri> = prefs.folderUris()

    fun hasFolders(): Boolean = prefs.folderUris().isNotEmpty()

    fun addTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        prefs.addFolder(uri)
    }

    fun removeTree(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        prefs.removeFolder(uri)
    }
}
