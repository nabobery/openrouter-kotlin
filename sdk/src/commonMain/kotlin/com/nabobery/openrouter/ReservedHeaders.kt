package com.nabobery.openrouter

import com.nabobery.sdkgen.runtime.SdkConfigurationException

// Names the SDK or the runtime owns; generic header injection must not override them
// (docs/security-and-privacy.md "Header safety").
private val RESERVED_HEADER_NAMES: Set<String> =
    setOf("authorization", "host", "content-length", "content-type", "accept", "user-agent")

internal fun requireNotReserved(name: String) {
    if (name.lowercase() in RESERVED_HEADER_NAMES) {
        throw SdkConfigurationException(
            "Header '$name' is reserved and cannot be set through generic header configuration.",
        )
    }
}
