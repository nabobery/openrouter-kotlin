plugins {
    // Version-pin KMP/serialization here so :sdk can apply them without versions.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.binary.compatibility.validator)
}
