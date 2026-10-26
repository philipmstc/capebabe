package net.shchoo.scafe.scafe.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressTest {
    @Test
    void justTheAddressOrWithItsPort() {
        assertEquals("100.79.84.1:43594", SocketConnection.normalize("100.79.84.1"));
        assertEquals("100.79.84.1:43594", SocketConnection.normalize("100.79.84.1:43594"));
        assertEquals("100.79.84.1:5000", SocketConnection.normalize("100.79.84.1:5000"));
    }

    @Test
    void spacesPastedInDontMatter() {
        assertEquals("100.79.84.1:43594", SocketConnection.normalize(" 100.79.84.1 : 43594 "));
        assertEquals("100.79.84.1:43594", SocketConnection.normalize("100.79.84.1\t"));
    }
}
