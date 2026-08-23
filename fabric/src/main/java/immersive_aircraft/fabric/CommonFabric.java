package immersive_aircraft.fabric;

import immersive_aircraft.*;
import immersive_aircraft.cobalt.network.NetworkHandler;
import immersive_aircraft.entity.VehiclePersistence;
import immersive_aircraft.fabric.cobalt.network.NetworkHandlerImpl;
import immersive_aircraft.fabric.cobalt.registration.CobaltFuelRegistryImpl;
import immersive_aircraft.fabric.cobalt.registration.RegistrationImpl;
import immersive_aircraft.network.s2c.AircraftDataMessage;
import immersive_aircraft.network.s2c.VehicleUpgradesMessage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;

public final class CommonFabric implements ModInitializer {
    static {
        Main.MOD_LOADER = "fabric";

        new RegistrationImpl();
        new NetworkHandlerImpl();
        new CobaltFuelRegistryImpl();
    }

    @Override
    public void onInitialize() {
        Items.bootstrap();
        Sounds.bootstrap();
        Entities.bootstrap();
        WeaponRegistry.bootstrap();
        DataLoaders.bootstrap();

        Messages.loadMessages();

        CreativeModeTab group = FabricItemGroup.builder()
                .title(ItemGroups.getDisplayName())
                .icon(ItemGroups::getIcon)
                .displayItems((enabledFeatures, entries) -> entries.acceptAll(Items.getSortedItems()))
                .build();

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Main.locate("group"), group);

        // Register event for syncing aircraft upgrades.
        ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register(this::onSyncDatapack);

        // Register vehicle persistence events
        ServerPlayConnectionEvents.DISCONNECT.register(this::onDisconnect);
        ServerPlayConnectionEvents.JOIN.register(this::onJoin);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
    }

    /**
     * Send sync packets for upgrades when datapack is reloaded.
     */
    private void onSyncDatapack(ServerPlayer player, boolean joined) {
        NetworkHandler.sendToPlayer(new VehicleUpgradesMessage(), player);
        NetworkHandler.sendToPlayer(new AircraftDataMessage(), player);
    }

    /**
     * Track which vehicles players are riding every tick.
     */
    private void onServerTick(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            VehiclePersistence.trackTick(player);
        }
    }

    /**
     * Save vehicle data when a player disconnects.
     * The tick-based tracking already recorded which vehicle the player was riding.
     */
    private void onDisconnect(net.minecraft.server.network.ServerGamePacketListenerImpl handler, net.minecraft.server.MinecraftServer server) {
        VehiclePersistence.confirmDisconnect(handler.getPlayer());
    }

    /**
     * Restore vehicle data when a player joins.
     */
    private void onJoin(net.minecraft.server.network.ServerGamePacketListenerImpl handler, PacketSender sender, net.minecraft.server.MinecraftServer server) {
        ServerPlayer player = handler.getPlayer();
        VehiclePersistence.get(player.serverLevel()).restoreVehicle(player);
    }
}

