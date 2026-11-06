// LayoutVariantParityContractTest.java — the car runs layout-land: every view the code looks up must exist there too
package com.overdrive.app.ui;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 57.0.17 crashed on every car at start: the "getting ready" strip was added to layout/ only, the
 * head unit (landscape) inflates layout-land/, and findViewById came back null. For the main screen
 * and the dashboard, every id of the portrait layout must also be in the landscape one.
 */
public class LayoutVariantParityContractTest {

    private static final Pattern ID = Pattern.compile("@\\+id/([A-Za-z0-9_]+)");

    @Test
    public void mainScreenLandscapeHasEveryPortraitView() throws IOException {
        assertLandscapeHasEveryPortraitId("activity_main_new.xml");
    }

    @Test
    public void dashboardLandscapeHasEveryPortraitView() throws IOException {
        assertLandscapeHasEveryPortraitId("fragment_dashboard.xml");
    }

    @Test
    public void startupBannerViewsAreInEveryMainScreenVariant() throws IOException {
        for (String dir : new String[] {"layout", "layout-land"}) {
            Set<String> ids = ids(dir + "/activity_main_new.xml");
            for (String id : new String[] {"startupBanner", "startupBannerProgress", "startupBannerTitle",
                    "startupBannerText", "startupBannerClose"}) {
                assertTrue(dir + " lacks " + id, ids.contains(id));
            }
        }
    }

    private static void assertLandscapeHasEveryPortraitId(String file) throws IOException {
        Set<String> missing = new TreeSet<>(ids("layout/" + file));
        missing.removeAll(ids("layout-land/" + file));
        assertTrue("layout-land/" + file + " lacks " + missing, missing.isEmpty());
    }

    private static Set<String> ids(String relative) throws IOException {
        Set<String> out = new TreeSet<>();
        Matcher m = ID.matcher(read("app/src/main/res/" + relative));
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
