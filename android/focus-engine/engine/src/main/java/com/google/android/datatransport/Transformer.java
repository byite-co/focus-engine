package com.google.android.datatransport;

/** No-op stand-in (see {@link Encoding}). Single abstract method so MediaPipe's lambda still links. */
public interface Transformer<T, U> {
    U apply(T input);
}
