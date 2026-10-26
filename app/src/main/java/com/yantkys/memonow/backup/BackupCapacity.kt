package com.yantkys.memonow.backup

import com.yantkys.memonow.data.Post

// Conservative bound: JSON can escape one UTF-16 unit as six ASCII bytes.
// This uses String.length, never re-serializes the full history on the UI thread.
fun backupCapacityWarning(posts: List<Post>): String? {
    if (posts.size > BackupJson.MAX_POSTS) return "1万件の上限を超えているため、現在は全件バックアップできません。記録は自動削除しません。"
    if (posts.size == BackupJson.MAX_POSTS) return "1万件のバックアップ上限に達しています。追加すると全件保存できなくなります。"
    val upperBytes = 256L + posts.sumOf { 512L + 6L * (it.body.length.toLong() + (it.placeName?.length ?: 0)) }
    return if (upperBytes >= BackupJson.MAX_BYTES * 9L / 10) {
        "本文量が多く、全件バックアップが10MiB上限で失敗する可能性があります（概算）。早めにJSON保存で確認してください。"
    } else null
}
