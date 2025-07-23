package com.cobbleraids.config;

import com.cobbleraids.utils.config.ConfigData;

import java.util.ArrayList;
import java.util.List;

public class RaidBossConfig implements ConfigData {
    public String version = "1.0";
    public List<RaidBoss> bosses = new ArrayList<>();

    public RaidBossConfig() {
        // Default bosses for initial config creation
        bosses.add(new RaidBoss("pikachu", 50, 10000L, 2.0f, 1.0, List.of(
                new SpawnPoint("minecraft:overworld", 0.0, 64.0, 0.0)
        ), 500L, 1800L)); // 30 minutes
        bosses.add(new RaidBoss("charizard", 80, 50000L, 3.0f, 0.5, List.of(
                new SpawnPoint("minecraft:overworld", 0.0, 64.0, 0.0)
        ), 1000L, 3600L)); // 1 hour
    }

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getConfigId() {
        return "raid_bosses";
    }

    public List<RaidBoss> getBosses() {
        return bosses;
    }

    public static class RaidBoss {
        public String species;
        public int level;
        public long maxHealth;
        public float scale;
        public double spawnChance;
        public List<SpawnPoint> spawnPoints = new ArrayList<>();
        public long damagePerWin;
        public long despawnTimeSeconds; // New field for despawn timer

        public RaidBoss() {} // For GSON

        public RaidBoss(String species, int level, long maxHealth, float scale, double spawnChance, List<SpawnPoint> spawnPoints, long damagePerWin, long despawnTimeSeconds) {
            this.species = species;
            this.level = level;
            this.maxHealth = maxHealth;
            this.scale = scale;
            this.spawnChance = spawnChance;
            this.spawnPoints = spawnPoints;
            this.damagePerWin = damagePerWin;
            this.despawnTimeSeconds = despawnTimeSeconds;
        }
    }

    public static class SpawnPoint {
        public String dimension;
        public double x;
        public double y;
        public double z;

        public SpawnPoint() {} // For GSON

        public SpawnPoint(String dimension, double x, double y, double z) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }
}