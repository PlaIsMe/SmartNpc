package com.pla.smart_npc.client.compat;

import net.minecraft.core.component.DataComponents;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.compat.BetterCombatCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.VanillaMeleeAttackAi;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-only Better Combat bridge for PlayerNpcEntity.
 *
 * Better Combat only injects its PlayerAnimator animation stack into actual
 * client Player entities. PlayerNpcEntity is a PathfinderMob rendered with a
 * PlayerModel, so it never reaches that mixin. This bridge deliberately uses
 * reflection: Better Combat stays a truly optional dependency while, when it
 * is present, we can still use its already-resolved WeaponRegistry and its
 * PlayerAnimator animation registry.
 */
public final class BetterCombatClientCompat {
    private static final String BETTER_COMBAT_CLASS = "net.bettercombat.BetterCombatMod";
    private static final String WEAPON_REGISTRY_CLASS = "net.bettercombat.logic.WeaponRegistry";
    private static final String TRAIL_PARTICLES_CLASS = "net.bettercombat.client.particle.TrailParticles";
    private static final String SLASH_PARTICLE_EFFECT_CLASS = "net.bettercombat.particle.SlashParticleEffect";
    private static final String BETTER_COMBAT_CLIENT_CLASS = "net.bettercombat.client.BetterCombatClientMod";

    private static final Map<PlayerNpcEntity, CachedAttack> ATTACK_CACHE = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, Integer> SPAWNED_TRAIL_SEQUENCES = new WeakHashMap<>();

    private static Method weaponRegistryGetAttributes;
    private static boolean disabled;
    private static boolean warnedFailure;
    private static boolean trailsDisabled;
    private static boolean warnedTrailFailure;

    private BetterCombatClientCompat() {
    }

    /**
     * True only while this NPC has a real melee attack animation in progress
     * and Better Combat can resolve an animation for the currently selected
     * combo attack. The renderer uses this to suppress the vanilla swing so
     * both systems do not fight over the same model parts.
     */
    public static boolean hasActiveAttackAnimation(PlayerNpcEntity playerNpc) {
        if (!BetterCombatCompat.isLoaded() || disabled || playerNpc.getBetterCombatAttackAnimationTicks() <= 0
                || playerNpc.isUsingItem() && playerNpc.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            return false;
        }
        try {
            ResolvedAttack attack = resolveCurrentAttack(playerNpc);
            return attack != null && animationTime(playerNpc, attack, 0.0F) < attack.stopTick();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disable("resolve the NPC attack animation", exception);
            return false;
        }
    }

    /**
     * Applies Better Combat's selected keyframe animation to the supplied
     * PlayerModel. Returns true when an animation was actually applied.
     */
    public static boolean applyAttackAnimation(PlayerModel model, PlayerNpcEntity playerNpc, float partialTick) {
        if (!BetterCombatCompat.isLoaded() || disabled || playerNpc.getBetterCombatAttackAnimationTicks() <= 0) {
            return false;
        }

        try {
            SampledAnimation sampled = sampleCurrentAttack(playerNpc, partialTick);
            if (sampled == null) {
                return false;
            }

            Object animationApplier = sampled.animationApplier();
            // Match PlayerAnimator's PlayerModelMixin application order.
            applyBodyParts(animationApplier, model, playerNpc);
            maybeSpawnAttackTrail(playerNpc, sampled.attack(), partialTick);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disable("apply the NPC attack animation", exception);
            return false;
        }
    }

    /**
     * Applies Better Combat's resolved idle/holding pose to the NPC model.
     *
     * This follows Better Combat's player pose rules:
     *  - main-hand pose comes from WeaponAttributes#pose();
     *  - off-hand pose comes from WeaponAttributes#offHandPose() while dual wielding;
     *  - one-handed body poses are suppressed while walking/sneaking, while
     *    two-handed body poses remain active;
     *  - item channels remain active while walking;
     *  - attacks, item use, swimming, digging/swing activity and charged
     *    crossbows suppress the idle pose.
     */
    public static boolean applyPoseAnimation(PlayerModel model, PlayerNpcEntity playerNpc, float partialTick) {
        if (!BetterCombatCompat.isLoaded() || disabled || isPoseSuppressed(playerNpc)) {
            return false;
        }

        try {
            ResolvedPoseSet poses = resolvePoseSet(playerNpc);
            if (poses == null || !shouldApplyPoseBody(playerNpc, poses)) {
                return false;
            }

            boolean applied = false;
            // Better Combat's stack priority is off-hand body below main-hand
            // body, so apply them in that same order.
            if (poses.offHandPose() != null) {
                SampledAnimation sampled = samplePose(poses.offHandPose(), playerNpc, partialTick);
                if (sampled != null) {
                    applyPoseBodyParts(sampled.animationApplier(), model, playerNpc);
                    applied = true;
                }
            }
            if (poses.mainHandPose() != null) {
                SampledAnimation sampled = samplePose(poses.mainHandPose(), playerNpc, partialTick);
                if (sampled != null) {
                    applyPoseBodyParts(sampled.animationApplier(), model, playerNpc);
                    applied = true;
                }
            }
            return applied;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disable("apply the NPC idle weapon pose", exception);
            return false;
        }
    }

