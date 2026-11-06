package org.example

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModalityTest {

    @Test
    fun testModalityManagerRegistrationAndNotification() {
        val manager = ModalityManager()

        // Verify initial state
        assertTrue(manager.registeredModalities.isEmpty())

        // Create a custom test modality (demonstrating how easy it is for others to plug in new modules)
        var customModalityReceivedState: CognitiveLoadState? = null
        val customModality = object : PresentationModality {
            override val modalityId: String = "custom_visual"
            override var isEnabled: Boolean = true
            override fun onCognitiveLoadChanged(state: CognitiveLoadState) {
                customModalityReceivedState = state
            }
            override fun reset() {
                customModalityReceivedState = null
            }
        }

        manager.register(customModality)
        assertEquals(1, manager.registeredModalities.size)
        assertEquals(customModality, manager.get<PresentationModality>("custom_visual"))

        // Broadcast load change
        val state1 = CognitiveLoadState(
            loadLevel = "High",
            previousLevel = "Low",
            combinedLoadIndex = 110.0,
            isSpeaking = false,
            isAutoMode = true
        )
        manager.notifyCognitiveLoadChanged(state1)
        assertEquals("High", customModalityReceivedState?.loadLevel)
        assertEquals(110.0, customModalityReceivedState?.combinedLoadIndex)

        // When disabled, modality should not receive notifications
        customModality.isEnabled = false
        val state2 = CognitiveLoadState(loadLevel = "Low")
        manager.notifyCognitiveLoadChanged(state2)
        assertEquals("High", customModalityReceivedState?.loadLevel, "Disabled modality must not receive updates")

        // Reset
        manager.resetAll()
        assertNull(customModalityReceivedState)

        // Unregister
        manager.unregister("custom_visual")
        assertTrue(manager.registeredModalities.isEmpty())
    }

    @Test
    fun testAuditoryModalityDefaultAdaptation() {
        val auditory = AuditoryModality()

        // Low load state
        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "Low", isAutoMode = true, isAudioMode = true))
        assertEquals(1.0f, auditory.currentSpeed)
        assertEquals(300L, auditory.currentDelay)
        assertTrue(auditory.isDetailed)
        assertFalse(auditory.isCheckpointActive)
        assertFalse(auditory.isPaused)

        // Medium load state
        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "Medium", isAutoMode = true, isAudioMode = true))
        assertEquals(0.85f, auditory.currentSpeed)
        assertEquals(1500L, auditory.currentDelay)
        assertTrue(auditory.isDetailed)
        assertFalse(auditory.isCheckpointActive)
        assertFalse(auditory.isPaused)

        // High load state
        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "High", isAutoMode = true, isAudioMode = true))
        assertEquals(0.75f, auditory.currentSpeed)
        assertEquals(2000L, auditory.currentDelay)
        assertFalse(auditory.isDetailed, "High load should use simplified script")
        assertTrue(auditory.isCheckpointActive, "High load should enable checkpoints")
        assertFalse(auditory.isPaused)

        // Overload state
        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "Overload", previousLevel = "High", isAutoMode = true, isAudioMode = true))
        assertTrue(auditory.isPaused, "Overload should pause playback")
    }

    @Test
    fun testAuditoryModalityParameterModifiability() {
        val auditory = AuditoryModality()

        // Modify speed, pause, density, and checkpoint for High tier
        auditory.setSpeed("High", 0.65f)
        auditory.setPauseMs("High", 3000L)
        auditory.setDensity("High", true) // Override to detailed
        auditory.setCheckpoint("High", false) // Disable checkpoint

        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "High", isAutoMode = true, isAudioMode = true))

        assertEquals(0.65f, auditory.currentSpeed, "Modified speed should be applied")
        assertEquals(3000L, auditory.currentDelay, "Modified pause delay should be applied")
        assertTrue(auditory.isDetailed, "Modified density should be applied")
        assertFalse(auditory.isCheckpointActive, "Modified checkpoint flag should be applied")

        // Modify static control mode parameters
        auditory.setStaticSpeed(1.2f)
        auditory.setStaticPauseMs(500L)

        auditory.onCognitiveLoadChanged(CognitiveLoadState(loadLevel = "High", isAutoMode = false, isAudioMode = true))
        assertEquals(1.2f, auditory.currentSpeed, "Static speed should be modifiable")
        assertEquals(500L, auditory.currentDelay, "Static pause delay should be modifiable")

        // Modify earcon configuration
        auditory.setEarconPolicy(poiArrival = false, overload = true, endOfTour = false)
        auditory.setPoiArrivalLeadInMs(1500L)

        assertFalse(auditory.config.poiArrivalEarconEnabled)
        assertTrue(auditory.config.overloadEarconEnabled)
        assertFalse(auditory.config.endOfTourEarconEnabled)
        assertEquals(1500L, auditory.config.poiArrivalLeadInMs)
    }

    @Test
    fun testPluggingInMultipleModalitiesSimultaneously() {
        val manager = ModalityManager()
        val auditory = AuditoryModality()

        // User plugs in an Auditory modality AND a new Haptic modality
        var hapticVibrationTriggered = false
        val hapticModality = object : PresentationModality {
            override val modalityId: String = "haptic"
            override var isEnabled: Boolean = true
            override fun onCognitiveLoadChanged(state: CognitiveLoadState) {
                if (state.loadLevel == "Overload") {
                    hapticVibrationTriggered = true
                }
            }
            override fun reset() {
                hapticVibrationTriggered = false
            }
        }

        manager.register(auditory)
        manager.register(hapticModality)

        assertEquals(2, manager.registeredModalities.size)

        // Trigger Overload
        manager.notifyCognitiveLoadChanged(CognitiveLoadState(loadLevel = "Overload", isAutoMode = true))

        assertTrue(auditory.isPaused, "Auditory modality should pause on Overload")
        assertTrue(hapticVibrationTriggered, "Haptic modality should trigger vibration on Overload")
    }
}
