package com.nabobery.openrouter.stt

import com.nabobery.openrouter.InlineAudioTranscriptionsPostRequestMultipartXc57fc157
import com.nabobery.openrouter.OpenRouterExperimentalApi
import com.nabobery.openrouter.SttResponse
import com.nabobery.openrouter.io.byteStreamOf
import com.nabobery.sdkgen.runtime.CallOptions
import com.nabobery.sdkgen.runtime.SdkByteStream

// Curated speech-to-text overloads on the generated `SttClient`. Unlike the files multipart type, the audio
// transcription multipart model carries `model`/`language`/`responseFormat`/`temperature`/`timestampGranularities`
// slots, so [configure] can set them. The part name and content type are still fixed by the generated codec.

/** Transcribes [audio] with [model]; optional extra multipart fields via [configure]. The stream is consumed once. */
@OpenRouterExperimentalApi
public suspend fun SttClient.transcribe(
    audio: SdkByteStream,
    model: String,
    options: CallOptions = CallOptions(),
    configure: (InlineAudioTranscriptionsPostRequestMultipartXc57fc157.Builder.() -> Unit)? = null,
): SttResponse = createAudioTranscriptionsMultipart(
    InlineAudioTranscriptionsPostRequestMultipartXc57fc157.build {
        this.file = audio
        this.model = model
        configure?.invoke(this)
    },
    options = options,
)

/** Transcribes in-memory [audio] bytes with [model]; optional extra multipart fields via [configure]. */
@OpenRouterExperimentalApi
public suspend fun SttClient.transcribe(
    audio: ByteArray,
    model: String,
    options: CallOptions = CallOptions(),
    configure: (InlineAudioTranscriptionsPostRequestMultipartXc57fc157.Builder.() -> Unit)? = null,
): SttResponse = transcribe(byteStreamOf(audio), model, options, configure)
