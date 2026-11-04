// ModelCommandSupport.java — remote commands a selected vehicle model cannot carry out, so the UI greys them out
package com.overdrive.app.byd;

import com.overdrive.app.config.VehicleModelSelection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Remote commands that do nothing on some models. On the Sealion 7 and the Tang (DiLink 5) the
 * car carries out Lock and Close trunk, but Unlock, Flash lights and Open trunk have no effect,
 * so every screen shows those buttons disabled instead of offering a tap that always fails.
 *
 * <p>Only a resolved model selection counts ({@link VehicleModelSelection#resolvedModelId}): the
 * renderer's cosmetic fallback ({@code modelSource=unset}) never disables anything.
 */
public final class ModelCommandSupport {
    public static final String UNLOCK = "unlock";
    public static final String FLASH = "flash";
    public static final String TRUNK_OPEN = "trunk_open";

    private static final List<String> OPENING_COMMANDS =
            Collections.unmodifiableList(Arrays.asList(UNLOCK, FLASH, TRUNK_OPEN));
    private static final List<String> MODELS_WITHOUT_OPENING = Arrays.asList("sealion7", "tang");

    private ModelCommandSupport() {}

    /** The commands the model selected in {@code modelId}/{@code modelSource} cannot carry out. */
    public static List<String> unsupportedCommands(String modelId, String modelSource) {
        String resolved = VehicleModelSelection.resolvedModelId(modelId, modelSource);
        if (resolved == null) return Collections.emptyList();
        return MODELS_WITHOUT_OPENING.contains(resolved.toLowerCase(Locale.US))
                ? OPENING_COMMANDS
                : Collections.<String>emptyList();
    }

    /** {@link #unsupportedCommands} for a {@code unified.vehicle} section; null reads as no model. */
    public static List<String> unsupportedCommands(JSONObject vehicle) {
        if (vehicle == null) return Collections.emptyList();
        return unsupportedCommands(vehicle.optString("modelId", ""), vehicle.optString("modelSource", ""));
    }

    /** The commands the currently configured vehicle cannot carry out; empty when it can't be read. */
    public static List<String> current() {
        try {
            return unsupportedCommands(com.overdrive.app.config.UnifiedConfigManager.getVehicle());
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    public static JSONArray toJson(List<String> commands) {
        return new JSONArray(commands);
    }
}
