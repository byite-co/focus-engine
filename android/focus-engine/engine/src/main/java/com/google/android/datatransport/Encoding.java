package com.google.android.datatransport;

/**
 * No-op stand-in for com.google.android.datatransport:transport-api. MediaPipe tasks-core's
 * RemoteLoggingClient (Google usage telemetry) links against these types; the real library declares the
 * INTERNET permission and is excluded from the build. Nothing here stores or sends anything.
 */
public final class Encoding {
    private static final Encoding NONE = new Encoding();

    private Encoding() {}

    public static Encoding of(String name) {
        return NONE;
    }
}
