package com.nabobery.openrouter

/**
 * The published SDK version, used to form the `User-Agent` product token
 * (`openrouter-kotlin/<SDK_VERSION>`) carried on every generated call through `SdkClientConfig`.
 *
 * This constant is kept in lockstep with the Gradle `project.version` by the `checkSdkVersionConstant`
 * verification task (wired into `verificationCheck`); the build fails if the two drift apart.
 */
internal const val SDK_VERSION: String = "0.1.0-rc.1"
