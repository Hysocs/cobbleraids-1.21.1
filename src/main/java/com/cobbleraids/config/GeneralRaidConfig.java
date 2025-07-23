package com.cobbleraids.config;

import com.cobbleraids.utils.config.ConfigData;
import java.util.List;
import java.util.Arrays;

public class GeneralRaidConfig implements ConfigData {
    public String version = "1.3"; // Updated version
    public long spawnAttemptIntervalSeconds = 30;
    public int maxActiveRaids = 1;
    // Updated to a List<String> for multi-line support and added a fancier default
    public List<String> spawnMessage = Arrays.asList(
            "§c§lA new Raid Boss has appeared!",
            "§eA wild §6§l{species} §ehas spawned at §a{coords}§e.",
            "§bType §n/warp boss§r §bto challenge it, or §6[Click Here]§b!"
    );
    public boolean showBossBarOnlyAfterBattle = true;
    public String bossBarTitle = "{species} | Despawns in: {time}";

    @Override
    public String getVersion() {
        return version;
    }

    @Override
    public String getConfigId() {
        return "general_raid";
    }

    public long getSpawnAttemptIntervalSeconds() {
        return spawnAttemptIntervalSeconds;
    }

    public int getMaxActiveRaids() {
        return maxActiveRaids;
    }

    // Now returns a List<String>
    public List<String> getSpawnMessage() {
        return spawnMessage;
    }

    public boolean shouldShowBossBarOnlyAfterBattle() {
        return showBossBarOnlyAfterBattle;
    }

    public String getBossBarTitle() {
        return bossBarTitle;
    }
}