package consumers

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SmokeTest {
    @Test
    fun resolvesAndExercisesBothArtifacts() = runTest {
        assertTrue(smoke().startsWith("openrouter-kotlin OK"))
    }
}
