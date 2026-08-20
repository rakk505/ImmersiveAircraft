package immersive_aircraft.client.sound;

import immersive_aircraft.entity.EngineVehicle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.lang.ref.WeakReference;
import java.util.Random;

public class AircraftSoundInstance extends AbstractTickableSoundInstance {
    private final Minecraft client;
    private final WeakReference<EngineVehicle> vehicleRef;
    private final Random jitterRandom = new Random();

    private double lastDistance;
    private int fade = 0;
    private boolean die = false;

    public AircraftSoundInstance(SoundEvent sound, Minecraft client, EngineVehicle vehicle) {
        super(sound, SoundSource.NEUTRAL, vehicle.level().random);
        this.client = client;
        this.vehicleRef = new WeakReference<>(vehicle);
        this.looping = true;
        this.delay = 0;
    }

    protected boolean canPlay(EngineVehicle vehicle) {
        return vehicle.getEnginePower() > 0.01f && vehicle.isVehicle();
    }

    protected float getPitch(EngineVehicle vehicle) {
        return vehicle.getEnginePitch();
    }

    protected float getVolume(EngineVehicle vehicle) {
        return vehicle.getEngineVolume();
    }

    @Override
    public void tick() {
        EngineVehicle vehicle = vehicleRef.get();

        if (vehicle == null || vehicle.isRemoved()) {
            this.stop();
            return;
        }

        if (!this.canPlay(vehicle)) {
            this.die = true;
        }

        if (this.die) {
            if (this.fade > 0) {
                this.fade--;
            } else {
                this.stop();
                return;
            }
        } else if (this.fade < 3) {
            this.fade++;
        }

        this.x = vehicle.getX();
        this.y = vehicle.getY() + vehicle.getBbHeight() * 0.5;
        this.z = vehicle.getZ();

        this.volume = this.getVolume(vehicle) * (float) fade / 3.0f;

        this.pitch = (jitterRandom.nextFloat() * 0.1f + 0.95f) * this.getPitch(vehicle);

        if (client.player != null && client.player.getVehicle() != vehicle) {
            double distance = vehicle.distanceToSqr(client.player);
            this.pitch += (float) (0.36 * Math.atan(lastDistance - distance));
            this.lastDistance = distance;
        }
    }

    public static class EngineSound extends AircraftSoundInstance {
        public EngineSound(Minecraft client, EngineVehicle vehicle) {
            super(vehicle.getEngineSound(), client, vehicle);
        }
    }

    public void shutdown() {
        this.stop();
    }
}
