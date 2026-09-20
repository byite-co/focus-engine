package com.google.android.datatransport.runtime;

import android.content.Context;

import com.google.android.datatransport.Encoding;
import com.google.android.datatransport.Event;
import com.google.android.datatransport.Transformer;
import com.google.android.datatransport.Transport;
import com.google.android.datatransport.TransportFactory;

/**
 * No-op stand-in for transport-runtime (see {@link Encoding}). {@link #initialize} does nothing; the
 * factory hands out a transport whose {@code send} drops the event. No storage, no scheduler, no network.
 */
public final class TransportRuntime {
    private static final TransportRuntime INSTANCE = new TransportRuntime();
    private static final Transport<Object> DROP = new Transport<Object>() {
        @Override
        public void send(Event<Object> event) {
            // dropped on purpose
        }
    };
    private static final TransportFactory FACTORY = new TransportFactory() {
        @Override
        @SuppressWarnings("unchecked")
        public <T> Transport<T> getTransport(String name, Class<T> payloadType, Encoding payloadEncoding, Transformer<T, byte[]> payloadTransformer) {
            return (Transport<T>) DROP;
        }
    };

    private TransportRuntime() {}

    public static void initialize(Context applicationContext) {
        // nothing to initialise
    }

    public static TransportRuntime getInstance() {
        return INSTANCE;
    }

    public TransportFactory newFactory(Destination destination) {
        return FACTORY;
    }
}
