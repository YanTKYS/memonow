package com.yantkys.memonow.backup

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface BackupFiles {
    suspend fun read(uri: Uri): BackupDocument
    suspend fun write(uri: Uri, bytes: ByteArray)
}
class AndroidBackupFiles(private val resolver: ContentResolver) : BackupFiles {
    override suspend fun read(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri)?.let { input ->
            BackupJson.read(input) { coroutineContext.ensureActive() }
        } ?: throw IOException("Input unavailable")
    }
    override suspend fun write(uri: Uri, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Output unavailable")
        output.use {
            for (start in bytes.indices step 8192) {
                coroutineContext.ensureActive()
                it.write(bytes, start, minOf(8192, bytes.size - start))
            }
            it.flush()
        }
    }
}
