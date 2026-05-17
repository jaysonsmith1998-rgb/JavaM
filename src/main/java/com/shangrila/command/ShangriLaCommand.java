package com.shangrila.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shangrila.world.ShangriLaRegion;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Debug commands for finding and inspecting Shangri-La regions:
 *
 * <ul>
 *   <li>{@code /shangrila locate} — find the nearest region to the player and
 *       report its center coordinates.</li>
 *   <li>{@code /shangrila info} — describe the player's current relation to the
 *       region grid (inside a region? distance to nearest center? ...).</li>
 * </ul>
 *
 * <p>This is a replacement for vanilla {@code /locate} because our regions
 * aren't registered as Minecraft structures — they're placed by a post-
 * worldgen pipeline. Using region geometry directly is also more reliable
 * since it can't desync from where pipelines actually put villages.
 */
public final class ShangriLaCommand {

    private ShangriLaCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Permission check intentionally omitted — debug command on the player's
        // own world. Adding it back means wiring up the new ServerPlayer permission
        // API (Player#permissions().hasPermission(Permission)), not the old
        // hasPermission(int) which was removed in the 26.1 permission rework.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("shangrila");

        root.then(Commands.literal("locate").executes(ShangriLaCommand::locate));
        root.then(Commands.literal("info").executes(ShangriLaCommand::info));

        dispatcher.register(root);
    }

    private static int locate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = src.getPosition();
        int x = (int) Math.floor(pos.x);
        int z = (int) Math.floor(pos.z);
        long salt = ShangriLaRegion.DEFAULT_SALT;

        // Search outward in expanding rings of grid cells. Stop at the first
        // hosting cell found. With 50% cell occupancy and 5000-block cells,
        // a region center is expected within ~7000 blocks of any point — the
        // search radius below comfortably covers that.
        int gx0 = Math.floorDiv(x, ShangriLaRegion.GRID_SIZE);
        int gz0 = Math.floorDiv(z, ShangriLaRegion.GRID_SIZE);
        final int MAX_RING = 5;     // 5 cells = 25 000 blocks
        Integer bestRcx = null, bestRcz = null;
        long bestD2 = Long.MAX_VALUE;
        for (int ring = 0; ring <= MAX_RING; ring++) {
            boolean foundThisRing = false;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue; // ring boundary only
                    int cx = gx0 + dx;
                    int cz = gz0 + dz;
                    if (!ShangriLaRegion.cellHostsRegion(cx, cz, salt)) continue;
                    int rcx = ShangriLaRegion.regionCenterX(cx, cz, salt);
                    int rcz = ShangriLaRegion.regionCenterZ(cx, cz, salt);
                    long ddx = x - rcx;
                    long ddz = z - rcz;
                    long d2 = ddx * ddx + ddz * ddz;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        bestRcx = rcx;
                        bestRcz = rcz;
                        foundThisRing = true;
                    }
                }
            }
            if (foundThisRing) break; // closest cell is in the smallest ring containing any
        }

        if (bestRcx == null) {
            src.sendFailure(Component.literal(
                    "No Shangri-La region found within " +
                    (MAX_RING * ShangriLaRegion.GRID_SIZE) + " blocks."));
            return 0;
        }

        int dist = (int) Math.round(Math.sqrt((double) bestD2));
        src.sendSuccess(() -> Component.literal(String.format(
                "Nearest Shangri-La region: %d, %d (≈ %d blocks away)",
                bestRcx, bestRcz, dist)), false);
        return 1;
    }

    private static int info(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Vec3 pos = src.getPosition();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y);
        int z = (int) Math.floor(pos.z);
        long salt = ShangriLaRegion.DEFAULT_SALT;

        boolean inside = ShangriLaRegion.horizontalContains(x, z, salt);
        long d2 = ShangriLaRegion.nearestRegionDistanceSq(x, z, salt);
        String distStr = d2 < 0
                ? "no region in 3x3 cells"
                : Integer.toString((int) Math.round(Math.sqrt((double) d2))) + " blocks";

        double density = ShangriLaRegion.density(x, y, z, salt);
        boolean openHere = ShangriLaRegion.isOpen(x, y, z, salt);

        src.sendSuccess(() -> Component.literal(String.format(
                "At %d, %d, %d:%n" +
                "  in region footprint: %s%n" +
                "  distance to nearest region center: %s%n" +
                "  density here: %.3f (threshold %.2f, open=%s)",
                x, y, z,
                inside, distStr,
                density, ShangriLaRegion.CARVE_THRESHOLD, openHere)), false);
        return 1;
    }
}
