package cx.m42.restoapp.protocol

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.fail

/**
 * `schema/declaration.schema.json` of the app-mobile-api hook — copied into the test resources, as
 * the plan has it (section 5): the server checks its registrations against it, the client checks
 * what it reads and what its demo server says.
 *
 * [Pos] is the same for `schema/pos.schema.json`: the answers of the POS, which the client draws
 * itself and whose shape therefore has a file of its own (`order-entry.plan.md`).
 */
object Contract : SchemaFile("/declaration.schema.json") {
    object Pos : SchemaFile("/pos.schema.json")
}

abstract class SchemaFile(resource: String) {
    private val mapper = ObjectMapper()
    private val root: ObjectNode = mapper.readTree(javaClass.getResource(resource)!!.readText()) as ObjectNode
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val schemas = mutableMapOf<String, JsonSchema>()

    /** Everything a server would send, defaults included: what the contract sees on the wire. */
    val wire: Json = Json(ProtocolJson) { encodeDefaults = true }

    /** The schema of one definition: the whole file with a root `$ref`, and no `$id` to fetch. */
    private fun schema(definition: String): JsonSchema = schemas.getOrPut(definition) {
        val node = root.deepCopy()
        node.remove("\$id")
        node.put("\$ref", "#/definitions/$definition")
        factory.getSchema(node)
    }

    fun errors(definition: String, json: String): List<String> =
        schema(definition).validate(mapper.readTree(json)).map { it.message }

    fun assertValid(definition: String, element: JsonElement) {
        val text = wire.encodeToString(JsonElement.serializer(), element)
        val errors = errors(definition, text)
        if (errors.isNotEmpty()) fail("Not a valid $definition:\n" + errors.joinToString("\n") + "\n" + text.take(2000))
    }

    fun assertInvalid(definition: String, json: String) {
        if (errors(definition, json).isEmpty()) fail("Expected $definition to refuse: $json")
    }
}
