package com.google.android.datatransport.cct;

import com.google.android.datatransport.runtime.Destination;

/** No-op stand-in for transport-backend-cct (see {@link com.google.android.datatransport.Encoding}). */
public final class CCTDestination implements Destination {
    public static final CCTDestination INSTANCE = new CCTDestination();

    private CCTDestination() {}
}
