package com.cobbleraids.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
This is placeholder until i can set it up, as the set invulnerable inside of cobblemon itself doesnt fucking work
 */
@Mixin(PokemonEntity.class)
public class PokemonEntityMixin {



	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void makeRaidBossInvulnerable(DamageSource source, CallbackInfoReturnable<Boolean> cir) {

		PokemonEntity thisEntity = (PokemonEntity) (Object) this;
		Pokemon pokemon = thisEntity.getPokemon(); // Access the public 'pokemon' field directly.


		if (pokemon != null) {

			boolean isRaidBoss = pokemon.getPersistentData().getBoolean("is_cobbleraid_boss");


			if (isRaidBoss) {
				cir.setReturnValue(true);
			}
		}
	}
}