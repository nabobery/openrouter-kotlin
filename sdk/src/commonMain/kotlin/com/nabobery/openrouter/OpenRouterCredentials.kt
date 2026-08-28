package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException
import com.nabobery.sdkgen.runtime.auth.Credential
import com.nabobery.sdkgen.runtime.auth.CredentialProvider
import com.nabobery.sdkgen.runtime.auth.Secret

/** Factories producing OpenRouter API-key credentials for the runtime's [CredentialProvider] seam. */
public object OpenRouterCredentials {
    /** Fixed API key. The key is validated eagerly; the [Secret] wrapper redacts it everywhere. */
    public fun static(apiKey: String): CredentialProvider {
        val secret = Secret(requireApiKey(apiKey))
        val credential = Credential.BearerCredential(secret)
        return CredentialProvider { credential }
    }

    /** Resolved before every physical attempt — safe for rotating keys. */
    public fun dynamic(resolve: suspend () -> String): CredentialProvider =
        CredentialProvider { Credential.BearerCredential(Secret(requireApiKey(resolve()))) }

    private fun requireApiKey(value: String): String {
        if (value.isBlank()) throw SdkConfigurationException("OpenRouter API key must not be blank.")
        return value
    }
}
