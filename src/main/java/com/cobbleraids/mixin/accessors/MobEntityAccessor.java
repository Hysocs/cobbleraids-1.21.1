package com.cobbleraids.mixin.accessors;


import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    @Accessor("goalSelector")
    GoalSelector getGoalSelector();

    @Accessor("targetSelector")
    GoalSelector getTargetSelector();
}
