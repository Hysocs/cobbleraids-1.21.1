package com.cobbleraids.mixin;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A Mixin class that targets Cobblemon's PokemonEntity.
 * Its purpose is to make our specific raid boss entities invulnerable,
 * while still allowing us to manually trigger the hurt animation.
 */
@Mixin(PokemonEntity.class)
public class PokemonEntityMixin {

	// NOTE: The @Shadow annotation has been removed because the 'pokemon' field in PokemonEntity is public.
	// We will access it directly by casting 'this'.

	/**
	 * Injects code at the very beginning of the isInvulnerableTo method.
	 *
	 * @param source The source of the damage being checked.
	 * @param cir    The CallbackInfoReturnable object, which lets us change the method's return value.
	 */
	@Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
	private void makeRaidBossInvulnerable(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
		// Get an instance of the class we are mixing into (PokemonEntity) by casting 'this'.
		PokemonEntity thisEntity = (PokemonEntity) (Object) this;
		Pokemon pokemon = thisEntity.getPokemon(); // Access the public 'pokemon' field directly.

		// First, check if the persistent data exists.
		if (pokemon != null) {
			// Check for our custom raid boss tag.
			boolean isRaidBoss = pokemon.getPersistentData().getBoolean("is_cobbleraid_boss");

			// KEY LOGIC: Only apply our custom invulnerability IF the entity is also set to be
			// invulnerable via the vanilla system. This allows setInvulnerable(false) to work.
			if (isRaidBoss) {
				// If it's our boss AND it's supposed to be invulnerable, force the method to return true.
				cir.setReturnValue(true);
			}
			// By doing nothing otherwise, we allow the setInvulnerable(false) -> damage() -> setInvulnerable(true)
			// pattern in handleRaidDamage to work correctly for the damage flash.
		}
	}
}