package com.yantkys.memonow.backup

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import com.yantkys.memonow.data.Post
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStreamWriter
import java.io.FilterReader
import java.io.Reader
import java.io.FilterInputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlin.math.abs

class InvalidBackup(message: String) : IllegalArgumentException(message)
class BackupDocument internal constructor(val exportedAt: Long, posts: List<Post>) {
    val posts: List<Post> = java.util.Collections.unmodifiableList(ArrayList(posts))
}

object BackupJson {
    const val MAX_BYTES = 10 * 1024 * 1024
    const val MAX_POSTS = 10_000
    const val MAX_BODY = 100_000
    const val MAX_SEQUENCE = Long.MAX_VALUE / 2 // Leave ample AUTOINCREMENT headroom.
    private const val MAX_DATE = 253402300799999L // 9999-12-31T23:59:59.999Z
    private val fields = setOf("id", "sequence", "body", "createdAt", "placeName", "weatherCode", "temperatureC", "weatherAt")
    private data class NumberText(val text: String)
    private fun verify(ok: Boolean, message: String = "バックアップの形式・値が正しくありません。") {
        if (!ok) throw InvalidBackup(message)
    }

    fun read(input: InputStream, checkCancelled: () -> Unit = {}): BackupDocument {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        JsonReader(StrictStrings(InputStreamReader(LimitedInput(input, checkCancelled), decoder))).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var exportedAt: Long? = null
            var posts: List<Post>? = null
            while (reader.hasNext()) {
                val name = reader.nextName()
                verify(seen.add(name))
                when (name) {
                    "app" -> verify(scalar(reader) == "memonow", "memonowのバックアップではありません。")
                    "schemaVersion" -> verify(integer(scalar(reader)) == 1L, "未対応のバックアップ形式です。")
                    "exportedAt" -> exportedAt = date(scalar(reader))
                    "posts" -> {
                        reader.beginArray()
                        val list = mutableListOf<Post>()
                        while (reader.hasNext()) {
                            verify(list.size < MAX_POSTS, "投稿は1万件までです。")
                            val values = mutableMapOf<String, Any?>()
                            reader.beginObject()
                            while (reader.hasNext()) {
                                val key = reader.nextName()
                                verify(key in fields && !values.containsKey(key))
                                values[key] = scalar(reader)
                            }
                            reader.endObject()
                            verify(values.keys == fields)
                            list += Post(
                                sequence = integer(values["sequence"]), id = string(values["id"]),
                                body = string(values["body"]), createdAt = date(values["createdAt"]),
                                placeName = values["placeName"]?.let(::string),
                                weatherCode = values["weatherCode"]?.let { integer(it).also { code -> verify(code in 0..999) }.toInt() },
                                temperatureC = values["temperatureC"]?.let { value ->
                                    verify(value is NumberText)
                                    (value as NumberText).text.toDoubleOrNull()?.also { verify(it.isFinite()) }
                                        ?: throw InvalidBackup("気温が正しくありません。")
                                },
                                weatherAt = values["weatherAt"]?.let(::date),
                            )
                        }
                        reader.endArray()
                        posts = list
                    }
                    else -> throw InvalidBackup("バックアップに未対応の項目があります。")
                }
            }
            reader.endObject()
            verify(reader.peek() == JsonToken.END_DOCUMENT)
            verify(seen == setOf("app", "schemaVersion", "exportedAt", "posts"))
            val result = BackupDocument(exportedAt!!, posts!!.toList())
            validate(result)
            return result
        }
    }

    fun encode(document: BackupDocument): ByteArray {
        validate(document)
        val output = LimitedBytes()
        JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
            writer.setIndent("  ")
            writer.beginObject().name("app").value("memonow").name("schemaVersion").value(1)
                .name("exportedAt").value(document.exportedAt).name("posts").beginArray()
            for (post in document.posts) {
                writer.beginObject().name("id").value(post.id).name("sequence").value(post.sequence)
                    .name("body").value(post.body).name("createdAt").value(post.createdAt)
                    .name("placeName").value(post.placeName).name("weatherCode").value(post.weatherCode)
                    .name("temperatureC").value(post.temperatureC).name("weatherAt").value(post.weatherAt).endObject()
            }
            writer.endArray().endObject()
        }
        return output.toByteArray()
    }

    internal fun validate(document: BackupDocument) {
        verify(document.exportedAt in 0..MAX_DATE)
        verify(document.posts.size <= MAX_POSTS, "投稿は1万件までです。")
        val ids = mutableSetOf<UUID>()
        val sequences = mutableSetOf<Long>()
        for (post in document.posts) {
            val id = try { UUID.fromString(post.id) } catch (_: IllegalArgumentException) { throw InvalidBackup("投稿IDが正しくありません。") }
            verify(id.toString().equals(post.id, ignoreCase = true) && id.variant() == 2 && id.version() in 1..8)
            verify(ids.add(id) && sequences.add(post.sequence), "投稿IDまたは順序が重複しています。")
            verify(post.sequence in 1..MAX_SEQUENCE)
            verify(post.createdAt in 0..MAX_DATE)
            verify(post.body.isNotBlank() && post.body.length <= MAX_BODY, "本文は空白以外で10万文字までです。")
            verify(Charsets.UTF_8.newEncoder().canEncode(post.body))
            post.placeName?.let { verify(it.isNotBlank() && it.length <= 1000 && Charsets.UTF_8.newEncoder().canEncode(it)) }
            post.weatherCode?.let { verify(it in 0..999) }
            post.temperatureC?.let { verify(it.isFinite() && it in -100.0..70.0) }
            val hasWeather = post.weatherCode != null || post.temperatureC != null
            verify(hasWeather == (post.weatherAt != null))
            post.weatherAt?.let { verify(it in 0..MAX_DATE && abs(it.toDouble() - post.createdAt) <= 20 * 60_000) }
        }
    }

    private fun scalar(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.NULL -> { reader.nextNull(); null }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> {
            val text = reader.nextString()
            verify(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(text))
            NumberText(text)
        }
        else -> throw InvalidBackup("項目の型が正しくありません。")
    }
    private fun integer(value: Any?): Long {
        verify(value is NumberText)
        val text = (value as NumberText).text
        verify(Regex("-?(0|[1-9][0-9]*)").matches(text))
        return text.toLongOrNull() ?: throw InvalidBackup("整数の範囲を超えています。")
    }
    private fun date(value: Any?): Long = integer(value).also { verify(it in 0..MAX_DATE, "日時が正しくありません。") }
    private fun string(value: Any?): String { verify(value is String); return value as String }
    // Android's JsonReader accepts unknown escapes and raw control characters even
    // when lenient=false. Preserve its structural parser but enforce string grammar.
    private class StrictStrings(reader: Reader) : FilterReader(reader) {
        private var quoted = false
        private var escaped = false
        private var unicode = 0
        private fun inspect(character: Char) {
            if (unicode > 0) {
                verify(character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F')
                unicode--
            } else if (escaped) {
                verify(character in "\"\\/bfnrtu")
                escaped = false
                if (character == 'u') unicode = 4
            } else if (quoted) {
                verify(character.code >= 32)
                if (character == '\\') escaped = true
                if (character == '"') quoted = false
            } else {
                verify(character.code >= 32 || character in " \t\r\n")
                if (character == '"') quoted = true
            }
        }
        override fun read(): Int = `in`.read().also { if (it >= 0) inspect(it.toChar()) }
        override fun read(buffer: CharArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { count ->
            if (count > 0) for (index in offset until offset + count) inspect(buffer[index])
        }
    }
    private class LimitedInput(input: InputStream, private val checkCancelled: () -> Unit) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int {
            checkCancelled()
            return `in`.read().also { if (it >= 0) { count++; verify(count <= MAX_BYTES, "ファイルは10MiBまでです。") } }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return `in`.read(buffer, offset, length).also {
                if (it > 0) { count += it; verify(count <= MAX_BYTES, "ファイルは10MiBまでです。") }
            }
        }
    }
    private class LimitedBytes : ByteArrayOutputStream() {
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            verify(count.toLong() + length <= MAX_BYTES, "ファイルは10MiBまでです。")
            super.write(buffer, offset, length)
        }
        override fun write(value: Int) {
            verify(count < MAX_BYTES, "ファイルは10MiBまでです。")
            super.write(value)
        }
    }
}