    /**
     * Applies PlayerAnimator's rightItem/leftItem channel at the same point in
     * the render pipeline as PlayerAnimator's HeldItemMixin: after vanilla's
     * third-person hand transform and immediately before ItemInHandRenderer.
     *
     * Better Combat's attack JSONs animate these item channels independently
     * from the arm. Without this transform the NPC hand moves correctly while
     * the sword/tool keeps the vanilla grip rotation.
     */
    public static boolean applyHeldItemTransform(
            PoseStack poseStack,
            PlayerNpcEntity playerNpc,
            HumanoidArm renderedArm,
            float partialTick
    ) {
        if (!BetterCombatCompat.isLoaded() || disabled
                || playerNpc.isUsingItem() && playerNpc.getUseItem().has(DataComponents.BLOCKS_ATTACKS)) {
            return false;
        }

        try {
            // Attack has the highest Better Combat animation-stack priority.
            // Do not compose an idle pose on top of it or the transforms would
            // add together instead of behaving like layered PlayerAnimator.
            if (playerNpc.getBetterCombatAttackAnimationTicks() > 0) {
                SampledAnimation sampled = sampleCurrentAttack(playerNpc, partialTick);
                if (sampled != null) {
                    return applyHeldItemSample(poseStack, sampled, renderedArm);
                }
            }

            if (isPoseSuppressed(playerNpc)) {
                return false;
            }

            ResolvedPoseSet poses = resolvePoseSet(playerNpc);
            if (poses == null) {
                return false;
            }

            // Main/off hand in Minecraft map to the entity's main arm rather
            // than always to right/left. PoseSubStack mirrors authored right
            // hand poses for left-handed players; ResolvedPose carries that
            // same mirror state.
            boolean renderingMainHand = renderedArm == playerNpc.getMainArm();
            ResolvedPose pose = renderingMainHand ? poses.mainHandPose() : poses.offHandPose();
            if (pose == null) {
                return false;
            }

            SampledAnimation sampled = samplePose(pose, playerNpc, partialTick);
            return sampled != null && applyHeldItemSample(poseStack, sampled, renderedArm);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disable("apply the NPC held-item animation", exception);
            return false;
        }
    }

    private static boolean applyHeldItemSample(
            PoseStack poseStack,
            SampledAnimation sampled,
            HumanoidArm renderedArm
    ) throws ReflectiveOperationException {
        String itemPart = renderedArm == HumanoidArm.LEFT ? "leftItem" : "rightItem";
        PlayerAnimationSampler26.applyHeldItemTransform(poseStack, sampled.animationApplier(), itemPart);
        return true;
    }

    @Nullable
    private static SampledAnimation sampleCurrentAttack(PlayerNpcEntity playerNpc, float partialTick)
            throws ReflectiveOperationException {
        ResolvedAttack attack = resolveCurrentAttack(playerNpc);
        if (attack == null) {
            return null;
        }

        float animationTime = animationTime(playerNpc, attack, partialTick);
        int animationStopTick = Math.max(1, attack.stopTick());
        if (animationTime >= animationStopTick) {
            return null;
        }

        Object animationApplier = PlayerAnimationSampler26.sample(
                attack.animation(),
                Math.min(animationTime, animationStopTick - 0.0001F),
                attack.mirror());
        return new SampledAnimation(animationApplier, attack);
    }

    @Nullable
    private static SampledAnimation samplePose(
            ResolvedPose pose,
            PlayerNpcEntity playerNpc,
            float partialTick
    ) throws ReflectiveOperationException {
        Object animation = pose.animation();
        if (animation == null) {
            return null;
        }

        float animationTime = poseAnimationTime(animation, playerNpc, partialTick);
        Object animationApplier = PlayerAnimationSampler26.sample(animation, animationTime, pose.mirror());
        return new SampledAnimation(animationApplier, null);
    }

    /**
     * Better Combat's pose animations are normally persistent
     * KeyframeAnimationPlayers. PlayerNpcEntity cannot own that player-only
     * stack, so sample the same looping interval from the NPC's world age.
     */
    private static float poseAnimationTime(Object animation, PlayerNpcEntity playerNpc, float partialTick) {
        float absoluteTime = Math.max(0.0F, playerNpc.tickCount + partialTick);
        return PlayerAnimationSampler26.loopedTime(animation, absoluteTime);
    }

