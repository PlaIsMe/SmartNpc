package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/** Vanilla damage keeps enchantments, armor, hurt immunity, and held-weapon durability. */
public final class VanillaMeleeAttackAi {
    private static final Identifier CRITICAL_DAMAGE_ID = Identifier.fromNamespaceAndPath("smart_npc", "critical_damage");
    private static final int SHIELD_DISABLE_TICKS = 100;

    private VanillaMeleeAttackAi() {
    }

    /**
     * Calculates the player's attack-speed attribute for the held item.
     *
     * ItemAttributeModifiers.compute() cannot be used here because it applies
     * every main-hand modifier, including attack damage, to the supplied base.
     */
    public static double weaponAttackSpeed(ItemStack stack) {
        final double baseAttackSpeed = 4.0D;
        double[] modifiers = {0.0D, 0.0D, 1.0D};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (!attribute.equals(Attributes.ATTACK_SPEED)) {
                return;
            }
            switch (modifier.operation()) {
                case ADD_VALUE -> modifiers[0] += modifier.amount();
                case ADD_MULTIPLIED_BASE -> modifiers[1] += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> modifiers[2] *= 1.0D + modifier.amount();
            }
        });

        double withAdditions = baseAttackSpeed + modifiers[0];
        return Math.max(0.1D, (withAdditions + withAdditions * modifiers[1]) * modifiers[2]);
    }

    /** Returns the first whole tick on which a player's held-item attack is fully charged. */
    public static int weaponAttackIntervalTicks(ItemStack stack) {
        return Math.max(1, (int) Math.ceil(20.0D / weaponAttackSpeed(stack)));
    }

    public static boolean attack(PlayerNpcEntity npc, LivingEntity target, boolean critical) {
        if (!(npc.level() instanceof ServerLevel serverLevel)
                || !npc.isAlive() || npc.isNoAi() || npc.isPassenger() || npc.isHealing() || npc.isUsingItem()
                || npc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                || target instanceof Player player && (player.isCreative() || player.isSpectator())
                || !target.isAlive() || npc.isAlliedTo(target) || target.isAlliedTo(npc)
                || npc.distanceToSqr(target) > 9.0D || !npc.getSensing().hasLineOfSight(target)) {
            return false;
        }
        var damage = npc.getAttribute(Attributes.ATTACK_DAMAGE);
        boolean modified = critical && damage != null;
        if (critical && target.isBlocking() && target.getUseItem().getItem() instanceof ShieldItem) {
            if (target instanceof Player player) {
                player.getCooldowns().addCooldown(player.getUseItem(), SHIELD_DISABLE_TICKS);
                player.stopUsingItem();
                serverLevel.playSound(null, target.blockPosition(), SoundEvents.SHIELD_BREAK.value(),
                        SoundSource.PLAYERS, 0.8F, 0.9F + npc.getRandom().nextFloat() * 0.2F);
            } else {
                if (target instanceof PlayerNpcEntity otherNpc) {
                    otherNpc.setShieldGuardCooldown(Math.max(otherNpc.getShieldGuardCooldown(), SHIELD_DISABLE_TICKS));
                }
                target.stopUsingItem();
                serverLevel.playSound(null, target.blockPosition(), SoundEvents.SHIELD_BREAK.value(),
                        SoundSource.PLAYERS, 0.8F, 0.9F + npc.getRandom().nextFloat() * 0.2F);
            }
        }
        if (modified) {
            damage.addTransientModifier(new AttributeModifier(
                    CRITICAL_DAMAGE_ID, 0.5D, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        boolean hit;
        try {
            hit = npc.doHurtTarget(target);
        } finally {
            if (modified) {
                damage.removeModifier(CRITICAL_DAMAGE_ID);
            }
        }
        if (hit && critical) {
            serverLevel.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5D), target.getZ(),
                    16, target.getBbWidth() * 0.4D, target.getBbHeight() * 0.3D, target.getBbWidth() * 0.4D, 0.15D);
            serverLevel.playSound(null, npc.blockPosition(), SoundEvents.PLAYER_ATTACK_CRIT,
                    SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        return hit;
    }
}
