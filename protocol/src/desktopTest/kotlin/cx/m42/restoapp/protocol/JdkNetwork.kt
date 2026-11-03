package cx.m42.restoapp.protocol

import cx.m42.superizer.runtime.NetworkException
import cx.m42.superizer.runtime.NetworkRequest
import cx.m42.superizer.runtime.NetworkResponse
import cx.m42.superizer.runtime.NetworkService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Superizer's `NetworkService` contract on the JDK's client, for the live test: strings in and out,
 * no status is an exception, a transport failure is `NetworkException(null, …)` — as the host's Ktor
 * one behaves, without pulling the host into a protocol test.
 */
class JdkNetwork : NetworkService {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override val online: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun request(request: NetworkRequest): NetworkResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(Duration.ofSeconds(15))
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        request.contentType?.let { builder.header("Content-Type", it) }
        builder.method(
            request.method,
            request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody(),
        )
        val response = try {
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (cause: Exception) {
            throw NetworkException(null, "request failed: ${request.method} ${request.url}", cause)
        }
        NetworkResponse(response.statusCode(), response.body(), response.headers().map())
    }
}
