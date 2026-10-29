package com.pla.smart_npc.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.compat.BetterCombatCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.VanillaMeleeAttackAi;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraftforge.common.ToolActions;

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
 * already-loaded attack AnimationRegistry.
 */
public final class BetterCombatClientCompat {
    private static final String BETTER_COMBAT_CLASS = "net.bettercombat.BetterCombat";
    private static final String WEAPON_REGISTRY_CLASS = "net.bettercombat.logic.WeaponRegistry";
    private static final String ANIMATION_REGISTRY_CLASS = "net.bettercombat.client.animation.AnimationRegistry";
    private static final String CUSTOM_ANIMATION_PLAYER_CLASS = "net.bettercombat.client.animation.CustomAnimationPlayer";
    private static final String ANIMATION_APPLIER_CLASS = "dev.kosmx.playerAnim.impl.animation.AnimationApplier";
    private static final String MODIFIER_LAYER_CLASS = "dev.kosmx.playerAnim.api.layered.ModifierLayer";
    private static final String MIRROR_MODIFIER_CLASS = "dev.kosmx.playerAnim.api.layered.modifier.MirrorModifier";
    private static final String TRANSFORM_TYPE_CLASS = "dev.kosmx.playerAnim.api.TransformType";
    private static final String VEC3F_CLASS = "dev.kosmx.playerAnim.core.util.Vec3f";

    private static final Map<PlayerNpcEntity, CachedAttack> ATTACK_CACHE = new WeakHashMap<>();