    @Nullable
    private static ResolvedPoseSet resolvePoseSet(PlayerNpcEntity playerNpc) throws ReflectiveOperationException {
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (mainHand.isEmpty()) {
            return null;
        }

        Object mainAttributes = getWeaponAttributes(mainHand);
        if (mainAttributes == null) {
            return null;
        }

        ResolvedPose mainPose = resolvePose(invokeNoArgs(mainAttributes, "pose"),
                playerNpc.getMainArm() == HumanoidArm.LEFT);

        Object offAttributes = getWeaponAttributes(playerNpc.getOffhandItem());
        boolean dualWielding = offAttributes != null
                && !isTwoHanded(mainAttributes)
                && !isTwoHanded(offAttributes);

        ResolvedPose offPose = null;
        if (dualWielding) {
            offPose = resolvePose(invokeNoArgs(offAttributes, "offHandPose"),
                    playerNpc.getMainArm() != HumanoidArm.LEFT);
        }

        if (mainPose == null && offPose == null) {
            return null;
        }
        return new ResolvedPoseSet(mainPose, offPose, isTwoHanded(mainAttributes));
    }

    @Nullable
    private static ResolvedPose resolvePose(Object poseNameValue, boolean mirror)
            throws ReflectiveOperationException {
        if (!(poseNameValue instanceof String poseName) || poseName.isBlank()) {
            return null;
        }
        Object animation = animationByName(poseName);
        if (animation == null) {
            return null;
        }
        return new ResolvedPose(animation, mirror);
    }

    private static boolean shouldApplyPoseBody(PlayerNpcEntity playerNpc, ResolvedPoseSet poses) {
        if (poses.mainHandTwoHanded()) {
            return true;
        }
        boolean walking = !playerNpc.isDeadOrDying()
                && playerNpc.getDeltaMovement().horizontalDistance() > 0.03D;
        boolean sneaking = playerNpc.isShiftKeyDown() || playerNpc.isCrouching();
        return !walking && !sneaking;
    }

    private static boolean isPoseSuppressed(PlayerNpcEntity playerNpc) {
        if (playerNpc.getBetterCombatAttackAnimationTicks() > 0
                || playerNpc.getMainHandAttackAnimationTicks() > 0
                || playerNpc.swinging
                || playerNpc.isUsingItem()
                || playerNpc.isSwimming()
                || playerNpc.isEpicFightDigging()) {
            return true;
        }
        ItemStack mainHand = playerNpc.getMainHandItem();
        return !mainHand.isEmpty()
                && mainHand.getItem() instanceof CrossbowItem
                && CrossbowItem.isCharged(mainHand);
    }

    private static void applyPoseBodyParts(
            Object animationApplier,
            PlayerModel model,
            PlayerNpcEntity playerNpc
    ) throws ReflectiveOperationException {
        applyPart(animationApplier, "head", model.head);
        applyPart(animationApplier, "leftArm", model.leftArm);
        applyPart(animationApplier, "rightArm", model.rightArm);
        // Better Combat disables pose leg channels while mounted so vanilla's
        // riding leg pose remains authoritative. Swimming suppresses the whole
        // pose earlier in isPoseSuppressed().
        if (playerNpc.getVehicle() == null) {
            applyPart(animationApplier, "leftLeg", model.leftLeg);
            applyPart(animationApplier, "rightLeg", model.rightLeg);
        }
        applyPart(animationApplier, "torso", model.body);
    }

    private static void applyBodyParts(
            Object animationApplier,
            PlayerModel model,
            PlayerNpcEntity playerNpc
    ) throws ReflectiveOperationException {
        // NPC look control owns the head. Better Combat's player controller
        // similarly releases the attack animation's head pitch channel.
        applyPart(animationApplier, "leftArm", model.leftArm);
        applyPart(animationApplier, "rightArm", model.rightArm);
        if (!playerNpc.isSwimming() && playerNpc.getVehicle() == null) {
            applyPart(animationApplier, "leftLeg", model.leftLeg);
            applyPart(animationApplier, "rightLeg", model.rightLeg);
        }
        applyPart(animationApplier, "torso", model.body);
    }

