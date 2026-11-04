package cx.m42.restoapp.render

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.Json

/**
 * A JSON Schema, read into the shape a form can draw — and the rules that schema puts on a value.
 *
 * This is the part of «Настройки» that no declaration can describe (`settings.plan.md`): every
 * setting of a server carries a schema of its own, and the form is built from the schema that
 * arrives *with the record*. It lives here, in the renderer, and not in the app of the settings,
 * because the forms of models (`data-query-layer.plan.md`) and the editor of navigation will ask the
 * same question of the same kind of schema.
 *
 * Not all of draft-07, on purpose: the 107 manifests of a server between them use fourteen keywords
 * — `type, properties, required, additionalProperties, items, enum, minimum, maximum, minLength,
 * maxLength, minItems, uniqueItems, pattern, description` — and those are drawn. Anything else falls
 * back to [SchemaNode.Raw], a text field of JSON: a schema this cannot draw is still editable, and
 * the server checks it either way. A form that guessed would be worse than one that says «as it is».
 */
public sealed interface SchemaNode {

    /** The schema's own `description`: a line under the field, as the web shows it. */
    public val note: String?

    /** A line of text. `secret` — the value never came, so the field is a password field. */
    public data class Text(
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val pattern: String? = null,
        /** Human wording of [pattern] — the manifest's `patternHint`; a raw regexp is not an instruction. */
        val patternHint: String? = null,
        val nullable: Boolean = false,
        val secret: Boolean = false,
        override val note: String? = null,
    ) : SchemaNode

    public data class Num(
        val integer: Boolean,
        val min: Double? = null,
        val max: Double? = null,
        /** `"type": ["integer", "null"]` — «not set» is a value of its own. */
        val nullable: Boolean = false,
        override val note: String? = null,
    ) : SchemaNode

    public data class Flag(override val note: String? = null) : SchemaNode

    /** One of a fixed set. `null` among the values is a value, as the web's dropdown has it. */
    public data class Choice(
        val values: List<JsonElement>,
        val titles: Map<String, String> = emptyMap(),
        override val note: String? = null,
    ) : SchemaNode {
        /** Over this many, chips do not fit a phone and the choice goes into a sheet with a search. */
        public val big: Boolean get() = values.size > CHIPS_LIMIT
    }

    /** An array whose items are one of a fixed set: a multiple choice. */
    public data class Multi(
        val values: List<JsonElement>,
        val titles: Map<String, String> = emptyMap(),
        val minItems: Int? = null,
        val unique: Boolean = false,
        override val note: String? = null,
    ) : SchemaNode {
        public val big: Boolean get() = values.size > CHIPS_LIMIT
    }

    /** An array of anything else: a list of lines, or a repeating group when the item is an object. */
    public data class Items(
        val item: SchemaNode,
        val minItems: Int? = null,
        val unique: Boolean = false,
        override val note: String? = null,
    ) : SchemaNode

    /** An object with named properties, in the order of the schema. */
    public data class Fields(
        val properties: List<Property>,
        val required: Set<String> = emptySet(),
        /** `additionalProperties: true` — whatever else is in the value is kept, and shown as JSON. */
        val loose: Boolean = false,
        override val note: String? = null,
    ) : SchemaNode

    /** An object whose keys are the operator's own and whose values follow one schema: a map. */
    public data class Keyed(val value: SchemaNode, override val note: String? = null) : SchemaNode

    /** Whatever this form cannot draw: the value as JSON text. `json` — it must parse. */
    public data class Raw(val json: Boolean, override val note: String? = null) : SchemaNode

    public companion object {
        public const val CHIPS_LIMIT: Int = 8
    }
}

public data class Property(val name: String, val node: SchemaNode)

