package com.shangrila;

import com.shangrila.registry.ModRegistries;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShangriLaMod implements ModInitializer {

    public static final String MOD_ID = "shangri_la";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Creates an Identifier under our mod namespace. */
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ModRegistries.bootstrap();
        LOG.info("Shangri-La initialized: post-worldgen chamber pipeline active.");
    }
}