    /**
     * Samples the animation on the same variable-speed timeline Better Combat
     * uses for real players. Better Combat scales the keyframes from vanilla
     * player attack speed, applies its global upswing multiplier, then changes
     * speed at the upswing and cooldown boundaries.
     */
    private static float animationTime(PlayerNpcEntity playerNpc, ResolvedAttack attack, float partialTick) {
        float elapsed = attackElapsedTicks(playerNpc, partialTick);

        float length = Math.max(0.01F, attack.attackLength());
        float upswingRate = clamp(attack.upswingRate(), 0.0F, 0.9999F);
        float upswingMultiplier = clamp(attack.upswingMultiplier(), 0.2F, 1.0F);
        float baseSpeed = attack.endTick() / length;
        float upswingSpeed = baseSpeed / upswingMultiplier;
        float firstGearTime = length * upswingRate;

        float lerpProgress = clamp((upswingMultiplier - 0.5F) / 0.5F, 0.0F, 1.0F);
        float slowDownFactor = 1.0F - upswingRate;
        float classicFactor = upswingRate / Math.max(0.0001F, 1.0F - upswingRate);
        float downwindSpeed = baseSpeed * lerp(lerpProgress, slowDownFactor, classicFactor);

        if (elapsed <= firstGearTime) {
            return elapsed * upswingSpeed;
        }

        float atFirstGear = firstGearTime * upswingSpeed;
        if (elapsed <= length) {
            return atFirstGear + (elapsed - firstGearTime) * downwindSpeed;
        }

        float atCooldownEnd = atFirstGear + (length - firstGearTime) * downwindSpeed;
        return atCooldownEnd + (elapsed - length) * baseSpeed;
    }

    private static float attackElapsedTicks(PlayerNpcEntity playerNpc, float partialTick) {
        int duration = Math.max(1, playerNpc.getBetterCombatAttackAnimationDuration());
        return clamp(
                duration - playerNpc.getBetterCombatAttackAnimationTicks() + partialTick,
                0.0F,
                duration);
    }

    @Nullable
    private static ResolvedAttack resolveCurrentAttack(PlayerNpcEntity playerNpc) throws ReflectiveOperationException {
        int sequence = playerNpc.getBetterCombatAttackSequence();
        CachedAttack cached = ATTACK_CACHE.get(playerNpc);
        if (cached != null && cached.sequence() == sequence) {
            return cached.attack();
        }

        ResolvedAttack resolved = resolveAttack(playerNpc, sequence);
        ATTACK_CACHE.put(playerNpc, new CachedAttack(sequence, resolved));
        return resolved;
    }

    /**
     * Mirrors Better Combat's PlayerAttackHelper#getCurrentAttack selection
     * flow closely enough for a non-Player entity:
     *
     *  - WeaponRegistry has already resolved JSON parents and fallback weapons.
     *  - Dual wielding alternates main/off hand every combo entry.
     *  - Conditions are filtered before choosing the combo index.
     *  - Off-hand and left-handed attacks mirror the keyframe animation.
     */
    @Nullable
    private static ResolvedAttack resolveAttack(PlayerNpcEntity playerNpc, int sequence) throws ReflectiveOperationException {
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (mainHand.isEmpty()) {
            return null;
        }

        Object mainAttributes = getWeaponAttributes(mainHand);
        if (mainAttributes == null) {
            // Better Combat's WeaponAttributesFallback has already populated
            // WeaponRegistry before it is synced to clients. A null here means
            // Better Combat itself has no attributes for the held weapon.
            return null;
        }

        ItemStack offHand = playerNpc.getOffhandItem();
        Object offAttributes = getWeaponAttributes(offHand);
        boolean dualWielding = offAttributes != null
                && !isTwoHanded(mainAttributes)
                && !isTwoHanded(offAttributes);

        int comboCount = Math.max(0, sequence - 1);
        boolean offHandAttack = dualWielding && (comboCount % 2 == 1);
        Object attackAttributes = offHandAttack ? offAttributes : mainAttributes;
        if (attackAttributes == null) {
            return null;
        }

        Object attacksArray = invokeNoArgs(attackAttributes, "attacks");
        if (attacksArray == null || !attacksArray.getClass().isArray()) {
            return null;
        }

        List<Object> eligibleAttacks = new ArrayList<>();
        int attackCount = Array.getLength(attacksArray);
        for (int index = 0; index < attackCount; index++) {
            Object attack = Array.get(attacksArray, index);
            if (attack != null && evaluateAttackConditions(
                    attack,
                    playerNpc,
                    mainAttributes,
                    offAttributes,
                    dualWielding,
                    offHandAttack)) {
                eligibleAttacks.add(attack);
            }
        }
        if (eligibleAttacks.isEmpty()) {
            return null;
        }

        int handSpecificComboCount;
        if (dualWielding) {
            handSpecificComboCount = ((offHandAttack && comboCount > 0) ? comboCount - 1 : comboCount) / 2;
        } else {
            handSpecificComboCount = comboCount;
        }

        int comboIndex = Math.floorMod(handSpecificComboCount, eligibleAttacks.size());
        Object attack = eligibleAttacks.get(comboIndex);
        ItemStack attackStack = offHandAttack ? offHand : mainHand;
        Object animationNameValue = invokeNoArgs(attack, "animation");
        if (!(animationNameValue instanceof String animationName) || animationName.isBlank()) {
            return null;
        }

        Object animation = animationByName(animationName);
        if (animation == null) {
            return null;
        }

        int endTick = Math.max(1, Math.round(PlayerAnimationSampler26.endTick(animation)));
        int stopTick = Math.max(1, (int) Math.ceil(PlayerAnimationSampler26.stopTick(animation)));
        if (stopTick <= 0) {
            stopTick = Math.max(1, endTick);
        }

        float rawUpswing = readNumberMethod(attack, "upswing", 0.5F);
        float upswingMultiplier = betterCombatUpswingMultiplier();
        float upswingRate = clamp(rawUpswing, 0.0F, 1.0F) * upswingMultiplier;
        float attackLength = playerStyleAttackCooldownTicks(attackStack);

        boolean mirror = offHandAttack;
        if (playerNpc.getMainArm() == HumanoidArm.LEFT) {
            mirror = !mirror;
        }

        TrailData trail = resolveTrailDataSafely(
                playerNpc,
                attack,
                attackAttributes,
                attackStack,
                animationName,
                mirror,
                attackLength,
                upswingRate);

        return new ResolvedAttack(
                animationName,
                animation,
                Math.max(1, endTick),
                stopTick,
                mirror,
                attackLength,
                upswingRate,
                upswingMultiplier,
                trail);
    }

