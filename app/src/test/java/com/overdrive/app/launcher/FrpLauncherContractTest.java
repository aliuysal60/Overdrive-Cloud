// FrpLauncherContractTest.java — shell contracts of the FRP tunnel loop and its stop/kill sweeps
package com.overdrive.app.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.frp.FrpPaths;
import com.overdrive.app.ui.model.DaemonType;
import com.overdrive.app.ui.model.ParkedShutdown;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class FrpLauncherContractTest {

    @Test
    public void runLoopStopsOnTheUserSentinelAndTheParkedMarker() {
        String script = FrpLauncher.buildRunScript();
        assertTrue(script.contains("STOP='" + DaemonType.FRP_TUNNEL.getSentinelPath() + "'"));
        assertTrue(script.contains("PARKED='" + ParkedShutdown.MARKER_PATH + "'"));
        assertTrue(script.contains(FrpPaths.BINARY + " -c " + FrpPaths.CONFIG));
        assertTrue(script.contains("sleep 5"));
        assertTrue(script.contains("umask 022\n"));
    }

    @Test
    public void loopCommandLineCannotPassForTheClient() {
        // the health check matches process arguments by substring
        assertFalse(FrpPaths.RUN_SCRIPT.contains(FrpPaths.PROCESS_NAME));
        assertTrue(FrpPaths.BINARY.endsWith("/" + FrpPaths.PROCESS_NAME));
        assertTrue(DaemonType.FRP_TUNNEL.getProcessName().equals(FrpPaths.PROCESS_NAME));
    }

    @Test
    public void stopKillsTheLoopBeforeTheClient() {
        String stop = FrpLauncher.buildStopCommand();
        int loop = stop.indexOf(FrpPaths.RUN_SCRIPT);
        int client = stop.indexOf("pidof " + FrpPaths.PROCESS_NAME);
        assertTrue(loop >= 0 && client > loop);
        assertTrue(stop.contains("kill -9"));
    }

    @Test
    public void sweepsKillTheLoopAndTheClient() throws IOException {
        for (String file : new String[] {
                "app/src/main/java/com/overdrive/app/daemon/AccSentryDaemon.java",
                "app/src/main/java/com/overdrive/app/updater/AppUpdater.java",
                "app/src/main/java/com/overdrive/app/updater/UpdateLifecycle.java",
                "app/src/main/java/com/overdrive/app/ui/dialog/ScratchRootDialog.kt",
        }) {
            String text = read(file);
            int loop = text.indexOf("\".frp/run.sh\"");
            int client = text.indexOf("\"odfrpc\"");
            assertTrue(file, loop >= 0 && client > loop);
        }
    }

    @Test
    public void bundledClientIsPackagedByteForByte() throws IOException {
        assertTrue(read("app/build.gradle.kts").contains("keepDebugSymbols += \"**/libfrpc.so\""));
        assertTrue(read("THIRD_PARTY_NOTICES.md").contains("https://github.com/fatedier/frp"));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            Path fromModule = current.resolve(relativePath.replaceFirst("^app/", ""));
            if (Files.isRegularFile(fromModule)) {
                return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
            }
            current = current.getParent();
        }
        throw new AssertionError("Could not locate repository file: " + relativePath);
    }
}
