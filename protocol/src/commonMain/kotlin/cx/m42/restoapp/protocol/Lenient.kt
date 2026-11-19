package cx.m42.restoapp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The optional hints of 2026-11-18 (P1–P7) are read so that a wrong one never costs the declaration: a value of
 * the wrong shape is read as no value, a broken element of a list is dropped and the rest kept. «Unknown — drawn
 * as without the key» holds for the shape as well as for the words.
 */

/** A value of [inner], or null when the server sent something else. */
public open class LenientSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    private val written = inner.nullable
    override val descriptor: SerialDescriptor get() = written.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val json = decoder as? JsonDecoder ?: return written.deserialize(decoder)
        val element = json.decodeJsonElement()
        return runCatching { json.json.decodeFromJsonElement(inner, element) }.getOrNull()
    }

    override fun serialize(encoder: Encoder, value: T?) {
        written.serialize(encoder, value)
    }
}

/**
 * A list of [inner] without the elements that are not one; something that is not a list at all is no list.
 * Null and not empty when absent: the contract has `minItems: 1`, so a list a server sends is never empty.
 */
public open class LenientListSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<List<T>?> {
    private val written = ListSerializer(inner).nullable
    override val descriptor: SerialDescriptor get() = written.descriptor

    override fun deserialize(decoder: Decoder): List<T>? {
        val json = decoder as? JsonDecoder ?: return written.deserialize(decoder)
        val array = json.decodeJsonElement() as? JsonArray ?: return null
        return array.mapNotNull { runCatching { json.json.decodeFromJsonElement(inner, it) }.getOrNull() }
    }

    override fun serialize(encoder: Encoder, value: List<T>?) {
        written.serialize(encoder, value)
    }
}

public object LenientString : LenientSerializer<String>(String.serializer())

public object LenientInt : LenientSerializer<Int>(Int.serializer())

public object LenientBalance : LenientSerializer<AccountBalance>(AccountBalance.serializer())

public object LenientCreditRate : LenientSerializer<CreditRate>(CreditRate.serializer())

public object LenientBoolean : LenientSerializer<Boolean>(Boolean.serializer())

public object LenientBoard : LenientSerializer<Board>(Board.serializer())

public object LenientColumns : LenientListSerializer<Column>(Column.serializer())

public object LenientSummary : LenientListSerializer<SummaryBlock>(SummaryBlock.serializer())

/** `{value: string}`: the pairs whose value is a string; anything else in it is dropped. */
public object LenientStringMap : KSerializer<Map<String, String>> {
    private val written = MapSerializer(String.serializer(), String.serializer())
    override val descriptor: SerialDescriptor get() = written.descriptor

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val json = decoder as? JsonDecoder ?: return written.deserialize(decoder)
        val obj = json.decodeJsonElement() as? JsonObject ?: return emptyMap()
        return obj.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content } }.toMap()
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        written.serialize(encoder, value)
    }
}
