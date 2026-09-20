package com.google.android.datatransport;

/** No-op stand-in (see {@link Encoding}). */
public interface TransportFactory {
    <T> Transport<T> getTransport(String name, Class<T> payloadType, Encoding payloadEncoding, Transformer<T, byte[]> payloadTransformer);
}
