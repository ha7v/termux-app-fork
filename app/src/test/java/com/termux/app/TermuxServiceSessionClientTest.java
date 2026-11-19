package com.termux.app;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalSessionServiceClient;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class TermuxServiceSessionClientTest {

    @Test
    public void staleActivityDestroyDoesNotClobberNewerActivitySessionClient() {
        TermuxService service = new TermuxService();
        assertTrue(service.getTermuxTerminalSessionClient() instanceof TermuxTerminalSessionServiceClient);

        TermuxTerminalSessionActivityClient oldClient = new TermuxTerminalSessionActivityClient(null);
        TermuxTerminalSessionActivityClient newClient = new TermuxTerminalSessionActivityClient(null);

        // Old activity connects first, then finishes; new activity connects before old onDestroy().
        service.setTermuxTerminalSessionClient(oldClient);
        assertSame(oldClient, service.getTermuxTerminalSessionClient());

        service.setTermuxTerminalSessionClient(newClient);
        assertSame(newClient, service.getTermuxTerminalSessionClient());

        // Deferred onDestroy() of old activity must NOT overwrite newClient with ServiceClient.
        service.unsetTermuxTerminalSessionClient(oldClient);
        assertSame(newClient, service.getTermuxTerminalSessionClient());

        // Destroying the current owning activity unsets its client back to ServiceClient.
        service.unsetTermuxTerminalSessionClient(newClient);
        assertNotSame(newClient, service.getTermuxTerminalSessionClient());
        assertTrue(service.getTermuxTerminalSessionClient() instanceof TermuxTerminalSessionServiceClient);
    }

    @Test
    public void unconditionalUnbindResetsSessionClientToServiceClient() {
        TermuxService service = new TermuxService();
        TermuxTerminalSessionActivityClient activeClient = new TermuxTerminalSessionActivityClient(null);

        service.setTermuxTerminalSessionClient(activeClient);
        assertSame(activeClient, service.getTermuxTerminalSessionClient());

        service.unsetTermuxTerminalSessionClient();
        assertTrue(service.getTermuxTerminalSessionClient() instanceof TermuxTerminalSessionServiceClient);
    }
}
