package immersive_aircraft.client.sound;

import immersive_aircraft.entity.EngineVehicle;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;

public class AircraftSoundManager {
    private static final Map<Integer, AircraftSoundInstance> activeSounds = new HashMap<>();

    public static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.player == null) {
            activeSounds.clear();
            return;
        }

        activeSounds.entrySet().removeIf(entry -> {
            Entity entity = client.level.getEntity(entry.getKey());
            if (entity == null || entity.isRemoved()) {
                entry.getValue().shutdown();
                return true;
            }
            return false;
        });

        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof EngineVehicle vehicle) {
                int id = vehicle.getId();
                AircraftSoundInstance existing = activeSounds.get(id);

                if (vehicle.getEnginePower() > 0.01f) {
                    if (existing == null || existing.isStopped()) {
                        AircraftSoundInstance sound = new AircraftSoundInstance.EngineSound(client, vehicle);
                        client.getSoundManager().play(sound);
                        activeSounds.put(id, sound);
                    }
                }
            }
        }
    }
}
