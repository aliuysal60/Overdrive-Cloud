// ModelCommandSupportTest.java — Sealion 7 / Tang lose Unlock, Flash and Open trunk; nothing else changes
package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ModelCommandSupportTest {

    private static final java.util.List<String> OPENING =
            Arrays.asList("unlock", "flash", "trunk_open");

    @Test
    public void sealion7AndTangCannotOpen() {
        assertEquals(OPENING, ModelCommandSupport.unsupportedCommands("sealion7", "user"));
        assertEquals(OPENING, ModelCommandSupport.unsupportedCommands("tang", "user"));
        assertEquals(OPENING, ModelCommandSupport.unsupportedCommands(" Tang ", "auto"));
        // configs written before provenance existed keep their model
        assertEquals(OPENING, ModelCommandSupport.unsupportedCommands("sealion7", ""));
    }

    @Test
    public void otherModelsKeepEveryCommand() {
        assertTrue(ModelCommandSupport.unsupportedCommands("seal", "user").isEmpty());
        assertTrue(ModelCommandSupport.unsupportedCommands("han", "user").isEmpty());
        assertTrue(ModelCommandSupport.unsupportedCommands("sealion7x", "user").isEmpty());
    }

    @Test
    public void anUnselectedModelDisablesNothing() {
        // the 3D renderer's fallback is not the owner's choice
        assertTrue(ModelCommandSupport.unsupportedCommands("sealion7", "unset").isEmpty());
        assertTrue(ModelCommandSupport.unsupportedCommands("", "user").isEmpty());
        assertTrue(ModelCommandSupport.unsupportedCommands(null, null).isEmpty());
        assertTrue(ModelCommandSupport.unsupportedCommands((org.json.JSONObject) null).isEmpty());
    }
}
