package com.cobbleraids.goals;

import com.cobbleraids.CobbleRaids;
import com.cobbleraids.CobbleRaids.Raid;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobbleraids.mixin.accessors.GoalSelectorAccessor;
import com.cobbleraids.mixin.accessors.MobEntityAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class BossGoals {
    private static final Logger LOGGER = LoggerFactory.getLogger(CobbleRaids.MOD_ID + ":BossGoals");

    public static void addBossGoals(PokemonEntity entity, Raid raid) {
        if (entity == null || raid == null) {
            LOGGER.error("Cannot add boss goals: entity or raid is null");
            return;
        }
        clearGoals(entity);
        ((MobEntityAccessor) entity).getGoalSelector().add(1, new LookAtRecentAttackerGoal(entity, raid));
        ((MobEntityAccessor) entity).getGoalSelector().add(2, new LookAroundGoal(entity));
        LOGGER.info("Applied boss goals to {}", entity.getPokemon().getSpecies().getName());
    }

    private static void clearGoals(PokemonEntity entity) {
        GoalSelectorAccessor goalAccessor = (GoalSelectorAccessor) ((MobEntityAccessor) entity).getGoalSelector();
        goalAccessor.getInternalGoals().clear();
        GoalSelectorAccessor targetAccessor = (GoalSelectorAccessor) ((MobEntityAccessor) entity).getTargetSelector();
        targetAccessor.getInternalGoals().clear();
    }

    static class LookAtRecentAttackerGoal extends Goal {
        private final PokemonEntity pokemon;
        private final Raid raid;
        @Nullable
        private Entity target;
        // Timer to control how often the boss checks for a new target
        private int refreshTargetCooldown;

        public LookAtRecentAttackerGoal(PokemonEntity pokemon, Raid raid) {
            this.pokemon = pokemon;
            this.raid = raid;
            this.setControls(EnumSet.of(Goal.Control.LOOK));
        }

        @Override
        public boolean canStart() {
            // The goal can start if there's any attacker to look at.
            if (raid.getLastAttacker() == null) {
                return false;
            }
            // Find the initial target.
            this.target = findTarget();
            return this.target != null;
        }

        @Override
        public boolean shouldContinue() {
            // The goal should continue as long as we have a valid target.
            // The tick() method will handle updating who the target is.
            return this.target != null && this.target.isAlive() && pokemon.canSee(this.target) && this.target.squaredDistanceTo(this.pokemon) <= 1024.0D; // 32*32 blocks
        }

        @Override
        public void start() {
            // Reset the cooldown when the goal begins.
            this.refreshTargetCooldown = 0;
        }

        @Override
        public void stop() {
            // Clear the target when the goal ends.
            this.target = null;
        }

        /**
         * Called every tick while the goal is active. This is where we add the dynamic update logic.
         */
        @Override
        public void tick() {
            this.refreshTargetCooldown--;
            // If the cooldown is over, check for a new target.
            if (this.refreshTargetCooldown <= 0) {
                // Reset the cooldown to a random value between 0.5 and 1 second (10-20 ticks).
                this.refreshTargetCooldown = 10 + this.pokemon.getRandom().nextInt(10);
                // Re-run the findTarget logic to get the MOST RECENT attacker.
                this.target = findTarget();
            }

            // If we have a valid target (either the old one or a new one), look at it.
            if (this.target != null) {
                this.pokemon.getLookControl().lookAt(this.target, 10.0F, 40.0F);
            }
        }

        /**
         * This method finds the best entity to look at based on the raid's last attacker.
         * It prioritizes the attacker's Pokémon and falls back to the player.
         */
        @Nullable
        private Entity findTarget() {
            ServerWorld world = (ServerWorld) this.pokemon.getWorld();
            UUID attackerUuid = raid.getLastAttacker(); // Always fetches the latest attacker UUID

            if (attackerUuid == null) {
                return null;
            }

            PlayerEntity attackerPlayer = world.getPlayerByUuid(attackerUuid);
            if (attackerPlayer == null || !attackerPlayer.isAlive()) {
                return null;
            }

            // Search for the active Pokémon of the last attacker.
            Box searchArea = this.pokemon.getBoundingBox().expand(64.0D);
            List<PokemonEntity> nearbyOwnedPokemon = world.getEntitiesByClass(
                    PokemonEntity.class,
                    searchArea,
                    (pokemonEntity) -> {
                        UUID ownerUuid = pokemonEntity.getPokemon().getOwnerUUID();
                        return attackerPlayer.getUuid().equals(ownerUuid) &&
                                !pokemonEntity.getUuid().equals(this.pokemon.getUuid());
                    }
            );

            // If their Pokémon is out, target the closest one to the player.
            if (!nearbyOwnedPokemon.isEmpty()) {
                return nearbyOwnedPokemon.stream()
                        .min(Comparator.comparingDouble(p -> p.squaredDistanceTo(attackerPlayer)))
                        .orElse(null);
            }

            // Otherwise, just look at the player themselves.
            return attackerPlayer;
        }
    }
}