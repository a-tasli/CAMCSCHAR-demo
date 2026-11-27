package org.example

import android.content.Context
import android.content.ContextWrapper
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManualCognitiveLoadTest {

    private class MockContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun testManualCognitiveLoadOverrideFlow() {
        val context = MockContext()
        val ttsManager = TTSManager(context)
        val tourManager = TourManager(context, ttsManager)

        // 1. Initial State
        assertFalse(tourManager.isManualLoadMode.value, "Manual mode should default to false")
        assertEquals("Low", tourManager.manualLoadLevel.value, "Default manual selection should be Low")
        assertEquals("Waiting...", tourManager.loadLevel.value, "Initial load level should be Waiting...")

        // 2. Automatic calculation updates
        tourManager.updateContext("Low")
        assertEquals("Low", tourManager.loadLevel.value)

        tourManager.updateContext("Medium")
        assertEquals("Medium", tourManager.loadLevel.value)

        // 3. Toggle ON manual cognitive load mode
        tourManager.setManualLoadMode(true)
        assertTrue(tourManager.isManualLoadMode.value, "Manual mode should now be true")
        assertEquals("Low", tourManager.loadLevel.value, "Manual level (Low) should now override automatic calculation")

        // 4. Select "High" manually
        tourManager.setManualLoadLevel("High")
        assertEquals("High", tourManager.manualLoadLevel.value)
        assertEquals("High", tourManager.loadLevel.value, "Load level should be overridden to High")
        assertEquals(0.75f, tourManager.audioParams.value.speed, "High load should apply 0.75x speed")
        assertEquals(2000L, tourManager.audioParams.value.pauseMs, "High load should apply 2000ms delay")
        assertFalse(tourManager.isDetailed.value, "High load should switch to simplified script")

        // 5. Automatic calculation arrives while manual mode is ON -> must be overridden
        tourManager.updateContext("Low")
        assertEquals("High", tourManager.loadLevel.value, "Manual override must prevent automatic Low from taking effect")

        // 6. Select "Overload" manually
        tourManager.setManualLoadLevel("Overload")
        assertEquals("Overload", tourManager.loadLevel.value)
        assertTrue(tourManager.audioParams.value.paused, "Overload should pause audio guide")

        // 7. Select "Medium" manually
        tourManager.setManualLoadLevel("Medium")
        assertEquals("Medium", tourManager.loadLevel.value)
        assertEquals(0.85f, tourManager.audioParams.value.speed)
        assertEquals(1500L, tourManager.audioParams.value.pauseMs)
        assertTrue(tourManager.isDetailed.value)

        // 8. Toggle OFF manual cognitive load mode
        tourManager.setManualLoadMode(false)
        assertFalse(tourManager.isManualLoadMode.value)
        assertEquals("Low", tourManager.loadLevel.value, "Reverting to auto should restore last automatic calculation")

        // 9. Subsequent automatic updates function normally
        tourManager.updateContext("High")
        assertEquals("High", tourManager.loadLevel.value)
    }

    @Test
    fun testOverloadToHighRecapDoesNotRepeatNextLinesTwice() {
        val context = MockContext()
        val ttsManager = TTSManager(context)
        val tourManager = TourManager(context, ttsManager)

        // Set pauseMs to 0 for instant execution without real timer delay in tests
        tourManager.auditoryModality.setPauseMs("Low", 0L)
        tourManager.auditoryModality.setPauseMs("High", 0L)

        // Setup test script with reflection:
        // index 0: normal
        // index 1: normal (interrupted by overload, recapped on resume)
        // index 2: normal (should NOT repeat twice on High load)
        // index 3: key fact (should NOT repeat by default when checkpoints are disabled)
        // index 4: normal
        val simplifiedField = TourManager::class.java.getDeclaredField("simplifiedScript").apply { isAccessible = true }
        val detailedField = TourManager::class.java.getDeclaredField("detailedScript").apply { isAccessible = true }
        val maxIndexField = TourManager::class.java.getDeclaredField("maxIndex").apply { isAccessible = true }

        val testScript = listOf(
            ScriptLine(1, "First line", isKeyFact = false),
            ScriptLine(2, "Second line", isKeyFact = false),
            ScriptLine(3, "Third line normal", isKeyFact = false),
            ScriptLine(4, "Fourth line key fact", isKeyFact = true),
            ScriptLine(5, "Fifth line normal", isKeyFact = false)
        )
        @Suppress("UNCHECKED_CAST")
        (simplifiedField.get(tourManager) as MutableList<ScriptLine>).apply {
            clear()
            addAll(testScript)
        }
        @Suppress("UNCHECKED_CAST")
        (detailedField.get(tourManager) as MutableList<ScriptLine>).apply {
            clear()
            addAll(testScript)
        }
        maxIndexField.set(tourManager, testScript.size)

        // Intercept TTS utterances
        val currentUtteranceIdField = TourManager::class.java.getDeclaredField("currentUtteranceId").apply { isAccessible = true }
        val onUtteranceCompletedField = TTSManager::class.java.getDeclaredField("_onUtteranceCompleted").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val onUtteranceCompletedFlow = onUtteranceCompletedField.get(ttsManager) as kotlinx.coroutines.flow.MutableSharedFlow<String>

        fun waitForUtterance(prevId: String? = null): String {
            for (i in 0 until 50) {
                val id = currentUtteranceIdField.get(tourManager) as? String
                if (id != null && id != prevId) return id
                Thread.sleep(10)
            }
            throw IllegalStateException("Timed out waiting for currentUtteranceId (prev=$prevId)")
        }

        // Start tour in Low load
        tourManager.updateContext("Low")
        tourManager.startTour()
        assertTrue(tourManager.isPlaying.value)
        assertEquals(0, tourManager.currentIndex)

        // Finish line 0: moves to line 1
        var uttId = waitForUtterance()
        onUtteranceCompletedFlow.tryEmit(uttId)
        Thread.sleep(20)
        assertEquals(1, tourManager.currentIndex)

        // Overload triggers while line 1 is playing
        tourManager.setManualLoadMode(true)
        tourManager.setManualLoadLevel("Overload")
        assertFalse(tourManager.isPlaying.value, "Overload must immediately pause playback")

        // Switch to High: triggers recovery recap
        tourManager.setManualLoadLevel("High")
        assertTrue(tourManager.isPlaying.value, "Recovery should resume playback")

        // Recap is playing
        val recapUttId = waitForUtterance(uttId)
        val lastHistoryItem = tourManager.history.value.last()
        assertTrue(lastHistoryItem.text.startsWith("(recap)"), "Recap should be added to history: ${lastHistoryItem.text}")

        // Complete the recap utterance: should advance to index 2 (not repeat line 1)
        onUtteranceCompletedFlow.tryEmit(recapUttId)
        Thread.sleep(20)
        assertEquals(2, tourManager.currentIndex, "After recap, should advance to line 2")

        // Line 2 (non-key-fact) completes: should NOT be repeated as checkpoint, should advance to index 3
        val line2UttId = waitForUtterance(recapUttId)
        onUtteranceCompletedFlow.tryEmit(line2UttId)
        Thread.sleep(20)
        assertEquals(3, tourManager.currentIndex, "Non-key-fact line must not repeat twice; should advance to index 3")

        // Line 3 (key-fact line) completes: checkpoints are disabled by default, so it advances directly to index 4
        val line3UttId = waitForUtterance(line2UttId)
        onUtteranceCompletedFlow.tryEmit(line3UttId)
        Thread.sleep(20)
        assertEquals(4, tourManager.currentIndex, "With checkpoints disabled by default, key fact line should advance directly to line 4")
    }

    @Test
    fun testLevelColorsAndCardLogic() {
        assertEquals(androidx.compose.ui.graphics.Color(0xFF4CAF50), getLevelColor("Low"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFFFC107), getLevelColor("Medium"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFFF44336), getLevelColor("High"))
        assertEquals(androidx.compose.ui.graphics.Color(0xFF9C27B0), getLevelColor("Overload"))
    }
}
