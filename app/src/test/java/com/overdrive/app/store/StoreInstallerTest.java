// StoreInstallerTest.java — request checks, hashing and pm output parsing of the store installer
package com.overdrive.app.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class StoreInstallerTest {
    // SHA-256("abc"), FIPS 180-2 test vector
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void ordinaryPackagesAreAccepted() {
        assertNull(StoreInstaller.refusePackageName("org.fdroid.fdroid"));
        assertNull(StoreInstaller.refusePackageName("de.danoeh.antennapod"));
        assertNull(StoreInstaller.refuseInstallRequest("org.videolan.vlc", ABC_SHA256));
        assertNull(StoreInstaller.refuseInstallRequest("org.videolan.vlc", ABC_SHA256.toUpperCase()));
    }

    @Test
    public void overDriveManagedPackagesAreRefused() {
        for (String managed : new String[] {
                "com.overdrive.app", "com.overdrive.store", "com.overdrive.launcher"}) {
            String refusal = StoreInstaller.refusePackageName(managed);
            assertEquals(managed + " is managed by OverDrive", refusal);
            assertEquals(refusal, StoreInstaller.refuseInstallRequest(managed, ABC_SHA256));
        }
    }

    @Test
    public void malformedPackageNamesNeverReachTheShell() {
        // The name is pasted into `pm uninstall <name>`: anything but a dotted identifier is refused.
        for (String bad : new String[] {
                null, "", "vlc", ".org.vlc", "org..vlc", "org.vlc.", "1org.vlc", "org.vlc; reboot",
                "org.vlc && rm -rf /", "org.vlc`id`", "org.vlc$(id)", "org/vlc", "org.vlc\nreboot"}) {
            assertEquals(String.valueOf(bad), "invalid package name", StoreInstaller.refusePackageName(bad));
        }
    }

    @Test
    public void installNeedsAFullSha256() {
        assertEquals("invalid SHA-256", StoreInstaller.refuseInstallRequest("org.videolan.vlc", null));
        assertEquals("invalid SHA-256", StoreInstaller.refuseInstallRequest("org.videolan.vlc", ""));
        assertEquals("invalid SHA-256",
                StoreInstaller.refuseInstallRequest("org.videolan.vlc", ABC_SHA256.substring(1)));
        assertEquals("invalid SHA-256",
                StoreInstaller.refuseInstallRequest("org.videolan.vlc", ABC_SHA256.replace('a', 'g')));
    }

    @Test
    public void copyAndHashReturnsTheSha256OfTheCopiedBytes() throws IOException {
        File out = tmp.newFile("apk");
        String hash = StoreInstaller.copyAndHash(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII)), out, 1024);
        assertEquals(ABC_SHA256, hash);
        assertEquals(3, out.length());
    }

    @Test
    public void copyAndHashRefusesOversizedInput() throws IOException {
        File out = tmp.newFile("apk");
        try {
            StoreInstaller.copyAndHash(new ByteArrayInputStream(new byte[2048]), out, 1024);
            fail("oversized input must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().startsWith("APK larger than"));
        }
    }

    @Test
    public void pmSuccessIsRecognisedOnlyAsItsOwnLine() {
        assertTrue(StoreInstaller.pmOutcome("Success").success);
        assertTrue(StoreInstaller.pmOutcome("Performing Streamed Install\nSuccess\n").success);
        assertTrue(StoreInstaller.pmOutcome("Performing Streamed Install\r\nSuccess\r\n").success);
        assertEquals("Success", StoreInstaller.pmOutcome("Success").message);
        assertFalse(StoreInstaller.pmOutcome("NoSuccess here").success);
    }

    @Test
    public void pmFailureCarriesTheBracketedReason() {
        StoreInstaller.Outcome outcome = StoreInstaller.pmOutcome(
                "Performing Streamed Install\nFailure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: "
                        + "Package org.videolan.vlc signatures do not match previously installed version]");
        assertFalse(outcome.success);
        assertTrue(outcome.message, outcome.message.startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
        assertEquals("DELETE_FAILED_INTERNAL_ERROR",
                StoreInstaller.pmOutcome("Failure [DELETE_FAILED_INTERNAL_ERROR]").message);
    }

    @Test
    public void pmSilenceOrTransportErrorsAreFailures() {
        assertEquals("pm gave no answer", StoreInstaller.pmOutcome("").message);
        assertEquals("pm gave no answer", StoreInstaller.pmOutcome(null).message);
        StoreInstaller.Outcome timeout = StoreInstaller.pmOutcome(
                "Execution failed: deadline 300000ms exceeded (dedicated connection closed to unblock)");
        assertFalse(timeout.success);
        assertTrue(timeout.message.startsWith("Execution failed"));
    }
}
