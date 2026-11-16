package com.watchyojet.engine;

/**
 * How one engine cycle treats the tracks it is given.
 */
public enum CycleMode {

    /**
     * Observed traffic. Maneuvers are proposed and tested on trial copies only.
     * The tracks are never changed, so the next OpenSky snapshot cannot undo anything.
     */
    ADVISE,

    /**
     * Scripted traffic. Accepted maneuvers are applied to the simulated tracks.
     */
    SIMULATE,

    /**
     * Scripted traffic before maneuvers start. Conflicts are detected and shown only.
     */
    DETECT_ONLY
}
