package dev.scandium.mixin.physics;

import dev.scandium.config.ScandiumConfig;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mobs without players nearby think less often. Ported from Vodium.
 * <p>
 * A mob's server tick has three expensive parts: goal re-evaluation (many goals search entities in a radius),
 * path finding (A* over blocks) and the brain (villagers, piglins, frogs, axolotls, wardens run sensors for
 * nearby entities and points of interest). A village of 30 villagers costs more than a hundred zombies.
 * <p>
 * All three are throttled under the same condition: the mob has no target and no player is within the
 * activation range. A mob in combat or chase is never throttled.
 * <p>
 * This is a deliberate behaviour change, not a pure optimization: a far-away mob notices a player a few ticks
 * later and walks its route in steps, and a far-away village's daily routine runs slower (zombie villager
 * curing is also counted in customServerAiStep). It all happens outside the activation range, where the player
 * can't see it, but it can be switched off ({@code mobAiThrottle}).
 */
@Mixin(Mob.class)
public abstract class MobAiThrottleMixin {
    @Shadow
    protected abstract void customServerAiStep();

    /** Catches both calls (targetSelector.tick() and goalSelector.tick()) in serverAiStep. */
    @Redirect(
            method = "serverAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V")
    )
    private void scandium$throttleGoalSelection(GoalSelector selector) {
        Mob self = (Mob) (Object) this;
        if (!scandium$shouldThrottle(self) || scandium$isFullTick(self)) {
            selector.tick();
        } else {
            // Vanilla's own light path: running goals keep ticking, no new goals are searched.
            selector.tickRunningGoals(false);
        }
    }

    /** Skipping a navigation tick doesn't break the path: the navigation keeps it and continues next time. */
    @Redirect(
            method = "serverAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/navigation/PathNavigation;tick()V")
    )
    private void scandium$throttleNavigation(PathNavigation navigation) {
        Mob self = (Mob) (Object) this;
        if (!scandium$shouldThrottle(self) || scandium$isFullTick(self)) {
            navigation.tick();
        }
    }

    /** For goal-based mobs this is nearly empty; for brain-based mobs it runs the sensors. */
    @Redirect(
            method = "serverAiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Mob;customServerAiStep()V")
    )
    private void scandium$throttleCustomAi(Mob self) {
        if (!scandium$shouldThrottle(self) || scandium$isFullTick(self)) {
            this.customServerAiStep();
        }
    }

    /**
     * The mob id is part of the tick number on purpose: otherwise all far mobs would think in the same tick and
     * the load would arrive as one spike every N ticks instead of evenly.
     */
    @Unique
    private static boolean scandium$isFullTick(Mob mob) {
        int tick = mob.level().getServer().getTickCount() + mob.getId();
        return tick % ScandiumConfig.get().mobAiThrottleInterval == 0;
    }

    @Unique
    private static boolean scandium$shouldThrottle(Mob mob) {
        ScandiumConfig cfg = ScandiumConfig.get();
        if (!cfg.mobAiThrottleActive() || mob.getTarget() != null) {
            return false;
        }
        // Only walks the player list, not the entities around.
        return !mob.level().hasNearbyAlivePlayer(mob.getX(), mob.getY(), mob.getZ(), cfg.mobAiActivationRange);
    }
}
