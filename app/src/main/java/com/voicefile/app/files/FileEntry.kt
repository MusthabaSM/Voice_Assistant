package com.voicefile.app.files

import android.net.Uri

enum class FileSource { SAF, MEDIA }

/** A single searchable file found on the device. */
data class FileEntry(
    val name: String,
    val uri: Uri,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val source: FileSource,
    /** Human-readable parent path, e.g. "Documents/Work" or "Music". */
    val path: String? = null,
    /** For SAF entries: the tree the file belongs to (needed to re-grant access). */
    val treeUri: Uri? = null,
)

/** A directory the user authorized through the Storage Access Framework. */
data class FolderEntry(
    val name: String,
    val path: String,
    val treeUri: Uri,
    val documentUri: Uri,
)

/** Files the user can see, produced by a full scan. */
data class Index(
    val files: List<FileEntry>,
    val folders: List<FolderEntry>,
) {
    companion object {
        val EMPTY = Index(emptyList(), emptyList())
    }
}
