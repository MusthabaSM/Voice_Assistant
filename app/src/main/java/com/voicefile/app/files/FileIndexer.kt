package com.voicefile.app.files

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds a full in-memory index of searchable files from two sources:
 *
 *  1. Folders the user authorized with the Storage Access Framework —
 *     walked recursively through DocumentsContract. This is the only way to
 *     see ordinary documents on Android 11+, so it is the primary source.
 *  2. MediaStore — audio/video/image collections (plus Downloads where the
 *     platform exposes them), when the matching runtime permission is granted.
 *
 * Nothing bypasses the Android storage sandbox; files the app is not allowed
 * to see simply do not appear.
 */
class FileIndexer(private val context: Context, private val repo: StorageRepository) {

    companion object {
        private const val MAX_FILES = 50_000
        private const val MAX_DEPTH = 10
        private val SKIPPED_DIRS = setOf("android", "data", "obb", ".thumbnails", "lost.dir")
    }

    suspend fun build(): Index = withContext(Dispatchers.IO) {
        val files = LinkedHashMap<String, FileEntry>()
        val folders = ArrayList<FolderEntry>()

        // 1) User-authorized folders (primary source for documents).
        for (tree in repo.folders()) {
            runCatching { walkTree(tree, files, folders) }
        }

        // 2) MediaStore collections (media coverage / convenience).
        if (files.size < MAX_FILES) {
            mediaStoreFiles().forEach { entry ->
                files.putIfAbsent(mediaKey(entry), entry)
            }
        }

        Index(files.values.toList(), folders)
    }

    private fun mediaKey(e: FileEntry) = e.name.lowercase() + "|" + e.size

    // ------------------------------------------------------------------ SAF walk

    private data class Node(val docId: String, val path: String, val depth: Int)

    private fun walkTree(
        tree: Uri,
        outFiles: MutableMap<String, FileEntry>,
        outFolders: MutableList<FolderEntry>,
    ) {
        val rootDocId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = rootDocId
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifEmpty { "storage" }
        outFolders.add(
            FolderEntry(
                name = rootName,
                path = rootName,
                treeUri = tree,
                documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootDocId),
            ),
        )

        val stack = ArrayDeque<Node>()
        stack.addLast(Node(rootDocId, rootName, 0))

        while (stack.isNotEmpty() && outFiles.size < MAX_FILES) {
            val node = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, node.docId)
            val cursor = runCatching {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    ),
                    null,
                    null,
                    null,
                )
            }.getOrNull() ?: continue

            cursor.use { c ->
                while (c.moveToNext() && outFiles.size < MAX_FILES) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val mime = c.getString(2)
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val modified = if (c.isNull(4)) 0L else c.getLong(4)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name.lowercase() in SKIPPED_DIRS) continue
                        if (node.depth + 1 > MAX_DEPTH) continue
                        val childPath = "$node.path/$name"
                        outFolders.add(
                            FolderEntry(
                                name = name,
                                path = childPath,
                                treeUri = tree,
                                documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                            ),
                        )
                        stack.addLast(Node(docId, childPath, node.depth + 1))
                    } else {
                        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        outFiles.putIfAbsent(
                            name.lowercase() + "|" + size,
                            FileEntry(
                                name = name,
                                uri = uri,
                                mimeType = mime,
                                size = size,
                                lastModified = modified,
                                source = FileSource.SAF,
                                path = node.path,
                                treeUri = tree,
                            ),
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- MediaStore

    private fun mediaStoreFiles(): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        val sdk = Build.VERSION.SDK_INT

        val queries = ArrayList<Uri>()
        if (sdk >= 33) {
            if (granted(android.Manifest.permission.READ_MEDIA_AUDIO)) {
                queries.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            }
            if (granted(android.Manifest.permission.READ_MEDIA_VIDEO)) {
                queries.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            }
            if (granted(android.Manifest.permission.READ_MEDIA_IMAGES)) {
                queries.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            }
        } else if (granted(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            queries.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            queries.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            queries.add(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (sdk >= 29) {
                queries.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            queries.add(MediaStore.Files.getContentUri("external"))
        }

        for (uri in queries) {
            if (out.size >= MAX_FILES) break
            runCatching { queryCollection(uri, out) }
        }
        return out
    }

    private fun queryCollection(uri: Uri, out: MutableList<FileEntry>) {
        val projection = ArrayList<String>().apply {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= 29) add(MediaStore.MediaColumns.RELATIVE_PATH)
        }.toTypedArray()

        val cursor: Cursor? = if (Build.VERSION.SDK_INT >= 30) {
            val args = android.os.Bundle().apply {
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, MAX_FILES - out.size)
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
                )
            }
            context.contentResolver.query(uri, projection, args, null)
        } else {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT ${MAX_FILES - out.size}",
            )
        }

        cursor?.use { c ->
            val idIdx = c.getColumnIndex(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val pathIdx = if (Build.VERSION.SDK_INT >= 29) {
                c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else -1

            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getLong(idIdx)
                val name = c.getString(nameIdx) ?: continue
                if (name.startsWith(".")) continue
                val mime = if (c.isNull(mimeIdx)) null else c.getString(mimeIdx)
                val size = if (c.isNull(sizeIdx)) -1L else c.getLong(sizeIdx)
                val modified = if (c.isNull(dateIdx)) 0L else c.getLong(dateIdx) * 1000
                val relPath = if (pathIdx >= 0 && !c.isNull(pathIdx)) {
                    c.getString(pathIdx)?.trimEnd('/')
                } else null

                out.add(
                    FileEntry(
                        name = name,
                        uri = ContentUris.withAppendedId(uri, id),
                        mimeType = mime,
                        size = size,
                        lastModified = modified,
                        source = FileSource.MEDIA,
                        path = relPath,
                    ),
                )
            }
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
