package com.cobbleraids;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.CobblemonEntities;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.party.PartyStore;
import com.cobblemon.mod.common.battles.BattleBuilder;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.battles.pokemon.BattlePokemon;
import com.cobblemon.mod.common.entity.PoseType;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.properties.UncatchableProperty;
import com.cobbleraids.config.GeneralRaidConfig;
import com.cobbleraids.config.RaidBossConfig;
import com.cobbleraids.goals.BossGoals;
import com.cobbleraids.mixin.accessors.LivingEntityAccessor;
import com.cobbleraids.utils.config.ConfigManager;
import com.cobbleraids.utils.config.ConfigMetadata;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import kotlin.Unit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.Vec3ArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CobbleRaids implements ModInitializer {
    public static final String MOD_ID = "cobbleraid";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final Map<UUID, ServerBossBar> catchableBossAnticipationBars = new ConcurrentHashMap<>();
    private static final Map<UUID, ServerBossBar> catchableBossCatchBars = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> playerToCatchableBossEntityMap = new ConcurrentHashMap<>();
    private static final Map<UUID, TimerTask> particleTasks = new ConcurrentHashMap<>();

    private static final Timer timer = new Timer("CobbleRaidTimer", true);
    private static final Random random = new Random();

    public static RaidManager raidManager;

    private ConfigManager<RaidBossConfig> bossConfigManager;
    private ConfigManager<GeneralRaidConfig> generalConfigManager;
    private RaidBossConfig bossConfig;
    private GeneralRaidConfig generalConfig;

    // --- Raid Class ---
    public static class Raid {
        private final PokemonEntity bossEntity;
        private final long maxHealth;
        private long currentHealth;
        private final ServerBossBar bossBar;
        private final Map<UUID, Long> damagers = new ConcurrentHashMap<>();
        private final Set<UUID> battledPlayers = ConcurrentHashMap.newKeySet();
        private final long damagePerWin;
        private final long creationTick;
        private final long despawnTimeSeconds;
        private final GeneralRaidConfig generalConfig;
        private UUID lastAttacker;

        public Raid(PokemonEntity bossEntity, long maxHealth, long damagePerWin, long despawnTimeSeconds, long creationTick, GeneralRaidConfig generalConfig) {
            this.bossEntity = bossEntity;
            this.maxHealth = maxHealth;
            this.currentHealth = maxHealth;
            this.damagePerWin = damagePerWin;
            this.despawnTimeSeconds = despawnTimeSeconds;
            this.creationTick = creationTick;
            this.generalConfig = generalConfig;
            this.bossBar = new ServerBossBar(Text.literal(""), BossBar.Color.PURPLE, BossBar.Style.PROGRESS);
            updateBossBar();
            updateNameWithTime(formatTime(despawnTimeSeconds));
        }

        public UUID getBossUuid() { return bossEntity.getUuid(); }
        public PokemonEntity getBossEntity() { return bossEntity; }
        public Map<UUID, Long> getDamagers() { return damagers; }
        public long getDamagePerWin() { return damagePerWin; }
        public long getCreationTick() { return creationTick; }
        public long getDespawnTimeSeconds() { return despawnTimeSeconds; }
        public ServerBossBar getBossBar() { return bossBar; }
        public UUID getLastAttacker() { return lastAttacker; }

        public void applyDamage(ServerPlayerEntity player, long damage) {
            this.currentHealth = Math.max(0, this.currentHealth - damage);
            this.damagers.merge(player.getUuid(), damage, Long::sum);
            this.lastAttacker = player.getUuid();
            updateBossBar();

            // Flash the boss red if not defeated
            if (this.currentHealth > 0) {
                ((LivingEntityAccessor) this.bossEntity).setHurtTime(10);
            }
        }

        public boolean isDefeated() {
            return this.currentHealth <= 0;
        }

        public void updateBossBar() {
            bossBar.setPercent((float) currentHealth / maxHealth);
        }

        public void updateNameWithTime(String time) {
            String formattedName = generalConfig.getBossBarTitle()
                    .replace("{species}", bossEntity.getPokemon().getSpecies().getName())
                    .replace("{time}", time);
            bossBar.setName(Text.literal(formattedName));
        }

        public void end() {
            bossBar.clearPlayers();

        }

        public void addPlayerToShowBossBar(ServerPlayerEntity player) {
            if (!this.bossBar.getPlayers().contains(player)) {
                this.bossBar.addPlayer(player);
            }
        }

        public void addBattledPlayer(UUID playerUuid) {
            battledPlayers.add(playerUuid);
        }

        public void removePlayerFromBossBar(ServerPlayerEntity player) {
            bossBar.removePlayer(player);
        }
    }

    // --- RaidManager Class ---
    public static class RaidManager {
        private final Map<UUID, Raid> activeRaids = new ConcurrentHashMap<>();
        private MinecraftServer server;
        private GeneralRaidConfig generalConfig;

        public void setServer(MinecraftServer server) { this.server = server; }
        public void setConfig(GeneralRaidConfig config) { this.generalConfig = config; }


        public void createRaid(PokemonEntity bossEntity, long maxHealth, long damagePerWin, long despawnTimeSeconds) {
            if (server == null || generalConfig == null) {
                LOGGER.error("RaidManager server or config is not initialized!");
                return;
            }
            Raid raid = new Raid(bossEntity, maxHealth, damagePerWin, despawnTimeSeconds, server.getTicks(), generalConfig);
            activeRaids.put(bossEntity.getUuid(), raid);
        }

        public void endRaid(UUID bossUuid) {
            Raid raid = activeRaids.remove(bossUuid);
            if (raid != null) {
                raid.end();
            }
        }

        public void endAllRaids() {
            new ArrayList<>(activeRaids.keySet()).forEach(this::endRaid);
            LOGGER.info("Ended all active raids.");
        }

        public Raid getRaidByBossUuid(UUID uuid) {
            return activeRaids.get(uuid);
        }

        public Collection<Raid> getActiveRaids() {
            return activeRaids.values();
        }

        public void addPlayerToRaid(ServerPlayerEntity player, Raid raid) {
            if (generalConfig.shouldShowBossBarOnlyAfterBattle()) {
                raid.addBattledPlayer(player.getUuid());
                raid.addPlayerToShowBossBar(player);
            } else {
                raid.addPlayerToShowBossBar(player);
            }
        }

        public void removePlayerFromRaid(ServerPlayerEntity player) {
            activeRaids.values().forEach(raid -> raid.removePlayerFromBossBar(player));
        }

        public void tick(MinecraftServer server) {
            if (this.server == null) setServer(server);

            List<UUID> toRemove = new ArrayList<>();
            for (Raid raid : activeRaids.values()) {
                long elapsedTicks = server.getTicks() - raid.getCreationTick();

                if (raid.getBossEntity().isRemoved()) {
                    toRemove.add(raid.getBossUuid());
                    continue;
                }

                if (raid.getDespawnTimeSeconds() > 0) {
                    long elapsedSeconds = elapsedTicks / 20;
                    long remainingSeconds = raid.getDespawnTimeSeconds() - elapsedSeconds;
                    if (remainingSeconds <= 0) {
                        toRemove.add(raid.getBossUuid());
                        ((ServerWorld)raid.getBossEntity().getWorld()).spawnParticles(ParticleTypes.POOF, raid.getBossEntity().getX(), raid.getBossEntity().getY() + 0.5, raid.getBossEntity().getZ(), 50, 0.3, 0.3, 0.3, 0.1);
                        server.getPlayerManager().broadcast(Text.literal(raid.getBossEntity().getPokemon().getSpecies().getName() + " has despawned!"), false);
                        continue;
                    }
                    if (elapsedTicks % 20 == 0) {
                        raid.updateNameWithTime(formatTime(remainingSeconds));
                    }
                }

                if (!generalConfig.shouldShowBossBarOnlyAfterBattle() && elapsedTicks % 100 == 0) {
                    ServerWorld world = (ServerWorld) raid.getBossEntity().getWorld();
                    List<ServerPlayerEntity> nearbyPlayers = world.getPlayers(p -> p.squaredDistanceTo(raid.getBossEntity()) < 150 * 150);
                    Set<ServerPlayerEntity> currentPlayers = new HashSet<>(raid.getBossBar().getPlayers());

                    for(ServerPlayerEntity p : nearbyPlayers) {
                        if (!currentPlayers.contains(p)) raid.addPlayerToShowBossBar(p);
                    }
                    currentPlayers.removeAll(nearbyPlayers);
                    currentPlayers.forEach(raid::removePlayerFromBossBar);
                }
            }
            toRemove.forEach(this::endRaid);
        }
    }

    private static String formatTime(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("CobbleRaids is initializing with RaidManager!");

        raidManager = new RaidManager();

        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        RaidBossConfig defaultBossConfig = new RaidBossConfig();
        bossConfigManager = new ConfigManager<>("1.0", defaultBossConfig, RaidBossConfig.class, configDir.resolve("bosses"), ConfigMetadata.defaultFor(defaultBossConfig.getConfigId()));
        generalConfigManager = new ConfigManager<>("1.2", new GeneralRaidConfig(), GeneralRaidConfig.class, configDir, ConfigMetadata.defaultFor("general_raid"));

        bossConfig = bossConfigManager.getConfig();
        generalConfig = generalConfigManager.getConfig();
        raidManager.setConfig(generalConfig);

        registerCommands();
        registerListeners();
        registerTickEvents();
    }

    private void registerTickEvents() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            raidManager.tick(server);

            if (server.getTicks() % (generalConfig.getSpawnAttemptIntervalSeconds() * 20L) == 0) {
                attemptSpawnRaids(server);
            }
        });
    }

    private void attemptSpawnRaids(MinecraftServer server) {
        if (!catchableBossAnticipationBars.isEmpty() || !catchableBossCatchBars.isEmpty()) {
            return;
        }
        if (raidManager.getActiveRaids().size() >= generalConfig.getMaxActiveRaids()) {
            return;
        }

        List<RaidBossConfig.RaidBoss> possibleBosses = bossConfig.getBosses();
        if (possibleBosses.isEmpty()) return;

        double totalWeight = possibleBosses.stream().mapToDouble(b -> b.spawnChance).sum();
        if (totalWeight <= 0) return;

        double randWeight = random.nextDouble() * totalWeight;
        double cumulative = 0;
        RaidBossConfig.RaidBoss selectedBoss = null;
        for (RaidBossConfig.RaidBoss boss : possibleBosses) {
            cumulative += boss.spawnChance;
            if (randWeight <= cumulative) {
                selectedBoss = boss;
                break;
            }
        }
        if (selectedBoss == null) return;

        if (selectedBoss.spawnPoints.isEmpty()) {
            LOGGER.warn("Selected boss '{}' has no preset spawn points defined. Skipping spawn.", selectedBoss.species);
            return;
        }

        RaidBossConfig.SpawnPoint sp = selectedBoss.spawnPoints.get(random.nextInt(selectedBoss.spawnPoints.size()));
        RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(sp.dimension));
        ServerWorld spawnWorld = server.getWorld(worldKey);
        if (spawnWorld == null) {
            LOGGER.warn("Invalid dimension for spawn point: {}", sp.dimension);
            return;
        }
        Vec3d spawnPos = new Vec3d(sp.x, sp.y, sp.z);

        spawnRaidBoss(spawnWorld, selectedBoss, spawnPos);
        String coords = String.format("%.0f, %.0f, %.0f", spawnPos.x, spawnPos.y, spawnPos.z);
        List<String> messageLines = generalConfig.getSpawnMessage();

        for (String line : messageLines) {
            String formattedLine = line.replace("{species}", selectedBoss.species).replace("{coords}", coords);
            // Text.literal() will correctly parse the color codes (e.g., §c, §l)
            server.getPlayerManager().broadcast(Text.literal(formattedLine), false);
        }
    }

    private void spawnRaidBoss(ServerWorld world, RaidBossConfig.RaidBoss bossDef, Vec3d pos) {
        try {
            PokemonProperties props = PokemonProperties.Companion.parse(bossDef.species);
            Pokemon pokemon = props.create();
            pokemon.setLevel(bossDef.level);
            pokemon.setScaleModifier(bossDef.scale);
            pokemon.getCustomProperties().add(UncatchableProperty.INSTANCE.uncatchable());
            PokemonEntity pokemonEntity = new PokemonEntity(world, pokemon, CobblemonEntities.POKEMON);
            pokemonEntity.setInvulnerable(true);
            pokemonEntity.setNoGravity(true); // Keep if you want floating; remove if you want it grounded
            pokemonEntity.setSilent(true); // Remove if you want sounds (e.g., hurt sounds)
            pokemonEntity.setMovementSpeed(0.0f); // Prevent movement
            pokemonEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, -1, 255, false, false)); // Infinite max slowness to immobilize
            pokemonEntity.setEnablePoseTypeRecalculation(true); // Enable animations
            pokemonEntity.getDataTracker().set(PokemonEntity.Companion.getPOSE_TYPE(), PoseType.STAND);
            pokemonEntity.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), world.getRandom().nextFloat() * 360, 0);
            world.spawnEntity(pokemonEntity);

            raidManager.createRaid(pokemonEntity, bossDef.maxHealth, bossDef.damagePerWin, bossDef.despawnTimeSeconds);
            BossGoals.addBossGoals(pokemonEntity, raidManager.getRaidByBossUuid(pokemonEntity.getUuid())); // Add this

            LOGGER.info("Spawned dynamic raid boss: {} at {}", bossDef.species, pos);
        } catch (Exception e) {
            LOGGER.error("Failed to spawn dynamic raid boss: {}", bossDef.species, e);
        }
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerRaidCommands(dispatcher));
    }

    private void registerListeners() {
        registerBattleListeners();
        registerCaptureListener();
        registerDisconnectListener();
    }

    private void registerRaidCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("raid")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("despawn")
                        .executes(context -> {
                            int count = raidManager.getActiveRaids().size();
                            raidManager.endAllRaids();
                            if (count > 0) {
                                context.getSource().sendFeedback(() -> Text.literal("Despawned " + count + " raid boss(es)."), true);
                            } else {
                                context.getSource().sendError(Text.literal("No active raid bosses found to despawn."));
                            }
                            return count;
                        })
                )
                .then(CommandManager.literal("spawn")
                        .then(CommandManager.argument("pokemon", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    PokemonSpecies.INSTANCE.getImplemented().forEach(species -> builder.suggest(species.getName().toLowerCase()));
                                    return builder.buildFuture();
                                })
                                .then(CommandManager.argument("level", IntegerArgumentType.integer(1, 100))
                                        .then(CommandManager.argument("health", LongArgumentType.longArg(1))
                                                .then(CommandManager.argument("scale", FloatArgumentType.floatArg(0.1f))
                                                        .then(CommandManager.argument("damagePerWin", LongArgumentType.longArg(1))
                                                                .then(CommandManager.argument("despawnTimeSeconds", LongArgumentType.longArg(0)) // Set to 0 for no timer
                                                                        .then(CommandManager.argument("pos", Vec3ArgumentType.vec3())
                                                                                .suggests((context, builder) -> {
                                                                                    Vec3d pos = context.getSource().getPosition();
                                                                                    String suggestionStr = String.format(Locale.US, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
                                                                                    return builder.suggest(suggestionStr).buildFuture();
                                                                                })
                                                                                .executes(context -> {
                                                                                    String pokemonName = StringArgumentType.getString(context, "pokemon");
                                                                                    int level = IntegerArgumentType.getInteger(context, "level");
                                                                                    long health = LongArgumentType.getLong(context, "health");
                                                                                    float scale = FloatArgumentType.getFloat(context, "scale");
                                                                                    long damagePerWin = LongArgumentType.getLong(context, "damagePerWin");
                                                                                    long despawnTime = LongArgumentType.getLong(context, "despawnTimeSeconds");
                                                                                    Vec3d pos = Vec3ArgumentType.getVec3(context, "pos");
                                                                                    return spawnRaidPokemon(context.getSource(), pokemonName, level, health, scale, damagePerWin, despawnTime, pos);
                                                                                })
                                                                        )
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private void registerBattleListeners() {
        CobblemonEvents.BATTLE_STARTED_PRE.subscribe(Priority.HIGHEST, event -> {
            PokemonBattle battle = event.getBattle();
            ServerPlayerEntity player = null;
            PokemonEntity potentialBoss = null;

            for (BattleActor actor : battle.getActors()) {
                if (actor instanceof PlayerBattleActor pa) player = pa.getEntity();
                else if (actor instanceof PokemonBattleActor pba) potentialBoss = pba.getEntity();
            }

            if (player != null && potentialBoss != null && raidManager.getRaidByBossUuid(potentialBoss.getUuid()) != null) {
                LOGGER.info("Intercepting battle with CobbleRaid boss: {}", potentialBoss.getPokemon().getSpecies().getName());
                event.setReason(Text.literal(""));
                event.cancel();
                startRaidBattle(player, potentialBoss);
            }

            return Unit.INSTANCE;
        });

        CobblemonEvents.BATTLE_FAINTED.subscribe(Priority.NORMAL, event -> {
            PokemonEntity faintedEntity = event.getKilled().getEntity();
            UUID originalBossUuid = faintedEntity != null ? faintedEntity.getPokemon().getPersistentData().getUuid("original_boss_uuid") : null;

            if (originalBossUuid != null) {
                ServerPlayerEntity player = null;
                for (BattleActor actor : event.getBattle().getActors()) {
                    if (actor instanceof PlayerBattleActor pa) {
                        player = pa.getEntity();
                        break;
                    }
                }
                if (player != null) {
                    LOGGER.info("Player {} defeated a raid clone.", player.getName().getString());
                    handleRaidDamage(player, originalBossUuid);
                    event.getBattle().end();
                } else {
                    LOGGER.warn("A raid clone fainted, but no player was found in the battle.");
                }
            }
            return Unit.INSTANCE;
        });
    }

    private void registerCaptureListener() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, event -> {
            UUID playerUuid = event.getPlayer().getUuid();
            if (playerToCatchableBossEntityMap.containsKey(playerUuid)) {
                LOGGER.info("Player {} caught the catchable boss. Removing catch bar.", event.getPlayer().getName().getString());
                ServerBossBar catchBar = catchableBossCatchBars.remove(playerUuid);
                if (catchBar != null) catchBar.removePlayer(event.getPlayer());
                playerToCatchableBossEntityMap.remove(playerUuid);
            }
            return Unit.INSTANCE;
        });
    }

    private void registerDisconnectListener() {
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            UUID playerUuid = player.getUuid();
            raidManager.removePlayerFromRaid(player);
            ServerBossBar anticipationBar = catchableBossAnticipationBars.remove(playerUuid);
            if (anticipationBar != null) anticipationBar.removePlayer(player);
            TimerTask particleTask = particleTasks.remove(playerUuid);
            if (particleTask != null) particleTask.cancel();
            ServerBossBar catchBar = catchableBossCatchBars.remove(playerUuid);
            if (catchBar != null) catchBar.removePlayer(player);
            UUID catchableUuid = playerToCatchableBossEntityMap.remove(playerUuid);
            if (catchableUuid != null) {
                Entity entity = ((ServerWorld) player.getWorld()).getEntity(catchableUuid);
                if (entity != null && !entity.isRemoved()) {
                    entity.discard();
                }
            }
        });
    }

    private void handleRaidDamage(ServerPlayerEntity player, UUID originalBossUuid) {
        Raid raid = raidManager.getRaidByBossUuid(originalBossUuid);
        if (raid == null) {
            LOGGER.error("Could not find active raid for boss UUID {}", originalBossUuid);
            return;
        }

        long damageDealt = raid.getDamagePerWin();
        raid.applyDamage(player, damageDealt);
        LOGGER.info("Raid boss {} took {} damage.", raid.getBossEntity().getPokemon().getSpecies().getName(), damageDealt);

        PokemonEntity bossEntity = raid.getBossEntity();
        if (bossEntity != null && !bossEntity.isRemoved()) {
            bossEntity.setInvulnerable(false);
            bossEntity.damage(bossEntity.getDamageSources().generic(), 0.0f);
            bossEntity.setInvulnerable(true);
        }

        if (raid.isDefeated()) {
            LOGGER.info("Raid boss {} has been defeated!", raid.getBossEntity().getPokemon().getSpecies().getName());
            player.sendMessage(Text.literal("You have defeated the Raid Boss!"), false);

            // --- Start: Corrected Defeat Logic ---
            // Capture all necessary data BEFORE the raid object is removed.
            Pokemon bossPokemon = bossEntity.getPokemon();
            Vec3d position = bossEntity.getPos();
            ServerWorld world = (ServerWorld) bossEntity.getWorld();
            Map<UUID, Long> damagers = raid.getDamagers();

            // Schedule all world-changing actions to run safely on the main server thread.
            world.getServer().execute(() -> {
                // Step 1: Play the visual effects.
                playDefeatParticles(world, position);

                // Step 2: Set the boss to its "defeated" state.
                if (!bossEntity.isRemoved()) {
                    // Set the pose to FAINTED, which is perfect for this.
                    bossEntity.getDataTracker().set(PokemonEntity.Companion.getPOSE_TYPE(), PoseType.SLEEP);
                    // As an alternative, you could use bossEntity.setSitting(true);

                    // Disable its AI to ensure it stops all actions (like looking at players).
                    bossEntity.setAiDisabled(true);
                    bossEntity.setEnablePoseTypeRecalculation(false);
                    bossEntity.getDataTracker().set(PokemonEntity.Companion.getPOSE_TYPE(), PoseType.SLEEP);
                }

                // Step 3: End the raid management (removes boss bar, etc.).
                // This no longer despawns the boss because we changed the Raid.end() method.
                raidManager.endRaid(originalBossUuid);

                // Step 4: Distribute rewards to players.
                distributeCatchableBosses(world, bossPokemon, damagers, position);

                // Step 5: Schedule despawn of the original boss after anticipation period (particles stop and catchables spawn)
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        world.getServer().execute(() -> {
                            if (!bossEntity.isRemoved()) {
                                world.spawnParticles(ParticleTypes.POOF, bossEntity.getX(), bossEntity.getY() + 0.5, bossEntity.getZ(), 50, 0.3, 0.3, 0.3, 0.1);
                                bossEntity.discard();
                            }
                        });
                    }
                }, 16000L); // 15s anticipation + 1s buffer
            });
        } else {
            player.sendMessage(Text.literal("The Raid Boss weakens!"), false);
        }
    }

    private void startRaidBattle(ServerPlayerEntity player, PokemonEntity originalBossEntity) {
        Raid raid = raidManager.getRaidByBossUuid(originalBossEntity.getUuid());
        if (raid == null) {
            LOGGER.error("Attempted to start battle with an unregistered raid boss!");
            return;
        }

        Pokemon originalPokemon = originalBossEntity.getPokemon();
        Pokemon clonePokemon = originalPokemon.clone(true, Objects.requireNonNull(player.getServer()).getRegistryManager());
        clonePokemon.getPersistentData().putUuid("original_boss_uuid", originalBossEntity.getUuid());
        clonePokemon.getCustomProperties().add(UncatchableProperty.INSTANCE.uncatchable());
        clonePokemon.setScaleModifier(0.1F);
        PokemonEntity cloneEntity = clonePokemon.sendOut((ServerWorld) player.getWorld(), originalBossEntity.getPos(), null, entity -> {
            entity.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, -1, 1, false, false));
            entity.setAiDisabled(true);
            return Unit.INSTANCE;
        });

        if (cloneEntity == null) {
            LOGGER.error("Failed to spawn the raid boss clone for battle.");
            player.sendMessage(Text.literal("An error occurred starting the raid battle."), false);
            return;
        }

        PartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        UUID leadingPokemonUuid = getLeadingPokemonUuid(party);
        if (leadingPokemonUuid == null) {
            LOGGER.warn("Player {} tried to battle a raid boss without any conscious Pokémon.", player.getName().getString());
            player.sendMessage(Text.literal("You have no Pokémon that can fight!"), false);
            cloneEntity.discard();
            return;
        }
        raidManager.addPlayerToRaid(player, raid);
        LOGGER.info("Starting new battle between {} and invisible clone of {}", player.getName().getString(), originalPokemon.getSpecies().getName());
        BattleBuilder.INSTANCE.pve(player, cloneEntity, leadingPokemonUuid, BattleFormat.Companion.getGEN_9_SINGLES(), false, false, Cobblemon.config.getDefaultFleeDistance(), party);
    }

    private int spawnRaidPokemon(ServerCommandSource source, String pokemonName, int level, long health, float scale, long damagePerWin, long despawnTimeSeconds, Vec3d pos) {
        try {
            ServerWorld world = source.getWorld();
            PokemonProperties props = PokemonProperties.Companion.parse(pokemonName);
            Pokemon pokemon = props.create();
            pokemon.setLevel(level);
            pokemon.setScaleModifier(scale);
            pokemon.getCustomProperties().add(UncatchableProperty.INSTANCE.uncatchable());
            PokemonEntity pokemonEntity = new PokemonEntity(world, pokemon, CobblemonEntities.POKEMON);
            pokemonEntity.setInvulnerable(true);
            pokemonEntity.setNoGravity(true); // Keep if you want floating; remove if you want it grounded
            pokemonEntity.setSitting(false);
            pokemonEntity.setSilent(true); // Remove if you want sounds (e.g., hurt sounds)
            pokemonEntity.setMovementSpeed(0.0f); // Prevent movement
            pokemonEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, -1, 255, false, false)); // Infinite max slowness to immobilize
            pokemonEntity.setEnablePoseTypeRecalculation(true); // Enable animations
            pokemonEntity.getDataTracker().set(PokemonEntity.Companion.getPOSE_TYPE(), PoseType.STAND);
            pokemonEntity.refreshPositionAndAngles(pos.getX(), pos.getY(), pos.getZ(), world.getRandom().nextFloat() * 360, 0);
            world.spawnEntity(pokemonEntity);

            raidManager.createRaid(pokemonEntity, health, damagePerWin, despawnTimeSeconds);
            BossGoals.addBossGoals(pokemonEntity, raidManager.getRaidByBossUuid(pokemonEntity.getUuid()));

            source.sendFeedback(() -> Text.literal("Spawned a level " + pokemon.getLevel() + " " + pokemon.getSpecies().getName() + " raid boss."), true);
            return 1;
        } catch (Exception e) {
            LOGGER.error("Failed to spawn raid Pokemon", e);
            source.sendError(Text.literal("Failed to spawn raid Pokemon. Is the name '" + pokemonName + "' correct?"));
            return 0;
        }
    }

    // --- Unchanged Methods ---
    private void distributeCatchableBosses(ServerWorld world, Pokemon originalBossPokemon, Map<UUID, Long> damagers, Vec3d bossPosition) {
        if (damagers == null || damagers.isEmpty()) {
            LOGGER.warn("No damagers found for boss {}", originalBossPokemon.getSpecies().getName());
            return;
        }
        damagers.keySet().forEach(playerUuid -> {
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(playerUuid);
            if (player != null) {
                initiateCatchableBossSequence(player, originalBossPokemon, bossPosition);
            }
        });
    }

    private void initiateCatchableBossSequence(ServerPlayerEntity player, Pokemon originalBossPokemon, Vec3d bossPosition) {
        UUID playerUuid = player.getUuid();
        Text initialText = Text.literal("Prepare to catch ").append(originalBossPokemon.getDisplayName()).append("...");
        ServerBossBar anticipationBar = new ServerBossBar(initialText, BossBar.Color.YELLOW, BossBar.Style.PROGRESS);
        anticipationBar.addPlayer(player);
        catchableBossAnticipationBars.put(playerUuid, anticipationBar);
        TimerTask particleTask = new TimerTask() {
            @Override
            public void run() {
                if (player.isDisconnected() || !catchableBossAnticipationBars.containsKey(playerUuid)) {
                    this.cancel();
                    return;
                }
                player.getServer().execute(() -> {
                    ServerWorld world = (ServerWorld) player.getWorld();
                    world.spawnParticles(ParticleTypes.LARGE_SMOKE, bossPosition.getX(), bossPosition.getY() + 0.5, bossPosition.getZ(), 80, 0.8, 0.8, 0.8, 0.05);
                });
            }
        };
        timer.scheduleAtFixedRate(particleTask, 0, 500L);
        particleTasks.put(playerUuid, particleTask);
        int preCatchDuration = 15;
        timer.schedule(new TimerTask() {
            private int countdown = preCatchDuration;
            @Override
            public void run() {
                if (player.isDisconnected() || countdown <= 0) {
                    player.getServer().execute(() -> {
                        anticipationBar.removePlayer(player);
                        catchableBossAnticipationBars.remove(playerUuid);
                        particleTasks.remove(playerUuid).cancel();
                        if (!player.isDisconnected()) {
                            spawnCatchableBossForPlayer(player, originalBossPokemon);
                        }
                    });
                    this.cancel();
                    return;
                }
                anticipationBar.setPercent((float) countdown / preCatchDuration);
                Text countdownText = Text.literal("Prepare to catch ").append(originalBossPokemon.getDisplayName()).append(" in " + countdown + "s...");
                anticipationBar.setName(countdownText);
                countdown--;
            }
        }, 0, 1000L);
    }

    private void spawnCatchableBossForPlayer(ServerPlayerEntity player, Pokemon originalBossPokemon) {
        Pokemon catchableBossPokemon = PokemonProperties.Companion.parse(originalBossPokemon.getSpecies().getName()).create();
        catchableBossPokemon.setLevel(originalBossPokemon.getLevel());
        catchableBossPokemon.setShiny(originalBossPokemon.getShiny());
        catchableBossPokemon.getCustomProperties().remove(UncatchableProperty.INSTANCE);
        PokemonEntity catchableBossEntity = new PokemonEntity(player.getWorld(), catchableBossPokemon, CobblemonEntities.POKEMON);
        catchableBossEntity.setAiDisabled(true);
        catchableBossEntity.setNoGravity(true);
        catchableBossEntity.setMovementSpeed(0.0f);
        catchableBossEntity.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, -1, 255, false, false));
        catchableBossEntity.setEnablePoseTypeRecalculation(false);
        catchableBossEntity.getDataTracker().set(PokemonEntity.Companion.getPOSE_TYPE(), PoseType.STAND);
        Vec3d playerPos = player.getPos();
        Vec3d lookVec = player.getRotationVector();
        Vec3d forwardVec = new Vec3d(lookVec.x, 0, lookVec.z).normalize();
        Vec3d spawnPos = playerPos.add(forwardVec.multiply(2.0));
        catchableBossEntity.refreshPositionAndAngles(spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(), player.getYaw(), 0);
        player.getWorld().spawnEntity(catchableBossEntity);
        ServerWorld world = (ServerWorld) catchableBossEntity.getWorld();
        Vec3d particlePos = catchableBossEntity.getPos();
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, particlePos.getX(), particlePos.getY() + 1, particlePos.getZ(), 50, 0.5, 0.5, 0.5, 0.2);
        UUID playerUuid = player.getUuid();
        playerToCatchableBossEntityMap.put(playerUuid, catchableBossEntity.getUuid());
        player.sendMessage(Text.literal("A " + catchableBossPokemon.getSpecies().getName() + " appeared! Catch it before it runs away!"), false);
        LOGGER.info("Spawned catchable boss {} for player {}", catchableBossPokemon.getSpecies().getName(), player.getName().getString());
        ServerBossBar catchBar = new ServerBossBar(Text.literal(catchableBossPokemon.getSpecies().getName() + " will flee soon!"), BossBar.Color.RED, BossBar.Style.PROGRESS);
        catchBar.addPlayer(player);
        catchableBossCatchBars.put(playerUuid, catchBar);
        int catchDuration = 30;
        timer.schedule(new TimerTask() {
            private int countdown = catchDuration;
            @Override
            public void run() {
                if (player.isDisconnected() || !playerToCatchableBossEntityMap.containsKey(playerUuid)) {
                    player.getServer().execute(() -> {
                        catchBar.removePlayer(player);
                        catchableBossCatchBars.remove(playerUuid);
                    });
                    this.cancel();
                    return;
                }
                if (countdown <= 0) {
                    player.getServer().execute(() -> {
                        Entity entity = ((ServerWorld) player.getWorld()).getEntity(playerToCatchableBossEntityMap.get(playerUuid));
                        if (entity != null && !entity.isRemoved()) {
                            ((ServerWorld) entity.getWorld()).spawnParticles(ParticleTypes.POOF, entity.getX(), entity.getY() + 0.5, entity.getZ(), 50, 0.3, 0.3, 0.3, 0.1);
                            entity.discard();
                            player.sendMessage(Text.literal("The Pokémon ran away!"));
                        }
                        catchBar.removePlayer(player);
                        catchableBossCatchBars.remove(playerUuid);
                        playerToCatchableBossEntityMap.remove(playerUuid);
                    });
                    this.cancel();
                    return;
                }
                catchBar.setPercent((float) countdown / catchDuration);
                catchBar.setName(Text.literal(catchableBossPokemon.getSpecies().getName() + " flees in " + countdown + "s"));
                countdown--;
            }
        }, 0, 1000L);
    }

    private void playDefeatParticles(ServerWorld world, Vec3d pos) {
        world.spawnParticles(ParticleTypes.POOF, pos.getX(), pos.getY() + 1.0, pos.getZ(), 150, 0.7, 0.7, 0.7, 0.05);
        world.spawnParticles(ParticleTypes.EXPLOSION, pos.getX(), pos.getY() + 1.0, pos.getZ(), 10, 0.0, 0.0, 0.0, 0.0);
    }

    @Nullable
    private UUID getLeadingPokemonUuid(PartyStore party) {
        for (Pokemon pokemon : party) {
            if (!pokemon.isFainted()) {
                return pokemon.getUuid();
            }
        }
        return null;
    }
}