    @Nullable
    private static TrailData resolveTrailDataSafely(
            PlayerNpcEntity playerNpc,
            Object attack,
            Object weaponAttributes,
            ItemStack weaponStack,
            String animationName,
            boolean mirrored,
            float attackLength,
            float upswingRate
    ) {
        if (trailsDisabled) {
            return null;
        }

        try {
            List<?> placements = resolveTrailPlacements(attack, animationName);
            Object appearance = resolveTrailAppearance(weaponAttributes, weaponStack);
            if (placements.isEmpty() || appearance == null) {
                return null;
            }

            // ENTITY_INTERACTION_RANGE belongs to Player's attribute set and
            // is not registered by PlayerNpcEntity (a PathfinderMob). Better
            // Combat uses the player's vanilla 3-block reach as the base when
            // sizing trails, so use that same value unless another integration
            // has explicitly attached the attribute to this NPC.
            var rangeAttribute = playerNpc.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
            double baseRange = rangeAttribute == null ? 3.0D : rangeAttribute.getValue();
            double fixedRange = readNumberMethod(weaponAttributes, "attackRange", 0.0F);
            double rangeBonus = readNumberMethod(weaponAttributes, "rangeBonus", 0.0F);
            float weaponRange = (float) (fixedRange != 0.0D ? fixedRange : baseRange + rangeBonus);
            float spawnTime = Math.max(0.0F, attackLength * upswingRate);
            return new TrailData(placements, appearance, mirrored, Math.max(0.25F, weaponRange), spawnTime);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disableTrails("resolve the NPC weapon trail", exception);
            return null;
        }
    }

    private static List<?> resolveTrailPlacements(Object attack, String animationName)
            throws ReflectiveOperationException {
        Object attackPlacements = invokeNoArgs(attack, "trailParticles");
        if (attackPlacements instanceof List<?> placements && !placements.isEmpty()) {
            return placements;
        }

        Object trailConfig = betterCombatTrailConfig();
        if (trailConfig == null) {
            return List.of();
        }
        Object animationBased = trailConfig.getClass().getField("animation_based").get(trailConfig);
        if (animationBased instanceof Map<?, ?> map) {
            Object configured = map.get(animationName);
            if (configured instanceof List<?> placements) {
                return placements;
            }
        }
        return List.of();
    }

    @Nullable
    private static Object resolveTrailAppearance(Object weaponAttributes, ItemStack weaponStack)
            throws ReflectiveOperationException {
        Object trailConfig = betterCombatTrailConfig();
        Object conditional = trailConfig == null
                ? null
                : trailConfig.getClass().getField("trail_appearance").get(trailConfig);
        Object weaponConditional = invokeNoArgs(weaponAttributes, "trailAppearance");

        if (conditional != null && weaponConditional != null) {
            Method merge = conditional.getClass().getMethod("merge", conditional.getClass());
            conditional = merge.invoke(conditional, weaponConditional);
        } else if (conditional == null) {
            conditional = weaponConditional;
        }
        if (conditional == null) {
            return null;
        }

        return conditional.getClass().getMethod("resolve", ItemStack.class).invoke(conditional, weaponStack);
    }

    @Nullable
    private static Object betterCombatTrailConfig() throws ReflectiveOperationException {
        Class<?> betterCombatClass = Class.forName(BETTER_COMBAT_CLASS);
        Object manager = betterCombatClass.getField("trailConfig").get(null);
        return manager == null ? null : manager.getClass().getField("value").get(manager);
    }

