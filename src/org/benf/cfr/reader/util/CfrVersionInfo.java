package org.benf.cfr.reader.util;

/**
 * Provides information about the CFR build.
 *
 */
public class CfrVersionInfo {
    private CfrVersionInfo() {}

    /** CFR version */
    public static final String VERSION = CfrVersionInfo.class.getPackage().getImplementationVersion();


    /** String consisting of CFR version and Git commit hash */
    public static final String VERSION_INFO = VERSION + " (FabricMC/cfr)";
}
