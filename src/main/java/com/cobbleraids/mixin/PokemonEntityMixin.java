package com.cobbleraids.mixin;

import com.cobblemon.mod.common.api.drop.DropTable;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
This is placeholder until i can set it up, as the set invulnerable inside of cobblemon itself doesnt fucking work
 */
@Mixin(value = PokemonEntity.class, remap = false)
public class PokemonEntityMixin {
	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void makeRaidBossInvulnerable(DamageSource source, CallbackInfoReturnable<Boolean> cir) {

		Pokemon pokemon = ((PokemonEntity) (Object) this).getPokemon();


		if (pokemon != null) {

			if (pokemon.getPersistentData().getBoolean("is_cobbleraid_boss")) {
				cir.setReturnValue(true);
			}
		}
	}

	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private void makeRaidBossesUnpushable(CallbackInfoReturnable<Boolean> cir){
		Pokemon pokemon = ((PokemonEntity) (Object) this).getPokemon();

		if(pokemon.getPersistentData().getBoolean("is_cobbleraid_boss")){
			cir.setReturnValue(false);
		}
	}
}