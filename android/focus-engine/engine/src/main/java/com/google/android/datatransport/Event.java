package com.google.android.datatransport;

/** No-op stand-in (see {@link Encoding}). The payload is dropped. */
public final class Event<T> {
    private static final Event<Object> DROPPED = new Event<>();

    private Event() {}

    @SuppressWarnings("unchecked")
    public static <T> Event<T> ofData(T payload) {
        return (Event<T>) DROPPED;
    }
}