    private static Method weaponRegistryGetAttributes;
    private static Field animationRegistryAnimations;
    private static boolean disabled;
    private static boolean warnedFailure;

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
                || playerNpc.isUsingItem() && playerNpc.getUseItem().canPerformAction(ToolActions.SHIELD_BLOCK)) {
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
    public static boolean applyAttackAnimation(PlayerModel<?> model, PlayerNpcEntity playerNpc, float partialTick) {
        if (!BetterCombatCompat.isLoaded() || disabled || playerNpc.getBetterCombatAttackAnimationTicks() <= 0
                || playerNpc.isUsingItem() && playerNpc.getUseItem().canPerformAction(ToolActions.SHIELD_BLOCK)) {
            return false;
        }

        try {
            SampledAnimation sampled = sampleCurrentAttack(playerNpc, partialTick);
            if (sampled == null) {
                return false;
            }

            Object animationApplier = sampled.animationApplier();
            // Match PlayerAnimator's PlayerModelMixin application order.
            applyBodyParts(animationApplier, model);
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
    public static boolean applyPoseAnimation(PlayerModel<?> model, PlayerNpcEntity playerNpc, float partialTick) {
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
                || playerNpc.isUsingItem() && playerNpc.getUseItem().canPerformAction(ToolActions.SHIELD_BLOCK)) {
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
        TransformVector position = sampleTransform(sampled.animationApplier(), itemPart, "POSITION");
        TransformVector rotation = sampleTransform(sampled.animationApplier(), itemPart, "ROTATION");

        // Match PlayerAnimator 1.20 HeldItemMixin exactly: model-pixel
        // translation followed by roll(Z), yaw(Y), pitch(X).
        poseStack.translate(position.x() / 16.0F, position.y() / 16.0F, position.z() / 16.0F);
        poseStack.mulPose(Axis.ZP.rotation(rotation.z()));
        poseStack.mulPose(Axis.YP.rotation(rotation.y()));
        poseStack.mulPose(Axis.XP.rotation(rotation.x()));
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

        int animationTick = Math.max(
                0,
                Math.min(animationStopTick - 1, (int) Math.floor(animationTime)));
        float animationPartialTick = clamp(animationTime - animationTick, 0.0F, 0.9999F);

        Object animationPlayer = newCustomAnimationPlayer(attack.animation(), animationTick);
        Object animationForApplier = attack.mirror()
                ? createMirroredAnimation(animationPlayer)
                : animationPlayer;
        Object animationApplier = newAnimationApplier(animationForApplier);
        setAnimationPartialTick(animationApplier, animationForApplier, animationPartialTick);
        return new SampledAnimation(animationApplier);
    }

    private static TransformVector sampleTransform(Object animationApplier, String partName, String transformTypeName)
            throws ReflectiveOperationException {
        Class<?> transformTypeClass = Class.forName(TRANSFORM_TYPE_CLASS);
        Class<?> vec3fClass = Class.forName(VEC3F_CLASS);

        @SuppressWarnings({"rawtypes", "unchecked"})
        Object transformType = Enum.valueOf((Class<? extends Enum>) transformTypeClass.asSubclass(Enum.class), transformTypeName);
        Object zero = vec3fClass.getField("ZERO").get(null);
        Method get3DTransform = animationApplier.getClass().getMethod(
                "get3DTransform", String.class, transformTypeClass, vec3fClass);
        Object vector = get3DTransform.invoke(animationApplier, partName, transformType, zero);

        return new TransformVector(
                readVectorComponent(vector, "getX"),
                readVectorComponent(vector, "getY"),
                readVectorComponent(vector, "getZ"));
    }

    private static float readVectorComponent(Object vector, String getter) throws ReflectiveOperationException {
        Object value = vector.getClass().getMethod(getter).invoke(vector);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("PlayerAnimator vector " + getter + "() did not return a number");
        }
        return number.floatValue();
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
        int animationTick = Math.max(0, (int) Math.floor(animationTime));
        float animationPartialTick = clamp(animationTime - animationTick, 0.0F, 0.9999F);

        Object animationPlayer = newCustomAnimationPlayer(animation, animationTick);
        Object animationForApplier = pose.mirror()
                ? createMirroredAnimation(animationPlayer)
                : animationPlayer;
        Object animationApplier = newAnimationApplier(animationForApplier);
        setAnimationPartialTick(animationApplier, animationForApplier, animationPartialTick);
        return new SampledAnimation(animationApplier);
    }

    /**
     * Better Combat's pose animations are normally persistent
     * KeyframeAnimationPlayers. PlayerNpcEntity cannot own that player-only
     * stack, so sample the same looping interval from the NPC's world age.
     */
    private static float poseAnimationTime(Object animation, PlayerNpcEntity playerNpc, float partialTick) {
        int beginTick = readIntField(animation, "beginTick", 0);
        int returnTick = readIntField(animation, "returnTick", beginTick);
        int stopTick = readIntField(animation, "stopTick", Math.max(returnTick + 1, 20));

        if (stopTick <= returnTick) {
            return Math.max(0, beginTick);
        }

        float absoluteTime = Math.max(0.0F, playerNpc.tickCount + partialTick);
        if (absoluteTime < stopTick) {
            return absoluteTime;
        }

        float loopLength = stopTick - returnTick;
        float loopTime = (absoluteTime - returnTick) % loopLength;
        if (loopTime < 0.0F) {
            loopTime += loopLength;
        }
        return returnTick + loopTime;
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
        Object animation = animationMap().get(poseName);
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
            PlayerModel<?> model,
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

    private static void applyBodyParts(Object animationApplier, PlayerModel<?> model) throws ReflectiveOperationException {
        applyPart(animationApplier, "head", model.head);
        applyPart(animationApplier, "leftArm", model.leftArm);
        applyPart(animationApplier, "rightArm", model.rightArm);
        applyPart(animationApplier, "leftLeg", model.leftLeg);
        applyPart(animationApplier, "rightLeg", model.rightLeg);
        applyPart(animationApplier, "torso", model.body);
    }

    /**
     * Samples the animation on the same variable-speed timeline Better Combat
     * uses for real players. Better Combat scales the keyframes from vanilla
     * player attack speed, applies its global upswing multiplier, then changes
     * speed at the upswing and cooldown boundaries.
     */
    private static float animationTime(PlayerNpcEntity playerNpc, ResolvedAttack attack, float partialTick) {
        int duration = Math.max(1, playerNpc.getBetterCombatAttackAnimationDuration());
        float elapsed = clamp(
                duration - playerNpc.getBetterCombatAttackAnimationTicks() + partialTick,
                0.0F,
                duration);

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

        Object animation = animationMap().get(animationName);
        if (animation == null) {
            return null;
        }

        int endTick = readIntField(animation, "endTick", 20);
        int stopTick = readIntField(animation, "stopTick", Math.max(endTick, 20));
        if (stopTick <= 0) {
            stopTick = Math.max(1, endTick);
        }

        float rawUpswing = readNumberMethod(attack, "upswing", 0.5F);
        float upswingMultiplier = betterCombatUpswingMultiplier();
        float upswingRate = clamp(rawUpswing, 0.0F, 1.0F) * upswingMultiplier;
        float attackLength = playerStyleAttackCooldownTicks(attackStack);

        // Better Combat mutates a copy before handing it to PlayerAnimator:
        // activity-specific leg channels can be disabled and torso is explicitly
        // enabled. A real player also turns its body toward its camera while the
        // attack is active. PlayerNpcEntity instead uses LookControl to aim its
        // head independently at its target, so attack-authored head yaw/roll
        // would overwrite that aim and make the head snap sideways. Keep every
        // head channel under vanilla/NPC control while animating the rest.
        Object preparedAnimation = prepareAttackAnimation(animation, playerNpc);

        boolean mirror = offHandAttack;
        if (playerNpc.getMainArm() == HumanoidArm.LEFT) {
            mirror = !mirror;
        }

        return new ResolvedAttack(
                animationName,
                preparedAnimation,
                Math.max(1, endTick),
                stopTick,
                mirror,
                attackLength,
                upswingRate,
                upswingMultiplier);
    }

    private static Object prepareAttackAnimation(Object animation, PlayerNpcEntity playerNpc) throws ReflectiveOperationException {
        Object builder = animation.getClass().getMethod("mutableCopy").invoke(animation);

        if (playerNpc.getPose() == Pose.SWIMMING || playerNpc.getVehicle() != null) {
            configureStateCollection(builder, "rightLeg", false, false);
            configureStateCollection(builder, "leftLeg", false, false);
        }

        Field torsoField = builder.getClass().getField("torso");
        Object torso = torsoField.get(builder);
        torso.getClass().getMethod("fullyEnablePart", boolean.class).invoke(torso, true);

        configureStateCollection(builder, "head", false, false);

        return builder.getClass().getMethod("build").invoke(builder);
    }

    private static void configureStateCollection(
            Object animationBuilder,
            String collectionFieldName,
            boolean rotationEnabled,
            boolean offsetEnabled
    ) throws ReflectiveOperationException {
        Field collectionField = animationBuilder.getClass().getField(collectionFieldName);
        Object collection = collectionField.get(animationBuilder);
        setStateEnabled(collection, "pitch", rotationEnabled);
        setStateEnabled(collection, "roll", rotationEnabled);
        setStateEnabled(collection, "yaw", rotationEnabled);
        setStateEnabled(collection, "x", offsetEnabled);
        setStateEnabled(collection, "y", offsetEnabled);
        setStateEnabled(collection, "z", offsetEnabled);
    }

    private static void setStateEnabled(Object stateCollection, String fieldName, boolean enabled) throws ReflectiveOperationException {
        Field field = stateCollection.getClass().getField(fieldName);
        Object state = field.get(stateCollection);
        state.getClass().getMethod("setEnabled", boolean.class).invoke(state, enabled);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> animationMap() throws ReflectiveOperationException {
        if (animationRegistryAnimations == null) {
            Class<?> registryClass = Class.forName(ANIMATION_REGISTRY_CLASS);
            animationRegistryAnimations = registryClass.getField("animations");
        }
        Object value = animationRegistryAnimations.get(null);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Better Combat AnimationRegistry.animations is not a Map");
        }
        return (Map<String, Object>) map;
    }

    private static Object newCustomAnimationPlayer(Object animation, int animationTick) throws ReflectiveOperationException {
        Class<?> playerClass = Class.forName(CUSTOM_ANIMATION_PLAYER_CLASS);
        for (Constructor<?> constructor : playerClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 2
                    && parameterTypes[1] == int.class
                    && parameterTypes[0].isAssignableFrom(animation.getClass())) {
                return constructor.newInstance(animation, animationTick);
            }
        }
        throw new NoSuchMethodException("No compatible CustomAnimationPlayer(animation, int) constructor");
    }

    private static Object createMirroredAnimation(Object animationPlayer) throws ReflectiveOperationException {
        Class<?> layerClass = Class.forName(MODIFIER_LAYER_CLASS);
        Object layer = layerClass.getConstructor().newInstance();

        Method setAnimation = findCompatibleMethod(layerClass, "setAnimation", animationPlayer);
        if (setAnimation == null) {
            throw new NoSuchMethodException("No compatible ModifierLayer.setAnimation(IAnimation) method");
        }
        setAnimation.invoke(layer, animationPlayer);

        Class<?> mirrorClass = Class.forName(MIRROR_MODIFIER_CLASS);
        Object mirrorModifier = mirrorClass.getConstructor().newInstance();
        mirrorClass.getMethod("setEnabled", boolean.class).invoke(mirrorModifier, true);

        Method addModifier = findCompatibleMethod(layerClass, "addModifier", mirrorModifier, 0);
        if (addModifier == null) {
            throw new NoSuchMethodException("No compatible ModifierLayer.addModifier(AbstractModifier, int) method");
        }
        addModifier.invoke(layer, mirrorModifier, 0);
        return layer;
    }

    private static Object newAnimationApplier(Object animation) throws ReflectiveOperationException {
        Class<?> applierClass = Class.forName(ANIMATION_APPLIER_CLASS);
        for (Constructor<?> constructor : applierClass.getConstructors()) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(animation.getClass())) {
                return constructor.newInstance(animation);
            }
        }
        throw new NoSuchMethodException("No compatible AnimationApplier(IAnimation) constructor");
    }

    private static void setAnimationPartialTick(Object animationApplier, Object animation, float partialTick) throws ReflectiveOperationException {
        Method method = findPublicMethod(animationApplier.getClass(), "setTickDelta", float.class);
        if (method != null) {
            method.invoke(animationApplier, partialTick);
            return;
        }

        // Compatibility fallback for older PlayerAnimator builds.
        Method setupAnim = findPublicMethod(animation.getClass(), "setupAnim", float.class);
        if (setupAnim != null) {
            setupAnim.invoke(animation, partialTick);
        }
    }

    private static void applyPart(Object animationApplier, String partName, ModelPart part) throws ReflectiveOperationException {
        Method updatePart = animationApplier.getClass().getMethod("updatePart", String.class, ModelPart.class);
        updatePart.invoke(animationApplier, partName, part);
    }

    @Nullable
    private static Method findPublicMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            return owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    @Nullable
    private static Method findCompatibleMethod(Class<?> owner, String name, Object... arguments) {
        methodLoop:
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int index = 0; index < parameterTypes.length; index++) {
                Object argument = arguments[index];
                if (argument == null) {
                    if (parameterTypes[index].isPrimitive()) {
                        continue methodLoop;
                    }
                    continue;
                }
                Class<?> argumentClass = argument.getClass();
                if (parameterTypes[index].isPrimitive()) {
                    if (!primitiveWrapperMatches(parameterTypes[index], argumentClass)) {
                        continue methodLoop;
                    }
                } else if (!parameterTypes[index].isAssignableFrom(argumentClass)) {
                    continue methodLoop;
                }
            }
            return method;
        }
        return null;
    }

    private static boolean primitiveWrapperMatches(Class<?> primitive, Class<?> wrapper) {
        return (primitive == boolean.class && wrapper == Boolean.class)
                || (primitive == byte.class && wrapper == Byte.class)
                || (primitive == short.class && wrapper == Short.class)
                || (primitive == int.class && wrapper == Integer.class)
                || (primitive == long.class && wrapper == Long.class)
                || (primitive == float.class && wrapper == Float.class)
                || (primitive == double.class && wrapper == Double.class)
                || (primitive == char.class && wrapper == Character.class);
    }

    private static Object invokeNoArgs(Object target, String methodName) throws ReflectiveOperationException {
        return target.getClass().getMethod(methodName).invoke(target);
    }

    private static int readIntField(Object target, String fieldName, int fallback) {
        try {
            Field field = target.getClass().getField(fieldName);
            return field.getInt(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
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
        if (!warnedFailure) {
            warnedFailure = true;
            SmartNpc.LOGGER.warn(
                    "Smart NPC Better Combat compat could not {}; disabling Better Combat NPC animations.",
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

    private record SampledAnimation(Object animationApplier) {
    }

    private record TransformVector(float x, float y, float z) {
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
            float upswingMultiplier
    ) {
    }
}
