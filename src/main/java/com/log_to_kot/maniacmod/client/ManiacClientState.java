package com.log_to_kot.maniacmod.client;

import com.log_to_kot.maniacmod.entity.ManiacType;

/**
 * Client-side singleton: holds the local player's ManiacType
 * so Mixins and the FOV handler can read it without going through
 * game manager (which is server-only).
 *
 * Updated via ManiacTypePacket whenever the server assigns/changes the type.
 */
public final class ManiacClientState {

    private static boolean localIsManiac = false;
    private static ManiacType localType  = null;

    private ManiacClientState() {}

    // Called by ManiacTypePacket on the client thread
    public static void setLocalManiac(ManiacType type) {
        localIsManiac = (type != null);
        localType     = type;
    }

    public static void clearLocalManiac() {
        localIsManiac = false;
        localType     = null;
    }

    public static boolean isLocalManiac()    { return localIsManiac; }
    public static ManiacType getLocalType()  { return localType; }
}
