package io.particle.controlrequestchannel;

import java.util.EventListener;

public interface StreamEventListener extends EventListener {
    void onData(byte[] data);
    void onClose();
    void onError();
}
