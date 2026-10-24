package app.sona.music

import org.junit.Assert.*
import org.junit.Test

class OutputSelectionTest {
    @Test fun openingBeforeAudioStartsSelectsTheDefaultWithoutConfirmingIt() {
        val initial = OutputSelection()
        assertEquals(1, initial.displayId(1))
        assertNull(initial.actualId)
        assertNull(initial.targetId)
        assertFalse(initial.canAdjustVolume(1, 1))
    }

    @Test fun systemDefaultCanBeBluetoothAndUnknownDefaultStaysUnselected() {
        assertEquals(8, OutputSelection().displayId(8))
        assertNull(OutputSelection().displayId(null))
    }

    @Test fun observedPlayerRouteTakesPriorityOverTheSystemDefault() {
        val observed = OutputSelection().observe(8)
        assertEquals(8, observed.displayId(1))
        assertFalse(observed.canAdjustVolume(8, 1))
        assertEquals(8, observed.displayId(null))
    }

    @Test fun defaultDoesNotCompleteAPendingSwitchEvenWhenItMatchesTheTarget() {
        val requested = OutputSelection(actualId = 8).request(1)
        assertEquals(1, requested.displayId(1))
        assertEquals(8, requested.actualId)
        assertEquals(1, requested.targetId)
        assertFalse(requested.canAdjustVolume(1, 1))
        val confirmed = requested.observe(1)
        assertEquals(1, confirmed.displayId(8))
        assertNull(confirmed.targetId)
        assertTrue(confirmed.canAdjustVolume(1, 1))
    }

    @Test fun disconnectedObservedDeviceFallsBackToTheRemainingDefault() {
        val retained = OutputSelection(actualId = 8).retain(setOf(1))
        assertEquals(1, retained.displayId(1))
        assertNull(retained.actualId)
        assertFalse(retained.canAdjustVolume(1, 1))
    }

    @Test fun selectingTheLastOutputWhilePausedStillRequiresNewConfirmation() {
        val requested = OutputSelection(actualId = 8).request(8)
        assertEquals(8, requested.targetId)
        assertFalse(requested.canAdjustVolume(8, 8))
        assertEquals(OutputSelection(actualId = 8), requested.observe(8))
    }

    @Test fun requestingPhoneDoesNotClaimThatBluetoothStoppedPlaying() {
        val requested = OutputSelection(actualId = 8).request(1)
        assertEquals(8, requested.actualId)
        assertEquals(1, requested.targetId)
        assertEquals(requested, requested.observe(8))
        assertFalse(requested.canAdjustVolume(1, 8))
        assertFalse(requested.canAdjustVolume(8, 8))
        assertEquals(OutputSelection(actualId = 1), requested.observe(1))
    }

    @Test fun actualAudioAndSystemVolumeMustReferToTheSameDevice() {
        val phone = OutputSelection(actualId = 1)
        assertFalse(phone.canAdjustVolume(1, 8))
        assertFalse(phone.canAdjustVolume(1, null))
        assertTrue(phone.canAdjustVolume(1, 1))
        assertFalse(phone.canAdjustVolume(8, 8))
    }

    @Test fun failureAndDisconnectDoNotPromoteTheRequestedDevice() {
        val requested = OutputSelection(actualId = 8).request(1)
        assertEquals(OutputSelection(actualId = 8), requested.cancel())
        assertEquals(OutputSelection(actualId = 8), requested.retain(setOf(8)))
        assertEquals(OutputSelection(targetId = 1), requested.retain(setOf(1)))
        assertEquals(OutputSelection(), requested.retain(emptySet()))
    }
}
