package com.nabobery.openrouter.internal

import com.nabobery.sdkgen.runtime.SdkHeader
import com.nabobery.sdkgen.runtime.SdkRequest
import com.nabobery.sdkgen.runtime.SdkResponse
import com.nabobery.sdkgen.runtime.SdkTransport
import com.nabobery.sdkgen.runtime.TransportCapabilities

/**
 * Adds client-default headers (attribution, custom defaults) to every outgoing request,
 * but only when the request does not already carry the header — explicit per-call values
 * and generated typed parameters always win.
 */
internal class HeaderDefaultingTransport(
    private val delegate: SdkTransport,
    private val defaultHeaders: List<SdkHeader>,
) : SdkTransport {
    override suspend fun execute(request: SdkRequest): SdkResponse {
        val missing =
            defaultHeaders.filter { default ->
                request.headers.none { it.name.equals(default.name, ignoreCase = true) }
            }
        val effective = if (missing.isEmpty()) request else request.copy(headers = request.headers + missing)
        return delegate.execute(effective)
    }

    override fun capabilities(): TransportCapabilities = delegate.capabilities()
}
