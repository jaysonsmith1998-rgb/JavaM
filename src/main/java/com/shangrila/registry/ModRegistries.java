package com.shangrila.registry;

import com.shangrila.ShangriLaMod;
import com.shangrila.command.ShangriLaCommand;
import com.shangrila.pipeline.ChamberPipeline;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;

/**
 * Single-entry registration. Hooks the post-worldgen chunk event so the chamber
 * pipeline runs every time a chunk completes worldgen, and registers the debug
 * command.
 */
public final class ModRegistries {

    private ModRegistries() {}

    public static void bootstrap() {
        // The chamber pipeline runs once per chunk, after vanilla worldgen is
        // done. That timing guarantees aquifers, caves, surface, features, and
        // structures have all already placed their blocks. Our pipeline then
        // overwrites where it needs to.
        ServerChunkEvents.CHUNK_GENERATE.register((level, chunk) -> {
            try {
                ChamberPipeline.processChunk(level, chunk);
            } catch (Throwable t) {
                ShangriLaMod.LOG.error(
                        "Chamber pipeline crashed on chunk {} {} – continuing world gen anyway",
                        chunk.getPos().x(), chunk.getPos().z(), t);
            }
        });

        // Debug command — /shangrila locate / /shangrila info.
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) ->
                        ShangriLaCommand.register(dispatcher));
    }
}
