package app.myspoti.extensions

import app.myspoti.api.MyspotiApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.lang.reflect.Modifier

/**
 * Runs the `myspoti.spotify` calls of extensions over a [MyspotiApi] (and [raw], when the host
 * gives the raw door). A host implements [Host.spotify] with it:
 *
 *     override suspend fun spotify(extensionId: String, call: SpotifyCall) = dispatcher.execute(call)
 *
 * It checks nothing about rights: [ExtensionRuntime] has done that, from [SpotifyMethods].
 */
class SpotifyDispatcher(api: MyspotiApi, private val raw: SpotifyRaw? = null, media: PlayerMedia? = null) {
    private val backend = SpotifyBackend(api, raw, media)

    /** The call's result as JSON text (`null` for a method that returns nothing). */
    suspend fun execute(call: SpotifyCall): String {
        val method = SpotifyMethods.find(call.area, call.method) ?: throw IllegalArgumentException("spotify.${call.area}.${call.method}: unknown method")
        val args = ExtJson.parseToJsonElement(call.argsJson) as? JsonArray ?: throw IllegalArgumentException("spotify.${call.area}.${call.method}: arguments must be a list")
        return JsonModel.encode(method.run(backend, SpotifyArgs(args, "spotify.${method.key}"))).toString()
    }

    /** For [Host.spotifyMutation]: whether the raw GraphQL operation changes something (true when there is no raw door). */
    fun isMutation(operation: String): Boolean = raw?.isMutation(operation) ?: true
}

/**
 * The API's models are plain Kotlin classes, not `@Serializable`: this turns a result into JSON
 * by reading their fields (the field names are the property names). Enums are their names,
 * [Unit] and null are null, byte arrays are base64.
 */
internal object JsonModel {
    private const val MAX_DEPTH = 12

    fun encode(value: Any?, depth: Int = 0): JsonElement {
        if (depth > MAX_DEPTH) return JsonNull
        return when (value) {
            null, Unit -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name)
            is ByteArray -> JsonPrimitive(java.util.Base64.getEncoder().encodeToString(value))
            is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), encode(v, depth + 1)) } }
            is Iterable<*> -> buildJsonArray { value.forEach { add(encode(it, depth + 1)) } }
            is Array<*> -> buildJsonArray { value.forEach { add(encode(it, depth + 1)) } }
            else -> buildJsonObject {
                fields(value.javaClass).forEach { f ->
                    f.isAccessible = true
                    put(f.name, encode(f.get(value), depth + 1))
                }
            }
        }
    }

    private fun fields(c: Class<*>) = generateSequence(c) { it.superclass.takeIf { s -> s != Any::class.java } }
        .flatMap { it.declaredFields.asSequence() }
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.name.startsWith("$") }
        .toList()
}
