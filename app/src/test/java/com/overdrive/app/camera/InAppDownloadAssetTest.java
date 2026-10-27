// InAppDownloadAssetTest.java — recordings download in the page (progress, iPhone share sheet), old WebView keeps the plain link
package com.overdrive.app.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * An installed web app (iPhone home screen especially) shows no download bar and previews a
 * downloaded file full screen with no way back, so recordings go through BYD.downloads instead of
 * a plain {@code <a download>} navigation.
 */
public class InAppDownloadAssetTest {

    @Test
    public void eventsPageLoadsTheDownloadHelperBeforeEventsJs() throws Exception {
        String html = readWebAsset("local/events.html");
        int helper = html.indexOf("<script src=\"../shared/downloads.js");
        int events = html.indexOf("<script src=\"../shared/events.js");
        assertTrue(helper > 0);
        assertTrue(helper < events);
    }

    @Test
    public void everyRecordingDownloadGoesThroughTheHelper() throws Exception {
        String events = readWebAsset("shared/events.js");
        // list button passes itself so it can show the busy state
        assertTrue(events.contains("BYD.events.downloadVideo(\\'' + recordingKey + '\\', this)"));
        assertTrue(events.contains("BYD.downloads.save(rec.videoUrl, rec.filename, { button: button });"));
        // player link: handled in the page, the href stays as the fallback
        assertTrue(events.contains("BYD.downloads.save(rec.videoUrl, rec.filename, { button: downloadBtn });"));
        assertTrue(events.contains("e.preventDefault();"));
        // evidence pack: named by the server's Content-Disposition
        assertTrue(events.contains("BYD.downloads.save(packUrl, '');"));
    }

    @Test
    public void helperKeepsThePlainDownloadOnTheHeadUnitWebView() throws Exception {
        String js = readWebAsset("shared/downloads.js");
        // Chrome 58 (head unit) has no AbortController: no in-page download there
        assertTrue(js.contains("typeof window.AbortController === 'function'"));
        assertTrue(js.contains("if (!supported()) {"));
        assertTrue(js.indexOf("if (!supported()) {") < js.indexOf("var running = jobs[url];"));
        // plain values before min()/env() so old engines still place the panel
        assertTrue(js.contains("bottom:80px;bottom:calc("));
        assertFalse(js.contains("width:min("));
    }

    @Test
    public void iosSaveUsesTheShareSheetOnlyFromATap() throws Exception {
        String js = readWebAsset("shared/downloads.js");
        assertEquals("navigator.share is called in exactly one place", 1, count(js, "navigator.share({"));
        int share = js.indexOf("navigator.share({");
        int action = js.lastIndexOf("run: function () {", share);
        assertTrue("the share call lives in the Save button's handler", action > 0 && share - action < 200);
        assertTrue(js.contains("navigator.canShare({ files: [file] })"));
        // closing the sheet keeps "Save" on screen
        assertTrue(js.contains("if (err && err.name === 'AbortError') return;"));
        // .mp4 must be a video for iOS to offer "Save Video"
        assertTrue(js.contains("if (/\\.mp4$/i.test(job.filename)) return 'video/mp4';"));
    }

    @Test
    public void aSecondTapDoesNotStartASecondDownload() throws Exception {
        String js = readWebAsset("shared/downloads.js");
        assertTrue(js.contains("if (running && running.state === 'running') {"));
        assertTrue(js.contains("return running.promise;"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String readWebAsset(String relativePath) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        Path fromModule = current.resolve("src/main/assets/web").resolve(relativePath);
        if (Files.exists(fromModule)) {
            return new String(Files.readAllBytes(fromModule), StandardCharsets.UTF_8);
        }
        Path fromRepository = current.resolve("app/src/main/assets/web").resolve(relativePath);
        return new String(Files.readAllBytes(fromRepository), StandardCharsets.UTF_8);
    }
}
