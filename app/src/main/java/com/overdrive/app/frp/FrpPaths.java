// FrpPaths.java — file locations and constants of the self-hosted FRP tunnel (frpc)
package com.overdrive.app.frp;

import com.overdrive.app.util.ScratchRoot;

/**
 * File locations and constants shared by the FRP launcher, store and controller.
 *
 * <p>Permissions: HOME 0711, CONFIG 0600 (holds the frps auth token), SUMMARY 0644 (server and
 * domain only, never the token), RUN_SCRIPT 0755.
 *
 * <p>The binary is installed as {@link #PROCESS_NAME} and the restart loop lives in its own script
 * file, so the loop's command line ({@code sh …/.frp/run.sh}) never contains the process name: the
 * daemon health check matches process arguments by substring and would otherwise count the loop as
 * a running client.
 */
public final class FrpPaths {
    private FrpPaths() {}

    public static final String HOME = ScratchRoot.path(".frp");
    public static final String CONFIG = HOME + "/frpc.toml";
    public static final String SUMMARY = HOME + "/summary.json";
    public static final String LOG = HOME + "/frp.log";
    public static final String BINARY = HOME + "/odfrpc";
    public static final String RUN_SCRIPT = HOME + "/run.sh";
    public static final String VERSION_FILE = HOME + "/installed_version";
    /** OverDrive Cloud enrollment (car id + secret + gateway key), 0600; absent = not enrolled. */
    public static final String CLOUD = HOME + "/cloud.json";

    public static final String PROCESS_NAME = "odfrpc";
    public static final String NATIVE_LIB = "libfrpc.so";

    /** OverDrive's dashboard, the only service the tunnel publishes. */
    public static final int LOCAL_PORT = 8080;
}
