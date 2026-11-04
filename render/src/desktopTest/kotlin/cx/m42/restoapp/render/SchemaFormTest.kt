package cx.m42.restoapp.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The form by a schema, row by row of the table in `settings.plan.md`: what a schema turns into, and
 * what it then refuses.
 *
 * The schemas here are the real ones — copied from the manifests of this server's settings — so the
 * table is checked against what a server actually sends, not against what a reader of draft-07 might
 * imagine it sends.
 */
class SchemaFormTest {

    private val words = SchemaWords("ru")

    private fun schema(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun value(text: String): JsonElement = Json.parseToJsonElement(text)

    // ---- what a schema turns into ----

    @Test
    fun noSchemaAtAllIsAFieldByTheTypeOfTheSetting() {
        assertTrue(schemaNodeOf(null, "string") is SchemaNode.Text)
        assertTrue(schemaNodeOf(null, "number") is SchemaNode.Num)
        assertTrue(schemaNodeOf(null, "boolean") is SchemaNode.Flag)
        assertTrue(schemaNodeOf(null, "json") is SchemaNode.Raw)
    }

    @Test
    fun aSecretIsALineOfTextThatIsNeverShown() {
        val node = schemaNodeOf(schema("""{"type": "string", "minLength": 36}"""), "string", secret = true)
        assertTrue((node as SchemaNode.Text).secret)
        // Nothing typed means «leave the stored value alone», and there is nothing to check.
        assertNull(problemOf(node, null, words))
        // Typed, it is checked like any other line: the server would refuse a short one.
        assertNotNull(problemOf(node, JsonPrimitive("short"), words))
    }

    @Test
    fun aStringCarriesItsLengthAndItsPattern() {
        val node = schemaNodeOf(
            schema("""{"type": "string", "pattern": "^[a-z0-9\\-]+$", "minLength": 3}"""),
            "string",
            patternHint = "lowercase latin letters, digits and hyphens",
        )
        assertEquals(SchemaNode.Text(minLength = 3, pattern = "^[a-z0-9\\-]+$", patternHint = "lowercase latin letters, digits and hyphens"), node)
        assertNull(problemOf(node, JsonPrimitive("resto-stand"), words))
        assertEquals("Не короче 3 символов", problemOf(node, JsonPrimitive("ab"), words))
        // The hint of the manifest, never the raw expression: `^[a-z0-9\-]+$` instructs nobody.
        assertEquals(
            "Должно содержать только: lowercase latin letters, digits and hyphens",
            problemOf(node, JsonPrimitive("Resto Stand!"), words),
        )
        assertEquals("Не может быть пустым", problemOf(node, JsonNull, words))
    }

    @Test
    fun aPatternWithoutAHintIsSaidAsTheServerSaysIt() {
        val node = schemaNodeOf(schema("""{"type": "string", "pattern": "^([01]?[0-9]|2[0-3]):[0-5][0-9]$"}"""), "string")
        assertEquals("Должно подходить под выражение: ^([01]?[0-9]|2[0-3]):[0-5][0-9]$", problemOf(node, JsonPrimitive("9 утра"), words))
    }

    @Test
    fun aWholeNumberWithBounds() {
        val node = schemaNodeOf(schema("""{"type": "integer", "minimum": 1, "maximum": 4}"""), "number")
        assertEquals(SchemaNode.Num(integer = true, min = 1.0, max = 4.0), node)
        assertNull(problemOf(node, JsonPrimitive(3), words))
        assertEquals("4 или меньше", problemOf(node, JsonPrimitive(9), words))
        assertEquals("1 или больше", problemOf(node, JsonPrimitive(0), words))
        assertEquals("Целое число", problemOf(node, JsonPrimitive(2.5), words))
        // What the field hands over when the text is not a number at all.
        assertEquals("Должно быть числом", problemOf(node, JsonPrimitive("12x"), words))
        assertEquals("Должно быть числом", problemOf(node, JsonNull, words))
    }

    @Test
    fun aWholeNumberOrNothing() {
        val node = schemaNodeOf(schema("""{"type": ["integer", "null"], "minimum": 0}"""), "number")
        assertTrue((node as SchemaNode.Num).nullable)
        // «Not set» is a value of its own here, and the server takes it.
        assertNull(problemOf(node, JsonNull, words))
        assertNull(problemOf(node, JsonPrimitive(0), words))
    }

    @Test
    fun aChoiceIsChipsWhileItFitsAndASearchWhenItDoesNot() {
        val small = schemaNodeOf(schema("""{"type": "string", "enum": ["en", "ru", "fr", "de"]}"""), "string")
        assertTrue(small is SchemaNode.Choice && !small.big)
        val big = schemaNodeOf(
            schema("""{"type": "string", "enum": ${(1..459).joinToString(prefix = "[", postfix = "]") { "\"z$it\"" }}}"""),
            "string",
        )
        assertTrue(big is SchemaNode.Choice && big.big, "459 values do not fit on chips")
        assertEquals("Одно из: en, ru, fr, de", problemOf(small, JsonPrimitive("it"), words))
        assertNull(problemOf(small, JsonPrimitive("ru"), words))
    }

    @Test
    fun nullAmongTheChoicesIsAChoice() {
        val node = schemaNodeOf(schema("""{"enum": ["a", null]}"""), "string")
        assertNull(problemOf(node, JsonNull, words))
        assertEquals("Одно из: a, Не задано", problemOf(node, JsonPrimitive("b"), words))
    }

    @Test
    fun manyOfASet() {
        val node = schemaNodeOf(
            schema("""{"type": "array", "items": {"type": "string", "enum": ["RU", "KZ", "DE"]}, "uniqueItems": true, "minItems": 1}"""),
            "json",
        )
        assertTrue(node is SchemaNode.Multi)
        assertNull(problemOf(node, value("""["RU", "KZ"]"""), words))
        assertEquals("Не меньше 1 элементов", problemOf(node, value("[]"), words))
        assertEquals("Значения не должны повторяться", problemOf(node, value("""["RU", "RU"]"""), words))
        assertEquals("Одно из: RU, KZ, DE", problemOf(node, value("""["FR"]"""), words))
    }

    @Test
    fun anArrayOfLines() {
        val node = schemaNodeOf(schema("""{"type": "array", "items": {"type": "string"}, "minItems": 0}"""), "json")
        assertTrue(node is SchemaNode.Items && node.item is SchemaNode.Text)
        assertNull(problemOf(node, value("""["https://a", "https://b"]"""), words))
    }

    @Test
    fun anArrayOfObjectsNamesTheItemThatIsWrong() {
        // WORK_TIME, as the manifest has it.
        val node = schemaNodeOf(
            schema(
                """
                {"type": "array", "items": {"type": "object", "required": ["dayOfWeek", "start", "stop"], "properties": {
                  "dayOfWeek": {"type": "array", "items": {"type": "string", "enum": ["monday", "tuesday"]}, "minItems": 1},
                  "start": {"type": "string", "pattern": "^([01]?[0-9]|2[0-3]):[0-5][0-9]$"},
                  "stop": {"type": "string", "pattern": "^([01]?[0-9]|2[0-3]):[0-5][0-9]$"}}}}
                """.trimIndent(),
            ),
            "json",
        )
        val items = node as SchemaNode.Items
        assertTrue(items.item is SchemaNode.Fields)
        assertNull(problemOf(node, value("""[{"dayOfWeek": ["monday"], "start": "09:00", "stop": "18:00"}]"""), words))
        // The path of the problem is the path the server's own answer uses.
        assertTrue(
            problemOf(node, value("""[{"dayOfWeek": ["monday"], "start": "9 утра", "stop": "18:00"}]"""), words)!!.startsWith("0.start: "),
        )
        assertEquals(
            "0: Заполните «stop»",
            problemOf(node, value("""[{"dayOfWeek": ["monday"], "start": "09:00"}]"""), words),
        )
    }

    @Test
    fun anObjectByItsPropertiesKeepsWhatTheSchemaDoesNotDescribe() {
        val node = schemaNodeOf(
            schema("""{"type": "object", "additionalProperties": true, "properties": {"url": {"type": "string"}, "timeoutMs": {"type": "integer", "minimum": 1}}}"""),
            "json",
        )
        val fields = node as SchemaNode.Fields
        assertEquals(listOf("url", "timeoutMs"), fields.properties.map { it.name })
        assertTrue(fields.loose, "additionalProperties: true — the tail is the operator's too")
        assertEquals("timeoutMs: 1 или больше", problemOf(node, value("""{"url": "https://a", "timeoutMs": 0}"""), words))
        // A property the schema does not describe is not a problem for a loose object.
        assertNull(problemOf(node, value("""{"url": "https://a", "legacyMode": true}"""), words))
    }

    @Test
    fun aMapIsKeysOfTheOperatorAndValuesOfTheSchema() {
        val node = schemaNodeOf(
            schema("""{"type": "object", "additionalProperties": {"type": "object", "properties": {"enabled": {"type": "boolean"}, "cost": {"type": "number", "minimum": 0}}}}"""),
            "json",
        )
        assertTrue(node is SchemaNode.Keyed)
        assertNull(problemOf(node, value("""{"fcm-web": {"enabled": true, "cost": 0}}"""), words))
        assertEquals(
            "fcm-web.cost: 0 или больше",
            problemOf(node, value("""{"fcm-web": {"enabled": true, "cost": -1}}"""), words),
        )
    }

    @Test
    fun whatTheFormCannotDrawIsStillEditableAsText() {
        val node = schemaNodeOf(schema("""{"oneOf": [{"type": "string"}, {"type": "integer"}]}"""), "json")
        assertTrue(node is SchemaNode.Raw)
        // The field hands text that does not parse back as a string, and that is what «not JSON» is.
        assertEquals("Не разбирается как JSON", problemOf(node, JsonPrimitive("{ broken"), words))
        assertNull(problemOf(node, value("""{"a": 1}"""), words))
    }

    @Test
    fun theRawFieldReadsWhatItWrites() {
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), parseJsonOrString(prettyJson(value("""{"a": 1}"""))))
        assertEquals(JsonArray(emptyList()), parseJsonOrString("[]"))
        assertEquals(null, parseJsonOrString("   "))
        assertEquals(JsonPrimitive("{ broken"), parseJsonOrString("{ broken"))
    }

    @Test
    fun aNewItemOfAGroupCarriesOnlyWhatTheSchemaDemands() {
        val node = schemaNodeOf(
            schema("""{"type": "object", "required": ["start"], "properties": {"start": {"type": "string"}, "break": {"type": "string"}}}"""),
            "json",
        )
        assertEquals(JsonObject(mapOf("start" to JsonPrimitive(""))), blankOf(node))
    }
}