    private static void maybeSpawnAttackTrail(
            PlayerNpcEntity playerNpc,
            @Nullable ResolvedAttack attack,
            float partialTick
    ) {
        if (attack == null || trailsDisabled) {
            return;
        }

        int sequence = playerNpc.getBetterCombatAttackSequence();
        if (SPAWNED_TRAIL_SEQUENCES.getOrDefault(playerNpc, Integer.MIN_VALUE) == sequence) {
            return;
        }

        TrailData trail = attack.trail();
        if (trail == null || attackElapsedTicks(playerNpc, partialTick) < trail.spawnTime()) {
            return;
        }

        // Mark first so multiple render passes in the same frame cannot emit
        // duplicate trails. A disabled client trail setting also counts as a
        // handled attack, matching Better Combat's own one-shot scheduling.
        SPAWNED_TRAIL_SEQUENCES.put(playerNpc, sequence);
        try {
            if (betterCombatTrailsEnabled()) {
                spawnTrailParticles(playerNpc, trail);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disableTrails("spawn the NPC weapon trail", exception);
        }
    }

    private static boolean betterCombatTrailsEnabled() throws ReflectiveOperationException {
        Class<?> clientClass = Class.forName(BETTER_COMBAT_CLIENT_CLASS);
        Object config = clientClass.getField("config").get(null);
        return config == null || config.getClass().getField("isShowingWeaponTrails").getBoolean(config);
    }

    @SuppressWarnings("unchecked")
    private static void spawnTrailParticles(PlayerNpcEntity playerNpc, TrailData trail)
            throws ReflectiveOperationException {
        Class<?> registryClass = Class.forName(TRAIL_PARTICLES_CLASS);
        Object registryValue = registryClass.getField("ENTRIES").get(null);
        if (!(registryValue instanceof Map<?, ?> registry)) {
            throw new IllegalStateException("Better Combat TrailParticles.ENTRIES is not a Map");
        }

        float scale = trail.weaponRange() - 0.25F;
        float sideSign = trail.mirrored() ? -1.0F : 1.0F;
        float mirrorRoll = trail.mirrored() ? 180.0F : 0.0F;
        float entityYaw = playerNpc.getYRot();
        float entityPitch = playerNpc.getXRot();
        Vec3 side = Vec3.directionFromRotation(0.0F, entityYaw + 90.0F).normalize();
        Vec3 forward = Vec3.directionFromRotation(entityPitch, entityYaw).normalize();

        for (Object placement : trail.placements()) {
            if (placement == null) {
                continue;
            }
            Object typeNameValue = invokeNoArgs(placement, "particle_type");
            if (!(typeNameValue instanceof String typeName)) {
                continue;
            }
            Object entriesValue = registry.get(typeName);
            if (!(entriesValue instanceof List<?> entries)) {
                continue;
            }

            float xAddition = numberValue(invokeNoArgs(placement, "x_addition"));
            float yAddition = numberValue(invokeNoArgs(placement, "y_addition"));
            float zAddition = numberValue(invokeNoArgs(placement, "z_addition"));
            float localYaw = numberValue(invokeNoArgs(placement, "local_yaw"));
            float pitchAddition = numberValue(invokeNoArgs(placement, "pitch_addition"));
            float roll = numberValue(invokeNoArgs(placement, "roll_set"));

            Vec3 origin = new Vec3(playerNpc.getX(), playerNpc.getEyeY() - 0.25D + yAddition, playerNpc.getZ())
                    .add(forward.scale(zAddition))
                    .add(side.scale(xAddition * sideSign));
            Vec3 stabOrigin = origin.add(forward.scale(scale - 1.5F));

            for (Object entry : entries) {
                if (entry == null) {
                    continue;
                }
                boolean stabPosition = Boolean.TRUE.equals(invokeNoArgs(entry, "stabPosition"));
                Vec3 particlePosition = stabPosition ? stabOrigin : origin;
                float entryRoll = numberValue(invokeNoArgs(entry, "rollOffset"));
                Object layeredParticles = invokeNoArgs(entry, "particles");
                if (!(layeredParticles instanceof List<?> layers)) {
                    continue;
                }

                for (Object layer : layers) {
                    Object primary = trail.appearance().getClass().getField("primary").get(trail.appearance());
                    Object secondary = trail.appearance().getClass().getField("secondary").get(trail.appearance());
                    spawnTrailLayer(
                            playerNpc,
                            layer,
                            "bottom",
                            primary,
                            particlePosition,
                            scale,
                            entityPitch + pitchAddition,
                            entityYaw,
                            localYaw * sideSign,
                            (roll + entryRoll + mirrorRoll) * sideSign);
                    spawnTrailLayer(
                            playerNpc,
                            layer,
                            "top",
                            secondary,
                            particlePosition,
                            scale,
                            entityPitch + pitchAddition,
                            entityYaw,
                            localYaw * sideSign,
                            (roll + entryRoll + mirrorRoll) * sideSign);
                }
            }
        }
    }

    private static void spawnTrailLayer(
            PlayerNpcEntity playerNpc,
            Object layeredParticle,
            String particleAccessor,
            @Nullable Object appearancePart,
            Vec3 position,
            float scale,
            float pitch,
            float yaw,
            float localYaw,
            float roll
    ) throws ReflectiveOperationException {
        if (layeredParticle == null || appearancePart == null) {
            return;
        }

        Object particleType = invokeNoArgs(layeredParticle, particleAccessor);
        boolean glows = Boolean.TRUE.equals(invokeNoArgs(appearancePart, "glows"));
        Object colorValue = invokeNoArgs(appearancePart, "color_rgba");
        if (!(colorValue instanceof Number color)) {
            throw new IllegalStateException("Better Combat trail color is not numeric");
        }

        Class<?> effectClass = Class.forName(SLASH_PARTICLE_EFFECT_CLASS);
        Object effect = null;
        for (Constructor<?> constructor : effectClass.getConstructors()) {
            if (constructor.getParameterCount() == 8) {
                effect = constructor.newInstance(
                        particleType,
                        scale,
                        pitch,
                        yaw,
                        localYaw,
                        roll,
                        glows,
                        color.longValue());
                break;
            }
        }
        if (!(effect instanceof ParticleOptions particleOptions)) {
            throw new NoSuchMethodException("No compatible Better Combat SlashParticleEffect constructor");
        }

        playerNpc.level().addParticle(
                particleOptions,
                position.x,
                position.y,
                position.z,
                0.0D,
                0.0D,
                0.0D);
    }

    private static float numberValue(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Better Combat trail value is not numeric");
        }
        return number.floatValue();
    }

