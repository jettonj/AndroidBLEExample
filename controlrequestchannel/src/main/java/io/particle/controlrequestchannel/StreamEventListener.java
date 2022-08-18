package io.particle.controlrequestchannel;

import java.util.EventListener;

public abstract class StreamEventListener implements EventListener {
    void onData(byte[] data) {
        throw new RuntimeException("Stub!");
    }

    void onClose() {
        throw new RuntimeException("Stub!");
    }

    void onError() {
        throw new RuntimeException("Stub!");
    }
}
