package com.nabobery.openrouter.oauth

import com.nabobery.openrouter.InlineTokenExchangeRequestGrantTypeX34984e08
import com.nabobery.openrouter.InlineTokenExchangeRequestSubjectTokenTypeX694c5468
import com.nabobery.openrouter.OpenRouter
import com.nabobery.openrouter.OpenRouterCredentials
import com.nabobery.openrouter.tokenExchangeRequest
import com.nabobery.sdkgen.runtime.SdkByteStream
import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequestBody
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.SdkResponseResult
import com.nabobery.sdkgen.testing.FakeByteStream
import com.nabobery.sdkgen.testing.FakeTransport
import com.nabobery.sdkgen.testing.assertClosedNormally
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Evidence tests for the OAuth operations landed by the `d49dda78` re-pin: `listOauthJwks` (GET `/oauth/jwks`)
 * and `createOauthToken` (POST `/oauth/token`, RFC 8693 token exchange). A fake transport enqueues the minimal
 * documented success body; the call goes through the curated root (`OpenRouter.oAuth`); the captured request's
 * exact method, path, and (for the token exchange) decoded form body are asserted; and the response stream is
 * proven closed.
 */
class OauthContractTest {
    private val credential = OpenRouterCredentials.static("sk-or-oauth")
    private val json = listOf(SdkHeader("Content-Type", "application/json"))

    private fun client(transport: FakeTransport) = OpenRouter(credential, transport)

    private fun body(s: String) = FakeByteStream(listOf(s.encodeToByteArray()))

    private suspend fun consume(body: SdkRequestBody?): ByteArray = when (body) {
        null -> ByteArray(0)
        is SdkRequestBody.Bytes -> body.bytes
        is SdkRequestBody.OneShot -> readAll(body.stream)
        is SdkRequestBody.ReplayFactory -> consume(body.create())
    }

    private suspend fun readAll(stream: SdkByteStream): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        while (true) chunks += stream.readChunk() ?: break
        stream.close()
        return chunks.fold(ByteArray(0)) { acc, c -> acc + c }
    }

    /** Decode an `application/x-www-form-urlencoded` body into decoded key/value pairs. */
    private fun decodeForm(wire: String): Map<String, String> = wire.split("&").associate { pair ->
        val eq = pair.indexOf('=')
        decodePercent(pair.substring(0, eq)) to decodePercent(pair.substring(eq + 1))
    }

    private fun decodePercent(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '+' -> {
                    out.append(' ')
                    i += 1
                }

                '%' -> {
                    out.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 3
                }

                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    @Test
    fun listOauthJwksIsAGetAndDecodesTheKeySet() = runTest {
        val payload = body("""{"keys":[]}""")
        val transport = FakeTransport().enqueueResponse(200, json, payload)

        val result = client(transport).oAuth.listOauthJwks()

        assertTrue(result.keys.isEmpty(), "empty JWK set should decode to no keys")
        val request = transport.capturedRequests.single()
        assertEquals("GET", request.method)
        assertTrue(request.uri.endsWith("/oauth/jwks"), request.uri)
        payload.assertClosedNormally()
    }

    @Test
    fun createOauthTokenEscapesReservedFormCharactersAndDecodesTheToken() = runTest {
        var contentType = ""
        var form: Map<String, String> = emptyMap()
        val reservedSubjectToken = "eyJ+subject token&scope=test"
        val responseBody = body(
            """{"access_token":"tok-123","expires_in":900,""" +
                """"issued_token_type":"urn:ietf:params:oauth:token-type:access_token",""" +
                """"scope":"inference","token_type":"Bearer"}""",
        )
        val transport = FakeTransport().enqueueExchange { req ->
            contentType = req.body?.contentType ?: ""
            form = decodeForm(consume(req.body).decodeToString())
            SdkResponse(200, json, responseBody)
        }

        val request = tokenExchangeRequest {
            federationPolicyId = "fed-1"
            grantType = InlineTokenExchangeRequestGrantTypeX34984e08.fromValue(
                "urn:ietf:params:oauth:grant-type:token-exchange",
            )
            subjectToken = reservedSubjectToken
            subjectTokenType = InlineTokenExchangeRequestSubjectTokenTypeX694c5468.fromValue(
                "urn:ietf:params:oauth:token-type:jwt",
            )
        }
        val result = client(transport).oAuth.createOauthToken(request)

        assertEquals("tok-123", result.accessToken)
        assertEquals(900, result.expiresIn)
        assertEquals("inference", result.scope)
        assertEquals("Bearer", result.tokenType.value)

        val captured = transport.capturedRequests.single()
        assertEquals("POST", captured.method)
        assertTrue(captured.uri.endsWith("/oauth/token"), captured.uri)
        // The spec declares the request body as application/x-www-form-urlencoded, not JSON.
        assertTrue(contentType.startsWith("application/x-www-form-urlencoded"), "content type was '$contentType'")
        // The reserved characters in the subject token prove that form values are encoded, not merely present.
        assertEquals("fed-1", form["federation_policy_id"])
        assertEquals("urn:ietf:params:oauth:grant-type:token-exchange", form["grant_type"])
        assertEquals(reservedSubjectToken, form["subject_token"])
        assertEquals("urn:ietf:params:oauth:token-type:jwt", form["subject_token_type"])
        responseBody.assertClosedNormally()
    }

    @Test
    fun createOauthTokenWithResponseExposesTheStatus() = runTest {
        val payload = body(
            """{"access_token":"tok-1","expires_in":60,""" +
                """"issued_token_type":"urn:ietf:params:oauth:token-type:access_token",""" +
                """"scope":"inference","token_type":"Bearer"}""",
        )
        val transport = FakeTransport().enqueueResponse(200, json, payload)

        val request = tokenExchangeRequest {
            federationPolicyId = "fed-1"
            grantType = InlineTokenExchangeRequestGrantTypeX34984e08.fromValue(
                "urn:ietf:params:oauth:grant-type:token-exchange",
            )
            subjectToken = "eyJ-subject-jwt"
            subjectTokenType = InlineTokenExchangeRequestSubjectTokenTypeX694c5468.fromValue(
                "urn:ietf:params:oauth:token-type:jwt",
            )
        }
        val result = client(transport).oAuth.createOauthTokenWithResponse(request)

        val matched = assertIs<SdkResponseResult.Matched<*>>(result)
        assertEquals(200, matched.statusCode)
        payload.assertClosedNormally()
    }
}
