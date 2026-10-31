package com.voicefile.app.files

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap

/** Outcome of trying to open a file or folder in another app. */
sealed interface OpenOutcome {
    data object Opened : OpenOutcome
    data object NoApp : OpenOutcome
    data class Failed(val reason: String) : OpenOutcome
}

/**
 * Opens files with ACTION_VIEW + a content URI grant, so the file plays or
 * displays in a real, user-chosen app without any manual navigation.
 */
object FileOpener {

    fun open(activity: Activity, entry: FileEntry): OpenOutcome {
        val mime = resolveMime(entry)
        for (candidate in listOf(mime, "*/*").distinct()) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(entry.uri, candidate)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            try {
                activity.startActivity(intent)
                return OpenOutcome.Opened
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (e: Exception) {
                return OpenOutcome.Failed(e.message ?: "Could not open ${entry.name}")
            }
        }
        return OpenOutcome.NoApp
    }

    fun openFolder(activity: Activity, folder: FolderEntry): OpenOutcome {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                folder.documentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
            )
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        return try {
            activity.startActivity(intent)
            OpenOutcome.Opened
        } catch (_: ActivityNotFoundException) {
            OpenOutcome.NoApp
        } catch (e: Exception) {
            OpenOutcome.Failed(e.message ?: "Could not open ${folder.name}")
        }
    }

    private fun resolveMime(entry: FileEntry): String {
        val declared = entry.mimeType
        if (!declared.isNullOrBlank() && declared != "application/octet-stream") {
            return declared
        }
        val ext = entry.name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
}
