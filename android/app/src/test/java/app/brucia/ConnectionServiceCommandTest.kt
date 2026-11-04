package app.brucia

import app.brucia.service.ConnectionServiceCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionServiceCommandTest {

    @Test fun nullIntentRestartNeverShowsConnected() {
        // START_STICKY-style restart after process death delivers a null intent: no link owner exists.
        assertEquals(ConnectionServiceCommand.Stop, ConnectionServiceCommand.decide(false, null, null, null))
    }

    @Test fun missingStatusDoesNotDefaultToConnected() {
        assertEquals(ConnectionServiceCommand.Stop, ConnectionServiceCommand.decide(true, null, null, "wifi"))
        assertEquals(ConnectionServiceCommand.Stop, ConnectionServiceCommand.decide(true, null, " ", null))
    }

    @Test fun explicitStopStops() {
        assertEquals(
            ConnectionServiceCommand.Stop,
            ConnectionServiceCommand.decide(true, ConnectionServiceCommand.ACTION_STOP, "Connected", ""),
        )
    }

    @Test fun ownerStatusIsShownVerbatim() {
        assertEquals(
            ConnectionServiceCommand.Show("Connecting...", "", ConnectionServiceCommand.OWNER_COMPANION),
            ConnectionServiceCommand.decide(true, null, "Connecting...", null),
        )
        assertEquals(
            ConnectionServiceCommand.Show("Connected", "wifi, ble", ConnectionServiceCommand.OWNER_COMPANION),
            ConnectionServiceCommand.decide(true, null, "Connected", "wifi, ble"),
        )
    }

    private val companion = ConnectionServiceCommand.OWNER_COMPANION
    private fun flash(token: Long) = ConnectionServiceCommand.flashOwner(token)
    private fun show(status: String?, owner: String?, current: String?) =
        ConnectionServiceCommand.decide(true, null, status, "", owner, current)
    private fun stop(owner: String?, current: String?) =
        ConnectionServiceCommand.decide(true, ConnectionServiceCommand.ACTION_STOP, null, null, owner, current)

    @Test fun flashTakesOverTheLinkNotificationWithoutAStopGap() {
        assertEquals(ConnectionServiceCommand.Show("Updating firmware", "", flash(3)), show("Updating firmware", flash(3), companion))
        assertEquals(ConnectionServiceCommand.Show("Updating firmware", "", flash(3)), show("Updating firmware", flash(3), null))
    }

    @Test fun companionCannotStopOrReplaceAFlash() {
        // The flash path drops companion mode: that disconnect must not release the flash's wake lock.
        assertEquals(ConnectionServiceCommand.Ignore, stop(companion, flash(3)))
        assertEquals(ConnectionServiceCommand.Ignore, show("Connected", companion, flash(3)))
        // Legacy callers that name no owner are the companion link.
        assertEquals(ConnectionServiceCommand.Ignore, show("Connected", null, flash(3)))
        // A malformed show never tears down a live owner.
        assertEquals(ConnectionServiceCommand.Ignore, show(null, flash(3), flash(3)))
    }

    @Test fun flashStopIsCorrelatedByToken() {
        assertEquals(ConnectionServiceCommand.Stop, stop(flash(3), flash(3)))
        // A late stop from an older flash, or from a flash after the link took over, is ignored.
        assertEquals(ConnectionServiceCommand.Ignore, stop(flash(2), flash(3)))
        assertEquals(ConnectionServiceCommand.Ignore, stop(flash(3), companion))
        // Nothing shown: a stop always ends the service.
        assertEquals(ConnectionServiceCommand.Stop, stop(flash(3), null))
        assertEquals(ConnectionServiceCommand.Stop, stop(null, flash(3)))
    }

    @Test fun onlyANewerFlashReplacesAShownFlash() {
        assertEquals(ConnectionServiceCommand.Show("Updating firmware", "", flash(4)), show("Updating firmware", flash(4), flash(3)))
        assertEquals(ConnectionServiceCommand.Ignore, show("Updating firmware", flash(2), flash(3)))
        assertEquals(ConnectionServiceCommand.Show("x", "", flash(3)), show("x", flash(3), flash(3)))
    }

    @Test fun companionUpdatesReplaceCompanionAndAfterAFlashEnded() {
        assertEquals(ConnectionServiceCommand.Show("Connected", "", companion), show("Connected", companion, companion))
        assertEquals(ConnectionServiceCommand.Show("Connected", "", companion), show("Connected", companion, null))
        assertEquals(ConnectionServiceCommand.Stop, stop(companion, companion))
    }

    @Test fun flashOwnerRoundTrips() {
        assertEquals(42L, ConnectionServiceCommand.flashToken(flash(42)))
        assertEquals(null, ConnectionServiceCommand.flashToken(companion))
        assertEquals(null, ConnectionServiceCommand.flashToken("flash:x"))
        assertEquals(null, ConnectionServiceCommand.flashToken(null))
    }

    @Test fun nullIntentStopsEvenWhileAFlashIsShown() {
        // A system restart has no live owner of any kind.
        assertEquals(ConnectionServiceCommand.Stop, ConnectionServiceCommand.decide(false, null, null, null, null, flash(3)))
    }
}
