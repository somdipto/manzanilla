package com.agentdeck.hotel

import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.msgpack.core.MessagePack
import org.msgpack.core.MessagePacker
import org.msgpack.value.Value
import org.msgpack.value.ValueType

/** Fish uses MessagePack binary maps, never JSON/base64 WebSocket frames. */
internal object FishWire {
    fun matchesModel(id: String): Boolean = id == "gemini-3.8-flash" || id == "models/gemini-3.8-flash"
    fun recoverable(event: JSONObject): Boolean = event.optBoolean("recoverable") &&
        event.optString("error_type") in setOf("engine_error", "invalid_request")
    fun encode(value: JSONObject): ByteString = MessagePack.newDefaultBufferPacker().use { packer ->
        pack(packer, value)
        packer.toByteArray().toByteString()
    }
    fun audio(pcm: ByteArray): ByteString = MessagePack.newDefaultBufferPacker().use { packer ->
        packer.packMapHeader(2)
        packer.packString("type").packString("audio")
        packer.packString("data").packBinaryHeader(pcm.size).writePayload(pcm)
        packer.toByteArray().toByteString()
    }
    private fun pack(p: MessagePacker, value: Any?) {
        when (value) {
            null, JSONObject.NULL -> p.packNil()
            is JSONObject -> {
                p.packMapHeader(value.length())
                value.keys().forEach { key -> p.packString(key); pack(p, value.get(key)) }
            }
            is JSONArray -> { p.packArrayHeader(value.length()); for (i in 0 until value.length()) pack(p, value.get(i)) }
            is ByteArray -> { p.packBinaryHeader(value.size); p.writePayload(value) }
            is String -> p.packString(value)
            is Boolean -> p.packBoolean(value)
            is Int -> p.packInt(value)
            is Long -> p.packLong(value)
            is Number -> p.packDouble(value.toDouble())
            else -> error("Unsupported wire value")
        }
    }
    fun decode(bytes: ByteString): JSONObject {
        require(bytes.size <= 1024 * 1024)
        return MessagePack.newDefaultUnpacker(bytes.toByteArray()).use { unpacker ->
            val result = unpack(unpacker.unpackValue(), 0) as? JSONObject ?: error("Expected map")
            require(!unpacker.hasNext())
            result
        }
    }
    private fun unpack(value: Value, depth: Int): Any {
        require(depth < 16)
        return when (value.valueType) {
            ValueType.NIL -> JSONObject.NULL
            ValueType.BOOLEAN -> value.asBooleanValue().boolean
            ValueType.INTEGER -> value.asIntegerValue().toLong()
            ValueType.FLOAT -> value.asFloatValue().toDouble()
            ValueType.STRING -> value.asStringValue().asString()
            ValueType.BINARY -> value.asBinaryValue().asByteArray()
            ValueType.ARRAY -> JSONArray().also { out -> value.asArrayValue().forEach { out.put(unpack(it, depth + 1)) } }
            ValueType.MAP -> JSONObject().also { out -> value.asMapValue().map().forEach { (key, item) ->
                require(key.isStringValue)
                out.put(key.asStringValue().asString(), unpack(item, depth + 1))
            } }
            else -> error("Unsupported wire value")
        }
    }
    fun asrConfig() = JSONObject().put("type", "config")
        .put("audio", JSONObject().put("format", "pcm_s16le").put("sample_rate", 16000).put("channels", 1))
        .put("turn_detection", JSONObject().put("mode", "auto").put("eager", false))
        .put("results", JSONObject().put("drafts", true).put("diarize", false).put("ignore_timestamps", false))

    fun validateStarted(event: JSONObject) {
        require(event.getString("session_id").isNotBlank())
        val config = event.getJSONObject("config")
        val audio = config.getJSONObject("audio")
        val turns = config.getJSONObject("turn_detection")
        val results = config.getJSONObject("results")
        require(audio.getString("format") == "pcm_s16le" && audio.getInt("sample_rate") == 16000 && audio.getInt("channels") == 1)
        require(turns.getString("mode") == "auto" && turns.getBoolean("eager") == false)
        require(results.getBoolean("drafts") && !results.getBoolean("diarize") && !results.getBoolean("ignore_timestamps"))
    }
    fun ttsStart(voice: String, language: String) = JSONObject().put("event", "start")
        .put("request", JSONObject().put("text", "").put("reference_id", voice).put("format", "pcm")
            .put("sample_rate", 24000).put("chunk_length", 120).put("normalize", true).put("language", language)
            .put("prosody", JSONObject().put("speed", 1.0).put("volume", 0).put("normalize_loudness", true))
            .put("latency", "normal"))
}