private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.num(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

private fun JsonObject.flag(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

/** `type` of a schema, always as a list: `"string"` and `["integer", "null"]` read the same way. */
private fun JsonObject.types(): List<String> = when (val type = this["type"]) {
    is JsonPrimitive -> listOfNotNull(type.contentOrNull)
    is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptyList()
}

/**
 * The schema of a value as a form can draw it.
 *
 * [settingType] is the type of the setting itself (`string`, `number`, `boolean`, `json`): 68 of the
 * settings of a server have no schema at all, and that is what says which field to put there.
 * [secret] turns a line of text into a password field — the stored value never arrives, so the field
 * starts empty and «empty» means «leave it as it was».
 */
public fun schemaNodeOf(
    schema: JsonElement?,
    settingType: String? = null,
    patternHint: String? = null,
    secret: Boolean = false,
): SchemaNode {
    val obj = schema as? JsonObject
    if (obj == null || obj.isEmpty()) {
        return when (settingType) {
            "boolean" -> SchemaNode.Flag()
            "number" -> SchemaNode.Num(integer = false)
            "json" -> SchemaNode.Raw(json = true)
            else -> SchemaNode.Text(secret = secret)
        }
    }

    val note = obj.str("description")
    val types = obj.types()
    val nullable = "null" in types

    // `enum` wins over the type, as it does in the web: the field becomes a choice whatever is in it.
    (obj["enum"] as? JsonArray)?.let { values ->
        return SchemaNode.Choice(values = values.toList(), note = note)
    }

    if ("boolean" in types) return SchemaNode.Flag(note)
    if ("integer" in types || "number" in types) {
        return SchemaNode.Num(
            integer = "integer" in types,
            min = obj.num("minimum"),
            max = obj.num("maximum"),
            nullable = nullable,
            note = note,
        )
    }
    if ("string" in types) {
        return SchemaNode.Text(
            minLength = obj.int("minLength"),
            maxLength = obj.int("maxLength"),
            pattern = obj.str("pattern"),
            patternHint = patternHint,
            nullable = nullable,
            secret = secret,
            note = note,
        )
    }

    if ("array" in types) {
        val items = obj["items"] as? JsonObject
        val minItems = obj.int("minItems")
        val unique = obj.flag("uniqueItems") == true
        val choices = (items?.get("enum") as? JsonArray)?.toList()
        if (choices != null) {
            return SchemaNode.Multi(values = choices, minItems = minItems, unique = unique, note = note)
        }
        if (items == null) return SchemaNode.Raw(json = true, note = note)
        return SchemaNode.Items(item = schemaNodeOf(items), minItems = minItems, unique = unique, note = note)
    }

    if ("object" in types || obj["properties"] != null || obj["additionalProperties"] != null) {
        val properties = (obj["properties"] as? JsonObject)?.entries.orEmpty()
        if (properties.isNotEmpty()) {
            val required = (obj["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            return SchemaNode.Fields(
                properties = properties.map { (name, child) -> Property(name, schemaNodeOf(child)) },
                required = required,
                // A loose object keeps what the schema does not describe; the editor shows that tail as JSON
                // rather than dropping it, which is the one thing it must not do with an operator's value.
                loose = obj.flag("additionalProperties") == true,
                note = note,
            )
        }
        // A map: the keys are the operator's (a channel, a step of the checklist), the values follow a schema.
        (obj["additionalProperties"] as? JsonObject)?.let { value ->
            return SchemaNode.Keyed(value = schemaNodeOf(value), note = note)
        }
        return SchemaNode.Raw(json = true, note = note)
    }

    return SchemaNode.Raw(json = settingType == "json" || types.isEmpty(), note = note)
}

/** The text of a scalar as a field shows it; null when there is nothing in it. */
public fun plainOf(value: JsonElement?): String? = when (value) {
    null, JsonNull -> null
    is JsonPrimitive -> value.contentOrNull
    else -> value.toString()
}

/** A title for one value of an `enum`: what the sheet and the chips show. */
public fun titleOf(value: JsonElement, titles: Map<String, String>, words: SchemaWords): String =
    when {
        value is JsonNull -> words.notSet
        else -> titles[plainOf(value)] ?: plainOf(value) ?: words.notSet
    }

private fun at(path: String, problem: String) = if (path.isEmpty()) problem else "$path: $problem"

private fun join(path: String, step: String) = if (path.isEmpty()) step else "$path.$step"

/**
 * Why this value would be refused, in words — or null when nothing here objects.
 *
 * The same contract the web's own check has (`lib/adminpanel/src/lib/setting-schema.js`): it may say
 * «looks right» about a value the server then refuses, but it never says «wrong» about one the server
 * would take. The server has the last word, and when it speaks, its sentence is the one shown.
 */
public fun problemOf(node: SchemaNode, value: JsonElement?, words: SchemaWords, path: String = ""): String? {
    val present = value?.takeIf { it !is JsonNull }
    return when (node) {
        is SchemaNode.Text -> when {
            // An empty field of a secret means «leave the stored value alone»: there is nothing to
            // check. A secret that *was* typed is checked like any other line of text.
            present == null -> if (node.nullable || node.secret) null else at(path, words.empty)
            else -> {
                val text = plainOf(present).orEmpty()
                when {
                    node.minLength != null && text.length < node.minLength -> at(path, words.atLeastChars(node.minLength))
                    node.maxLength != null && text.length > node.maxLength -> at(path, words.atMostChars(node.maxLength))
                    node.pattern != null && !matches(node.pattern, text) ->
                        at(path, node.patternHint?.let { words.mustContainOnly(it) } ?: words.mustMatch(node.pattern))
                    else -> null
                }
            }
        }

        is SchemaNode.Num -> when {
            present == null -> if (node.nullable) null else at(path, words.mustBeNumber)
            else -> {
                val number = (present as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                when {
                    number == null -> at(path, words.mustBeNumber)
                    node.integer && number != number.toLong().toDouble() -> at(path, words.mustBeWhole)
                    node.min != null && number < node.min -> at(path, words.atLeast(numberText(node.min)))
                    node.max != null && number > node.max -> at(path, words.atMost(numberText(node.max)))
                    else -> null
                }
            }
        }

        is SchemaNode.Flag -> if ((value as? JsonPrimitive)?.booleanOrNull == null) at(path, words.empty) else null

        is SchemaNode.Choice ->
            if (node.values.any { it == (value ?: JsonNull) }) null
            else at(path, words.oneOf(node.values.map { titleOf(it, node.titles, words) }))

        is SchemaNode.Multi -> {
            val items = (present as? JsonArray)?.toList()
            when {
                items == null -> at(path, words.empty)
                node.minItems != null && items.size < node.minItems -> at(path, words.atLeastItems(node.minItems))
                node.unique && items.distinct().size != items.size -> at(path, words.uniqueItems)
                items.any { item -> node.values.none { it == item } } ->
                    at(path, words.oneOf(node.values.map { titleOf(it, node.titles, words) }))
                else -> null
            }
        }

        is SchemaNode.Items -> {
            val items = (present as? JsonArray)?.toList()
            when {
                items == null -> at(path, words.empty)
                node.minItems != null && items.size < node.minItems -> at(path, words.atLeastItems(node.minItems))
                node.unique && items.distinct().size != items.size -> at(path, words.uniqueItems)
                else -> items.withIndex().firstNotNullOfOrNull { (index, item) ->
                    problemOf(node.item, item, words, join(path, index.toString()))
                }
            }
        }

        is SchemaNode.Fields -> {
            val fields = present as? JsonObject
            when {
                fields == null -> at(path, words.empty)
                else -> node.required.firstNotNullOfOrNull { name ->
                    if (fields[name] == null || fields[name] is JsonNull) at(path, words.missing(name)) else null
                } ?: node.properties.firstNotNullOfOrNull { property ->
                    // A property that is not there and is not required is not a problem: the schema's
                    // own rules only bind what the value actually carries.
                    fields[property.name]?.let { problemOf(property.node, it, words, join(path, property.name)) }
                }
            }
        }

        is SchemaNode.Keyed -> {
            val fields = present as? JsonObject
            when {
                fields == null -> at(path, words.empty)
                fields.keys.any { it.isBlank() } -> at(path, words.empty)
                else -> fields.entries.firstNotNullOfOrNull { (key, child) ->
                    problemOf(node.value, child, words, join(path, key))
                }
            }
        }

        // The editor of raw JSON hands a string back when the text does not parse: that string is the
        // one thing a value of a `json` setting cannot be, so it reads as «this is not JSON».
        is SchemaNode.Raw -> when {
            !node.json -> null
            present == null -> at(path, words.empty)
            present is JsonPrimitive && present.isString -> at(path, words.notJson)
            else -> null
        }
    }
}

private val pretty = Json { prettyPrint = true }

/** A value as the raw-JSON field shows it; an empty field for nothing at all. */
public fun prettyJson(value: JsonElement?): String =
    if (value == null || value is JsonNull) "" else pretty.encodeToString(JsonElement.serializer(), value)

/**
 * The text of the raw-JSON field as a value: what parses, parsed — and what does not, as a string.
 *
 * Handing the text back as a string is deliberate: a value of a `json` setting is never a bare
 * string, so [problemOf] reads one as «this is not JSON» and the screen keeps «Сохранить» shut,
 * without the field needing a second channel to report itself.
 */
public fun parseJsonOrString(text: String): JsonElement? =
    if (text.isBlank()) null else runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }

/** A number as a field shows it: a whole one without a tail. */
public fun numberText(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

/**
 * Whether the text matches the schema's `pattern`.
 *
 * Kotlin's regular expressions are not JavaScript's, and a pattern Ajv compiles may be one this
 * cannot: an unparsable pattern is a broken schema, not a broken value, so it is left to the server
 * — the same thing the web's own check does.
 */
private fun matches(pattern: String, text: String): Boolean =
    runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(true)
