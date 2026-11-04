package cx.m42.restoapp.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Keeping and checking the code of a script app (script-apps.plan.md, D12), without a server: the
 * hash is what the client trusts, the cache is what makes the second open free, and the bundle that
 * worked before is what it falls back to when a download fails.
 */
class ScriptLoaderTest {

    private val bundle = "app({ id: 'navigation', routes: {} });\n"
    private val hash = sha256Hex(bundle.encodeToByteArray())
    private val ref = ScriptRef("/api/mobile/app-assets/navigation/$hash.js", hash, bundle.encodeToByteArray().size)

    /** A server that hands out whatever it was told to, and counts how often it was asked. */
    private class FakeApi(private var body: String?, private val checked: Boolean = true) : MobileApi {
        var asked = 0

        fun serve(body: String?) {
            this.body = body
        }

        override suspend fun script(token: String, ref: ScriptRef): String {
            asked++
            val answer = body ?: throw MobileApiException(MobileApiException.NETWORK, "No connection")
            // The real client checks the hash in `HttpMobileApi`; a fake that must act like it does so too.
            if (checked && sha256Hex(answer.encodeToByteArray()) != ref.sha256) {
                throw MobileApiException(MobileApiException.PROTOCOL, "Not the bundle the declaration named")
            }
            return answer
        }

        override suspend fun about(): ServerAbout = ServerAbout("Fake")
        override suspend fun challenge(): ByteArray? = null
        override suspend fun login(login: String, password: String, captchaSolution: String?, device: DeviceInfo): LoginResult =
            throw UnsupportedOperationException()
        override suspend fun logout(token: String) = Unit
        override suspend fun describe(token: String, lang: String): Answer<Declaration> = throw UnsupportedOperationException()
        override suspend fun call(token: String, method: String, params: JsonObject, place: String?, lang: String): Answer<JsonElement> =
            throw UnsupportedOperationException()
    }

    @Test
    fun theHashIsTheOneTheWholeWorldAgreesOn() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(ByteArray(0)))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".encodeToByteArray()))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()),
        )
        // Three bytes of UTF-8 per Cyrillic letter: the hash is of the bytes, not of the characters.
        assertEquals(sha256Hex("Навигация".encodeToByteArray()), sha256Hex("Навигация".encodeToByteArray()))
    }

    @Test
    fun downloadsOnceAndThenTakesItFromTheCache(): Unit = runBlocking {
        val api = FakeApi(bundle)
        val loader = ScriptLoader(api, MemoryScriptCache())

        val first = loader.load("navigation", "t", ref)
        assertEquals(hash, first.sha256)
        assertEquals(bundle, first.body)
        assertFalse(first.fromCache)
        assertFalse(first.fallback)

        val again = loader.load("navigation", "t", ref)
        assertTrue(again.fromCache)
        assertEquals(1, api.asked)
        assertEquals(bundle, loader.kept("navigation")?.body)
    }

    @Test
    fun aNewBuildIsDownloadedAndTheOldOneIsStillThere(): Unit = runBlocking {
        val cache = MemoryScriptCache()
        val api = FakeApi(bundle)
        val loader = ScriptLoader(api, cache)
        loader.load("navigation", "t", ref)

        val next = "$bundle// one more line\n"
        val nextHash = sha256Hex(next.encodeToByteArray())
        api.serve(next)
        val built = loader.load("navigation", "t", ScriptRef("/api/mobile/app-assets/navigation/$nextHash.js", nextHash, next.encodeToByteArray().size))

        assertEquals(nextHash, built.sha256)
        assertFalse(built.fromCache)
        assertEquals(2, api.asked)
        // Nothing was invalidated: the old bundle is still under its own hash.
        assertEquals(bundle, cache.read(hash))
    }

    @Test
    fun whenTheDownloadFailsTheBundleThatWorkedBeforeIsUsed(): Unit = runBlocking {
        val api = FakeApi(bundle)
        val loader = ScriptLoader(api, MemoryScriptCache())
        loader.load("navigation", "t", ref)

        val next = "$bundle// broken\n"
        val nextHash = sha256Hex(next.encodeToByteArray())
        api.serve(null)
        val fallen = loader.load("navigation", "t", ScriptRef("/api/mobile/app-assets/navigation/$nextHash.js", nextHash, next.length))

        assertTrue(fallen.fallback)
        assertEquals(hash, fallen.sha256)
        assertEquals(bundle, fallen.body)
    }

    @Test
    fun withNothingKeptAFailedDownloadIsAFailure(): Unit = runBlocking {
        val loader = ScriptLoader(FakeApi(null), MemoryScriptCache())
        val failure = assertFailsWith<MobileApiException> { loader.load("navigation", "t", ref) }
        assertEquals(MobileApiException.NETWORK, failure.code)
        assertNull(loader.kept("navigation"))
    }

    @Test
    fun aBodyThatIsNotTheDeclaredBundleIsRefused(): Unit = runBlocking {
        val api = FakeApi("app({ id: 'something-else' });\n")
        val failure = assertFailsWith<MobileApiException> { ScriptLoader(api, MemoryScriptCache()).load("navigation", "t", ref) }
        assertEquals(MobileApiException.PROTOCOL, failure.code)
    }

    @Test
    fun aScriptThatNeedsANewerClientIsNotEvenDownloaded(): Unit = runBlocking {
        val api = FakeApi(bundle)
        val failure = assertFailsWith<MobileApiException> {
            ScriptLoader(api, MemoryScriptCache()).load("navigation", "t", ref.copy(min = PROTOCOL_VERSION + 1))
        }
        assertEquals(MobileApiException.UPDATE_REQUIRED, failure.code)
        assertEquals(0, api.asked)
    }
}
