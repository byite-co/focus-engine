package com.google.android.datatransport;

/** No-op stand-in (see {@link Encoding}). */
public interface Transport<T> {
    void send(Event<T> event);
}
