package io.particle.controlrequestchannel;

import java.util.Collection;
import java.util.HashSet;

public abstract class Stream {
    private Collection<StreamEventListener> listeners = new HashSet<>();

    public void addListener(StreamEventListener listener) {
        listeners.add(listener);
    }

    public void close() {
        for (StreamEventListener listener:listeners){
            listener.onClose();
        }
    }

    public void data(byte[] data) {
        for (StreamEventListener listener:listeners){
            listener.onData(data);
        }
    }

    public void write(byte[] data) {
        throw new RuntimeException("Stub!");
    }
}
