package immersive_aircraft.entity;

import immersive_aircraft.Main;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persists vehicle state across player disconnects.
 *
 * Uses tick-based tracking: every tick, we record which vehicle entity each player is riding.
 * On disconnect, if the player was riding a vehicle, we save it to persistent storage and discard it.
 * On join/respawn, we recreate the vehicle from saved data and mount the player.
 *
 * If multiple passengers are on the vehicle, the vehicle is NOT despawned.
 */
public class VehiclePersistence extends SavedData {
    private static final String DATA_NAME = Main.MOD_ID + "_vehicles";

    // Tracks which vehicle entity ID each player was last seen riding.
    // Updated every tick. Cleared when the player is not riding anything.
    // On disconnect, if this has a valid entry, the vehicle is saved and restored on rejoin.
    private static final Map<UUID, Integer> lastVehicleIds = new HashMap<>();

    private final Map<UUID, CompoundTag> savedVehicles = new HashMap<>();

    public VehiclePersistence() {
    }

    /**
     * Called every server tick for each online player.
     * Tracks which vehicle the player is currently riding.
     */
    public static void trackTick(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle instanceof VehicleEntity) {
            lastVehicleIds.put(player.getUUID(), vehicle.getId());
        } else {
            lastVehicleIds.remove(player.getUUID());
        }
    }

    /**
     * Called from disconnect event handlers.
     * If the player was riding a vehicle, save it to persistent storage and discard the entity.
     */
    public static void confirmDisconnect(ServerPlayer player) {
        Integer vehicleId = lastVehicleIds.remove(player.getUUID());
        if (vehicleId == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        Entity entity = level.getEntity(vehicleId);

        if (!(entity instanceof VehicleEntity vehicle) || vehicle.isRemoved()) {
            return;
        }

        // If there are other passengers, don't despawn the vehicle
        if (vehicle.getPassengers().size() > 1) {
            return;
        }

        // Save entity type registry name
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());

        // Save full vehicle entity data (health, inventory, fuel, position, rotation, color, etc.)
        CompoundTag vehicleNbt = vehicle.saveWithoutId(new CompoundTag());

        // Wrap in a container tag with the type info
        CompoundTag entryTag = new CompoundTag();
        entryTag.put("VehicleNBT", vehicleNbt);
        entryTag.putString("VehicleType", typeId.toString());

        VehiclePersistence data = get(level);
        data.savedVehicles.put(player.getUUID(), entryTag);
        data.setDirty();

        // Remove vehicle from world
        vehicle.discard();

        Main.LOGGER.info("Saved vehicle {} for player {}", typeId, player.getName().getString());
    }

    public static VehiclePersistence load(@NotNull CompoundTag tag) {
        VehiclePersistence data = new VehiclePersistence();
        CompoundTag vehiclesTag = tag.getCompound(DATA_NAME);
        for (String key : vehiclesTag.getAllKeys()) {
            data.savedVehicles.put(UUID.fromString(key), vehiclesTag.getCompound(key));
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(@NotNull CompoundTag tag) {
        CompoundTag vehiclesTag = new CompoundTag();
        for (Map.Entry<UUID, CompoundTag> entry : savedVehicles.entrySet()) {
            vehiclesTag.put(entry.getKey().toString(), entry.getValue());
        }
        tag.put(DATA_NAME, vehiclesTag);
        return tag;
    }

    public static VehiclePersistence get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                VehiclePersistence::load,
                VehiclePersistence::new,
                DATA_NAME
        );
    }

    /**
     * Restore a vehicle for the player from persistent storage and mount them on it.
     *
     * @return true if a vehicle was restored
     */
    public boolean restoreVehicle(ServerPlayer player) {
        CompoundTag entryTag = savedVehicles.remove(player.getUUID());
        if (entryTag == null) {
            return false;
        }

        setDirty();

        String typeIdStr = entryTag.getString("VehicleType");
        CompoundTag vehicleNbt = entryTag.getCompound("VehicleNBT");

        // Look up entity type from registry
        ResourceLocation typeId = new ResourceLocation(typeIdStr);
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(typeId)) {
            Main.LOGGER.warn("Could not restore vehicle: unknown entity type {}", typeIdStr);
            return false;
        }

        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(typeId);
        ServerLevel level = (ServerLevel) player.level();
        Entity entity = entityType.create(level);

        if (!(entity instanceof VehicleEntity vehicle)) {
            Main.LOGGER.warn("Could not restore vehicle: entity type {} is not a VehicleEntity", typeIdStr);
            return false;
        }

        // Load saved data (health, inventory, fuel, position, rotation, etc.)
        vehicle.load(vehicleNbt);

        // Ensure vehicle spawns near the player if saved position is too far away
        double dx = vehicle.getX() - player.getX();
        double dy = vehicle.getY() - player.getY();
        double dz = vehicle.getZ() - player.getZ();
        if (dx * dx + dy * dy + dz * dz > 100 * 100) {
            vehicle.setPos(player.getX(), player.getY(), player.getZ());
            vehicle.setYRot(player.getYRot());
            vehicle.setXRot(player.getXRot());
        }

        // Spawn and mount
        level.addFreshEntity(vehicle);
        player.startRiding(vehicle);

        Main.LOGGER.info("Restored vehicle {} for player {}", typeId, player.getName().getString());
        return true;
    }
}