    private static boolean evaluateAttackConditions(
            Object attack,
            PlayerNpcEntity playerNpc,
            Object mainAttributes,
            @Nullable Object offAttributes,
            boolean dualWielding,
            boolean offHandAttack
    ) throws ReflectiveOperationException {
        Object conditions = invokeNoArgs(attack, "conditions");
        if (conditions == null || !conditions.getClass().isArray() || Array.getLength(conditions) == 0) {
            return true;
        }

        for (int index = 0; index < Array.getLength(conditions); index++) {
            Object condition = Array.get(conditions, index);
            if (condition == null) {
                continue;
            }
            String conditionName = condition instanceof Enum<?> enumCondition
                    ? enumCondition.name()
                    : condition.toString();
            if (!evaluateCondition(
                    conditionName,
                    playerNpc,
                    mainAttributes,
                    offAttributes,
                    dualWielding,
                    offHandAttack)) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateCondition(
            String condition,
            PlayerNpcEntity playerNpc,
            Object mainAttributes,
            @Nullable Object offAttributes,
            boolean dualWielding,
            boolean offHandAttack
    ) throws ReflectiveOperationException {
        return switch (condition) {
            case "NOT_DUAL_WIELDING" -> !dualWielding;
            case "DUAL_WIELDING_ANY" -> dualWielding;
            case "DUAL_WIELDING_SAME" -> dualWielding
                    && playerNpc.getMainHandItem().getItem() == playerNpc.getOffhandItem().getItem();
            case "DUAL_WIELDING_SAME_CATEGORY" -> dualWielding
                    && sameNonEmptyCategory(mainAttributes, offAttributes);
            case "NO_OFFHAND_ITEM" -> playerNpc.getOffhandItem().isEmpty();
            case "OFF_HAND_SHIELD" -> !playerNpc.getOffhandItem().isEmpty()
                    && playerNpc.getOffhandItem().getItem() instanceof ShieldItem;
            case "MAIN_HAND_ONLY" -> !offHandAttack;
            case "OFF_HAND_ONLY" -> offHandAttack;
            case "MOUNTED" -> playerNpc.getVehicle() != null;
            case "NOT_MOUNTED" -> playerNpc.getVehicle() == null;
            default -> true;
        };
    }

    private static boolean sameNonEmptyCategory(Object mainAttributes, @Nullable Object offAttributes) throws ReflectiveOperationException {
        if (offAttributes == null) {
            return false;
        }
        Object mainCategoryValue = invokeNoArgs(mainAttributes, "category");
        Object offCategoryValue = invokeNoArgs(offAttributes, "category");
        if (!(mainCategoryValue instanceof String mainCategory)
                || !(offCategoryValue instanceof String offCategory)
                || mainCategory.isEmpty()
                || offCategory.isEmpty()) {
            return false;
        }
        return mainCategory.equals(offCategory);
    }

    private static boolean isTwoHanded(Object attributes) throws ReflectiveOperationException {
        return Boolean.TRUE.equals(invokeNoArgs(attributes, "isTwoHanded"));
    }

    @Nullable
    /**
     * Better Combat derives animation length from a Player's attack cooldown.
     * PlayerNpcEntity is not a Player, so reproduce the vanilla player base
     * attack-speed calculation (base 4.0 + held-item MAINHAND modifiers).
     */
    private static float playerStyleAttackCooldownTicks(ItemStack stack) {
        float cooldown = (float) (20.0D / VanillaMeleeAttackAi.weaponAttackSpeed(stack));
        return Math.max(betterCombatAttackIntervalCap(), cooldown);
    }

    private static float betterCombatUpswingMultiplier() {
        try {
            Object config = betterCombatConfig();
            Object value = config.getClass().getMethod("getUpswingMultiplier").invoke(config);
            if (value instanceof Number number) {
                return clamp(number.floatValue(), 0.2F, 1.0F);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        return 0.5F;
    }

    private static float betterCombatAttackIntervalCap() {
        try {
            Object config = betterCombatConfig();
            Field field = config.getClass().getField("attack_interval_cap");
            Object value = field.get(config);
            if (value instanceof Number number) {
                return Math.max(1.0F, number.floatValue());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
        }
        return 2.0F;
    }

    private static Object betterCombatConfig() throws ReflectiveOperationException {
        Class<?> betterCombatClass = Class.forName(BETTER_COMBAT_CLASS);
        return betterCombatClass.getField("config").get(null);
    }

    private static float readNumberMethod(Object target, String methodName, float fallback) {
        try {
            Object value = invokeNoArgs(target, methodName);
            return value instanceof Number number ? number.floatValue() : fallback;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    private static Object getWeaponAttributes(ItemStack stack) throws ReflectiveOperationException {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return weaponRegistryGetAttributes().invoke(null, stack);
    }

    private static Method weaponRegistryGetAttributes() throws ClassNotFoundException, NoSuchMethodException {
        if (weaponRegistryGetAttributes == null) {
            Class<?> registryClass = Class.forName(WEAPON_REGISTRY_CLASS);
            weaponRegistryGetAttributes = registryClass.getMethod("getAttributes", ItemStack.class);
        }
        return weaponRegistryGetAttributes;
    }

    @Nullable
    private static Object animationByName(String animationName) throws ReflectiveOperationException {
        return PlayerAnimationSampler26.animation(Identifier.parse(animationName));
    }

    private static void applyPart(Object animationApplier, String partName, ModelPart part) throws ReflectiveOperationException {
        PlayerAnimationSampler26.applyPart(animationApplier, partName, part);
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float lerp(float delta, float start, float end) {
        return start + delta * (end - start);
    }

    private static void disable(String action, Throwable exception) {
        disabled = true;
        ATTACK_CACHE.clear();
        SPAWNED_TRAIL_SEQUENCES.clear();
        if (!warnedFailure) {
            warnedFailure = true;
            SmartNpc.LOGGER.warn(
                    "Smart NPC Better Combat compat could not {}; disabling Better Combat NPC animations.",
                    action,
                    unwrap(exception));
        }
    }

    private static void disableTrails(String action, Throwable exception) {
        trailsDisabled = true;
        SPAWNED_TRAIL_SEQUENCES.clear();
        if (!warnedTrailFailure) {
            warnedTrailFailure = true;
            SmartNpc.LOGGER.warn(
                    "Smart NPC Better Combat compat could not {}; disabling NPC weapon trails only.",
                    action,
                    unwrap(exception));
        }
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof InvocationTargetException invocationException
                && invocationException.getCause() != null) {
            return invocationException.getCause();
        }
        return exception;
    }

    private record SampledAnimation(Object animationApplier, @Nullable ResolvedAttack attack) {
    }

    private record CachedAttack(int sequence, @Nullable ResolvedAttack attack) {
    }

    private record ResolvedPose(Object animation, boolean mirror) {
    }

    private record ResolvedPoseSet(
            @Nullable ResolvedPose mainHandPose,
            @Nullable ResolvedPose offHandPose,
            boolean mainHandTwoHanded
    ) {
    }

    private record ResolvedAttack(
            String animationName,
            Object animation,
            int endTick,
            int stopTick,
            boolean mirror,
            float attackLength,
            float upswingRate,
            float upswingMultiplier,
            @Nullable TrailData trail
    ) {
    }

    private record TrailData(
            List<?> placements,
            Object appearance,
            boolean mirrored,
            float weaponRange,
            float spawnTime
    ) {
    }
}
