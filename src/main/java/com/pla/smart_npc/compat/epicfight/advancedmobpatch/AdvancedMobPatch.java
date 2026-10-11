package com.pla.smart_npc.compat.epicfight.advancedmobpatch;

import com.pla.smart_npc.compat.epicfight.WeaponCapabilityRedirect;
import com.pla.smart_npc.mixin.epicfight.WeaponCapabilityAccessor;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RangedAttackGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotion;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.api.utils.AttackResult;
import yesman.epicfight.api.utils.AttackResult.ResultType;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.registry.entries.EpicFightSounds;
import yesman.epicfight.network.EpicFightNetworkManager;
import yesman.epicfight.network.server.SPChangeLivingMotion;
import yesman.epicfight.registry.entries.EpicFightParticles;
import yesman.epicfight.particle.HitParticleType;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.Faction;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.CapabilityItem.Styles;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;
import yesman.epicfight.world.capabilities.item.Style;
import yesman.epicfight.world.capabilities.item.WeaponCapability;
import yesman.epicfight.world.capabilities.item.WeaponCategory;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;
import yesman.epicfight.world.damagesource.EpicFightDamageTypeTags;
import yesman.epicfight.world.damagesource.StunType;
import yesman.epicfight.registry.entries.EpicFightAttributes;
import yesman.epicfight.world.entity.ai.goal.AnimatedAttackGoal;
import yesman.epicfight.world.entity.ai.goal.TargetChasingGoal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public abstract class AdvancedMobPatch<T extends Mob> extends MobPatch<T> {
    private static final long GENERATED_CHAIN_SEED = 0xA71E5L;
    private static final double GENERATED_ATTACK_RANGE = 3.0D;
    private static final int MIN_GUARD_DURATION_TICKS = 40;
    private static final int MAX_GUARD_DURATION_TICKS = 100;
    private static final int DEFAULT_GUARD_COOLDOWN_TICKS = 20;
    private static final int DEFAULT_GUARD_RETRY_TICKS = 10;
    private static final double DEFAULT_GUARD_DISTANCE = 3.0D;
    private AdvancedCombatBehaviors<MobPatch<?>> combatBehaviors;
    private int attackRadius = (int) GENERATED_ATTACK_RANGE;
    private double chasingSpeed = 1.25D;
    private int recoverTickCount;
    private int lastActionTime;
    private boolean guardingLocally;
    private int guardEndTick;
    private int guardCooldownTicks;
    private AssetAccessor<? extends StaticAnimation> activeGuardAnimation;
    private final Set<Object> combatActionLocks = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean stunnedLastTick;
    private final Map<LivingMotion, AssetAccessor<? extends StaticAnimation>> defaultLivingMotions = new HashMap<>();
    private boolean defaultLivingMotionsCaptured;
    private float stamina;
    private AdvancedStaminaStatus staminaStatus = AdvancedStaminaStatus.COMMON;

    protected AdvancedMobPatch(T original, Faction faction) {
        super(original, faction);
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        this.commonMobUpdateMotion(considerInaction);
    }

    @Override
    public void onJoinWorld(T entity, net.minecraft.world.level.Level level, boolean isClientSide) {
        super.onJoinWorld(entity, level, isClientSide);
        this.stamina = this.getMaxStamina();
        this.staminaStatus = AdvancedStaminaStatus.COMMON;
        this.modifyLivingMotionByCurrentItem(false);
    }

    @Override
    public void preTick() {
        super.preTick();
        if (this.isLogicalClient()) {
            return;
        }
        boolean stunned = this.isStunned();
        if (stunned && (!this.stunnedLastTick || !this.combatActionLocks.isEmpty())) {
            this.interruptCombatActionsForStun();
        }
        this.stunnedLastTick = stunned;
        this.tickLocalGuard();

        float maxStamina = this.getMaxStamina();
        float stamina = this.stamina;
        AdvancedStaminaStatus status = this.staminaStatus;

        if (status == AdvancedStaminaStatus.COMMON) {
            this.recoverTickCount = 0;
            if (stamina <= 0.0F) {
                this.beginStaminaBreak(null);
                return;
            }

            AttributeInstance staminaRegen = this.getOriginal().getAttribute(EpicFightAttributes.STAMINA_REGEN);
            if (this.getEntityState().inaction() || this.guardingLocally || this.isCombatActionLocked()) {
                this.lastActionTime = this.getOriginal().tickCount;
            } else if (staminaRegen != null
                    && this.getOriginal().tickCount - this.lastActionTime > 60
                    && stamina < maxStamina) {
                float regenerated = maxStamina * 0.01F * (float) staminaRegen.getValue();
                this.setStamina(Math.min(maxStamina, stamina + regenerated));
            }
            return;
        }

        this.recoverTickCount++;
        if (status == AdvancedStaminaStatus.BREAK && this.recoverTickCount >= 40) {
            this.staminaStatus = AdvancedStaminaStatus.RECOVER;
        } else if (status == AdvancedStaminaStatus.RECOVER) {
            float progress = Mth.clamp((this.recoverTickCount - 40) / 60.0F, 0.0F, 1.0F);
            this.setStamina(Mth.lerp(progress, 0.0F, maxStamina));
            if (progress >= 1.0F) {
                this.staminaStatus = AdvancedStaminaStatus.COMMON;
            }
        }
    }

    @Override
    protected void initAI() {
        super.initAI();

        AdvancedCombatBehaviors.Builder<MobPatch<?>> builder = this.createCombatBehaviorBuilder();
        this.combatBehaviors = builder.build();
        Goal attackGoal = new AdvancedAnimationAttackGoal<>(
                this,
                this.combatBehaviors,
                this::areCombatActionsAllowed,
                this::tryStartLocalGuard
        );
        Goal chasingGoal = new AdvancedChasingGoal(
                this,
                this.attackRadius,
                this.chasingSpeed,
                this::areCombatActionsAllowed
        );
        this.configureCombatGoalControls(attackGoal, chasingGoal);
        this.getOriginal().goalSelector.addGoal(this.getAttackGoalPriority(), attackGoal);
        this.getOriginal().goalSelector.addGoal(this.getChasingGoalPriority(), chasingGoal);
    }

    protected void configureCombatGoalControls(Goal attackGoal, Goal chasingGoal) {
    }

    protected int getAttackGoalPriority() {
        return 0;
    }

    protected int getChasingGoalPriority() {
        return 1;
    }

    protected boolean isUtilityActionActive() {
        return false;
    }

    protected boolean isCombatEnabled() {
        return true;
    }

    @Override
    protected void selectGoalToRemove(Set<Goal> toRemove) {
        super.selectGoalToRemove(toRemove);

        for (WrappedGoal wrappedGoal : this.getOriginal().goalSelector.getAvailableGoals()) {
            Goal goal = wrappedGoal.getGoal();
            if (goal instanceof MeleeAttackGoal
                    || goal instanceof AnimatedAttackGoal<?>
                    || goal instanceof AdvancedAnimationAttackGoal<?>
                    || goal instanceof RangedAttackGoal
                    || goal instanceof TargetChasingGoal
                    || goal instanceof AdvancedChasingGoal) {
                toRemove.add(goal);
            }
        }
    }

    private AdvancedCombatBehaviors.Builder<MobPatch<?>> createCombatBehaviorBuilder() {
        CapabilityItem mainHandCap = this.getRedirectedHoldingItemCapability(InteractionHand.MAIN_HAND);
        CapabilityItem offHandCap = this.getAdvancedHoldingItemCapability(InteractionHand.OFF_HAND);
        Style style = mainHandCap.getStyle(this);
        AdvancedCombatBehaviors.Builder<MobPatch<?>> builder = AdvancedCombatBehaviors.builder();

        this.addCustomBehaviorRoots(builder, mainHandCap, offHandCap, style);

        WeaponMoveset moveset = this.getWeaponCapabilityMoveset(mainHandCap, style);
        if (!moveset.isEmpty()) {
            builder.newBehaviorRoot(this.createGeneratedAttackRoot(moveset, mainHandCap, offHandCap, style));
        }
        return builder;
    }

    protected void addCustomBehaviorRoots(
            AdvancedCombatBehaviors.Builder<MobPatch<?>> builder,
            CapabilityItem mainHandCap,
            CapabilityItem offHandCap,
            Style style
    ) {
    }

    protected boolean canGenerateWeaponCapabilityMoveset(CapabilityItem mainHandCap, Style style) {
        WeaponCategory category = mainHandCap.getWeaponCategory();
        return !mainHandCap.isEmpty()
                && mainHandCap instanceof WeaponCapability
                && category != WeaponCategories.NOT_WEAPON
                && category != WeaponCategories.RANGED
                && category != WeaponCategories.SHIELD;
    }

    protected WeaponMoveset getWeaponCapabilityMoveset(CapabilityItem mainHandCap, Style style) {
        if (mainHandCap.isEmpty()) {
            return new WeaponMoveset(
                    List.of(Animations.FIST_AUTO1, Animations.FIST_AUTO2, Animations.FIST_AUTO3),
                    Animations.FIST_DASH,
                    Animations.FIST_AIR_SLASH
            );
        }

        if (!this.canGenerateWeaponCapabilityMoveset(mainHandCap, style)) {
            return WeaponMoveset.empty();
        }

        // Epic Fight 21's built-in weapons use data-driven Movesets. The legacy
        // autoAttackMotions map is only populated by older addon capabilities.
        var moveset = ((WeaponCapability) mainHandCap).getCurrentSet(this);
        List<AnimationAccessor<? extends AttackAnimation>> configured = null;
        if (moveset != null) {
            configured = moveset.getComboAttackAnimations();
        } else if (mainHandCap instanceof WeaponCapabilityAccessor accessor) {
            var autoAttackMotions = accessor.smartNpc$getAutoAttackMotions();
            configured = autoAttackMotions.getOrDefault(style, autoAttackMotions.get(Styles.COMMON));
        }
        List<AnimationAccessor<? extends StaticAnimation>> animations = copyAnimations(configured);

        if (animations.size() < 4) {
            return new WeaponMoveset(animations, null, null);
        }

        int dashIndex = animations.size() - 2;
        int jumpIndex = animations.size() - 1;
        return new WeaponMoveset(
                animations.subList(0, dashIndex),
                animations.get(dashIndex),
                animations.get(jumpIndex)
        );
    }

    protected List<AdditionalAttackGroup> getAdditionalAttackGroups(
            CapabilityItem mainHandCap,
            CapabilityItem offHandCap,
            Style style
    ) {
        return List.of();
    }

    protected int getNormalAttacksPerChain() {
        return 3;
    }

    protected int getGeneratedChainCount() {
        return 50;
    }

    protected void setAttackRadius(int attackRadius) {
        this.attackRadius = Math.max(0, attackRadius);
    }

    protected void setChasingSpeed(double chasingSpeed) {
        this.chasingSpeed = Math.max(0.0D, chasingSpeed);
    }

    protected boolean canPerformGeneratedAttack() {
        LivingEntity target = this.getTarget();
        return this.areCombatActionsAllowed()
                && !this.isStunned()
                && this.getEntityState().canBasicAttack()
                && target != null
                && target.isAlive()
                && this.getOriginal().distanceToSqr(target) <= GENERATED_ATTACK_RANGE * GENERATED_ATTACK_RANGE;
    }

    private AdvancedCombatBehaviors.BehaviorRoot.Builder<MobPatch<?>> createGeneratedAttackRoot(
            WeaponMoveset moveset,
            CapabilityItem mainHandCap,
            CapabilityItem offHandCap,
            Style style
    ) {
        AdvancedCombatBehaviors.BehaviorRoot.Builder<MobPatch<?>> root = AdvancedCombatBehaviors.BehaviorRoot.builder()
                .rootName("GeneratedWeaponCombo")
                .priority(1.0D)
                .weight(40.0D)
                .maxCooldown(20);
        List<AdditionalAttackGroup> additionalGroups = this.getAdditionalAttackGroups(mainHandCap, offHandCap, style);
        Random random = new Random(
                GENERATED_CHAIN_SEED
                        + moveset.normalAttacks().size() * 31L
                        + (moveset.dashAttack() == null ? 0L : 1L)
                        + (moveset.jumpAttack() == null ? 0L : 2L)
        );

        for (int combo = 0; combo < Math.max(1, this.getGeneratedChainCount()); combo++) {
            List<AnimationAccessor<? extends StaticAnimation>> chain = new ArrayList<>();
            appendNormalChain(chain, moveset.normalAttacks(), this.getNormalAttacksPerChain(), random);
            insertRandom(chain, moveset.pickMovementAttack(random), random);

            if (additionalGroups != null) {
                for (AdditionalAttackGroup group : additionalGroups) {
                    if (group != null && group.shouldSelect(random)) {
                        insertRandom(chain, group.pick(random), random);
                    }
                }
            }

            if (!chain.isEmpty()) {
                root.addFirstBehavior(combatChain(chain));
            }
        }
        return root;
    }

    private static void appendNormalChain(
            List<AnimationAccessor<? extends StaticAnimation>> chain,
            List<AnimationAccessor<? extends StaticAnimation>> normalAttacks,
            int normalAttacksPerChain,
            Random random
    ) {
        if (normalAttacks.isEmpty()) {
            return;
        }

        int start = random.nextInt(normalAttacks.size());
        int count = Math.min(Math.max(1, normalAttacksPerChain), normalAttacks.size());
        for (int index = 0; index < count; index++) {
            chain.add(normalAttacks.get((start + index) % normalAttacks.size()));
        }
    }

    private AdvancedCombatBehaviors.Behavior.Builder<MobPatch<?>> combatChain(
            List<AnimationAccessor<? extends StaticAnimation>> chain
    ) {
        AdvancedCombatBehaviors.Behavior.Builder<MobPatch<?>> builder = this.combatStep(
                chain.get(chain.size() - 1)
        );
        for (int index = chain.size() - 2; index >= 0; index--) {
            builder = this.combatStep(chain.get(index)).addNextBehavior(builder);
        }
        return builder;
    }

    private AdvancedCombatBehaviors.Behavior.Builder<MobPatch<?>> combatStep(
            AnimationAccessor<? extends StaticAnimation> animation
    ) {
        return AdvancedCombatBehaviors.Behavior.<MobPatch<?>>builder()
                .custom(ignored -> this.canPerformGeneratedAttack())
                .withinDistance(0.0D, GENERATED_ATTACK_RANGE)
                .animationBehavior(animation, 0.0F);
    }

    private static void insertRandom(
            List<AnimationAccessor<? extends StaticAnimation>> chain,
            AnimationAccessor<? extends StaticAnimation> animation,
            Random random
    ) {
        if (animation != null) {
            chain.add(chain.isEmpty() ? 0 : random.nextInt(chain.size() + 1), animation);
        }
    }

    private static List<AnimationAccessor<? extends StaticAnimation>> copyAnimations(
            Collection<? extends AnimationAccessor<? extends StaticAnimation>> animations
    ) {
        if (animations == null || animations.isEmpty()) {
            return List.of();
        }

        List<AnimationAccessor<? extends StaticAnimation>> copy = new ArrayList<>();
        for (AnimationAccessor<? extends StaticAnimation> animation : animations) {
            if (animation != null) {
                copy.add(animation);
            }
        }
        return List.copyOf(copy);
    }

    public final AdvancedCombatBehaviors<?> getAdvancedCombatBehaviors() {
        return this.combatBehaviors;
    }

    @Override
    public void updateHeldItem(
            CapabilityItem fromCap,
            CapabilityItem toCap,
            ItemStack from,
            ItemStack to,
            InteractionHand hand
    ) {
        super.updateHeldItem(fromCap, toCap, from, to, hand);
        if (hasSameCombatEquipment(from, to)) {
            return;
        }
        this.modifyLivingMotionByCurrentItem(!this.getOriginal().level().isClientSide());

        // Rebuild for real equipment/preset changes, never for hit durability or count.
        // Removing the attack goal here stops its animation and loses its combo root.
        if (!this.getOriginal().level().isClientSide()) {
            this.initAI();
        }
    }

    private static boolean hasSameCombatEquipment(ItemStack from, ItemStack to) {
        if (!ItemStack.isSameItem(from, to)) {
            return false;
        }
        ItemStack previous = from.copy();
        ItemStack current = to.copy();
        previous.remove(DataComponents.DAMAGE);
        current.remove(DataComponents.DAMAGE);
        return ItemStack.isSameItemSameComponents(previous, current);
    }

    /**
     * Returns the capability Smart NPC should use for Epic Fight combat. The actual
     * held ItemStack is never replaced; only its combat capability can be redirected.
     */
    protected CapabilityItem getRedirectedHoldingItemCapability(InteractionHand hand) {
        ItemStack heldStack = this.getOriginal().getItemInHand(hand);
        CapabilityItem originalCapability = super.getHoldingItemCapability(hand);
        return WeaponCapabilityRedirect.resolve(heldStack, originalCapability);
    }

    @Override
    public CapabilityItem getAdvancedHoldingItemCapability(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return this.getRedirectedHoldingItemCapability(hand);
        }
        return this.isOffhandItemValid()
                ? this.getRedirectedHoldingItemCapability(hand)
                : CapabilityItem.EMPTY;
    }

    @Override
    public boolean isOffhandItemValid() {
        return this.getRedirectedHoldingItemCapability(InteractionHand.MAIN_HAND).checkOffhandValid(this);
    }

    /**
     * Applies the active item capabilities' living-motion preset without relying on
     * HumanoidMobPatch. The animations registered by the concrete patch remain the
     * fallback for motions the capability does not replace.
     */
    public void modifyLivingMotionByCurrentItem(boolean resendPacket) {
        Animator animator = this.getAnimator();
        if (!this.defaultLivingMotionsCaptured) {
            this.defaultLivingMotions.putAll(animator.getLivingAnimations());
            this.defaultLivingMotionsCaptured = true;
        }

        Map<LivingMotion, AssetAccessor<? extends StaticAnimation>> livingMotions =
                new HashMap<>(this.defaultLivingMotions);
        this.applyCapabilityLivingMotions(
                livingMotions,
                this.getRedirectedHoldingItemCapability(InteractionHand.MAIN_HAND),
                InteractionHand.MAIN_HAND
        );
        this.applyCapabilityLivingMotions(
                livingMotions,
                this.getAdvancedHoldingItemCapability(InteractionHand.OFF_HAND),
                InteractionHand.OFF_HAND
        );

        animator.resetLivingAnimations();
        livingMotions.forEach(animator::addLivingAnimation);

        if (resendPacket && !this.getOriginal().level().isClientSide()) {
            SPChangeLivingMotion packet = new SPChangeLivingMotion(this.getOriginal().getId());
            packet.putEntries(livingMotions.entrySet());
            EpicFightNetworkManager.sendToAllPlayerTrackingThisEntity(packet, this.getOriginal());
        }
    }

    private void applyCapabilityLivingMotions(
            Map<LivingMotion, AssetAccessor<? extends StaticAnimation>> target,
            CapabilityItem capability,
            InteractionHand hand
    ) {
        Map<LivingMotion, AnimationAccessor<? extends StaticAnimation>> modifiers =
                capability.getLivingMotionModifier(this, hand);
        if (modifiers != null) {
            modifiers.forEach(target::put);
        }
    }

    public boolean canGuard() {
        return false;
    }

    public int getGuardChance() {
        return 0;
    }

    protected int getGuardDurationTicks() {
        return Mth.nextInt(
                this.getOriginal().getRandom(),
                MIN_GUARD_DURATION_TICKS,
                MAX_GUARD_DURATION_TICKS
        );
    }

    protected int getGuardCooldownTicks() {
        return DEFAULT_GUARD_COOLDOWN_TICKS;
    }

    protected int getGuardRetryTicks() {
        return DEFAULT_GUARD_RETRY_TICKS;
    }

    protected double getGuardDistance() {
        return DEFAULT_GUARD_DISTANCE;
    }

    public final boolean isGuardingLocally() {
        return this.guardingLocally;
    }

    public final void cancelGuard() {
        this.stopLocalGuard();
    }

    /**
     * Prevents generated attacks and combat chasing while an external compatibility
     * action owns this patch's animation. The owner identity makes releasing one
     * action unable to unlock another action that is still active.
     */
    public final void lockCombatActions(Object owner) {
        if (owner == null || !this.combatActionLocks.add(owner)) {
            return;
        }
        this.stopLocalGuard();
        if (this.combatBehaviors != null) {
            this.combatBehaviors.clearCurrentBehavior();
        }
        this.getOriginal().getNavigation().stop();
    }

    public final void unlockCombatActions(Object owner) {
        if (owner != null) {
            this.combatActionLocks.remove(owner);
        }
    }

    public final boolean isCombatActionLocked() {
        return !this.combatActionLocks.isEmpty();
    }

    /** Releases compatibility owners when stun interrupts their action. */
    private void interruptCombatActionsForStun() {
        // Later owner cleanup can still unlock safely: removal is idempotent.
        this.combatActionLocks.clear();
        this.stopLocalGuard();
        if (this.combatBehaviors != null) {
            this.combatBehaviors.clearCurrentBehavior();
        }
        this.getOriginal().getNavigation().stop();
    }

    private boolean areCombatActionsAllowed() {
        return this.isCombatEnabled() && this.staminaStatus != AdvancedStaminaStatus.BREAK
                && !this.isStunned() && !this.guardingLocally && !this.isCombatActionLocked()
                && !this.isUtilityActionActive();
    }

    public final float getStamina() {
        return this.stamina;
    }

    public final void setStamina(float stamina) {
        if (!Float.isFinite(stamina)) {
            return;
        }
        if (stamina < this.stamina) {
            this.lastActionTime = this.getOriginal().tickCount;
        }
        this.stamina = Mth.clamp(stamina, 0.0F, this.getMaxStamina());
        if (!this.isLogicalClient() && this.stamina <= 0.0F) {
            this.beginStaminaBreak(null);
        }
    }

    public final void addStamina(float amount) {
        this.setStamina(this.stamina + amount);
    }

    public final float getMaxStamina() {
        AttributeInstance maxStamina = this.getOriginal().getAttribute(EpicFightAttributes.MAX_STAMINA);
        return maxStamina == null ? 15.0F : (float) maxStamina.getValue();
    }

    public final AdvancedStaminaStatus getStaminaStatus() {
        return this.staminaStatus;
    }

    public boolean canBeExecuted(LivingEntityPatch<?> executorPatch) {
        if (executorPatch == null || executorPatch == this || !this.getOriginal().isAlive() || this.getAnimator() == null) {
            return false;
        }

        // Stamina is server-owned. The client uses the synchronized neutralize animation for its prompt.
        if (!this.isLogicalClient() && (this.staminaStatus != AdvancedStaminaStatus.BREAK || this.stamina > 0.0F)) {
            return false;
        }

        AnimationPlayer animationPlayer = this.getAnimator().getPlayerFor(null);
        AssetAccessor<? extends StaticAnimation> neutralize = this.getHitAnimation(StunType.NEUTRALIZE);
        return neutralize != null && neutralize != Animations.EMPTY_ANIMATION
                && animationPlayer != null && !animationPlayer.isEmpty() && !animationPlayer.isEnd()
                && animationPlayer.getRealAnimation() == neutralize;
    }

    private void tickLocalGuard() {
        if (this.getOriginal().level().isClientSide()) {
            return;
        }

        if (this.guardCooldownTicks > 0) {
            this.guardCooldownTicks--;
        }

        LivingEntity target = this.getOriginal().getTarget();
        if (this.guardingLocally) {
            if (!this.canContinueLocalGuard(target)) {
                this.stopLocalGuard();
                return;
            }

            this.getOriginal().getNavigation().stop();
            this.getOriginal().getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    private boolean tryStartLocalGuard() {
        if (this.getOriginal().level().isClientSide() || this.guardCooldownTicks > 0) {
            return false;
        }

        LivingEntity target = this.getOriginal().getTarget();
        if (!this.canStartLocalGuard(target)) {
            return false;
        }

        int chance = Mth.clamp(this.getGuardChance(), 0, 100);
        if (chance > 0 && this.getOriginal().getRandom().nextInt(100) < chance) {
            this.startLocalGuard();
            return true;
        } else {
            this.guardCooldownTicks = Math.max(1, this.getGuardRetryTicks());
            return false;
        }
    }

    private boolean canStartLocalGuard(LivingEntity target) {
        return this.canGuard()
                && !this.isCombatActionLocked()
                && !this.isUtilityActionActive()
                && this.isValidGuardTarget(target)
                && !this.isStunned()
                && !this.getEntityState().inaction()
                && this.staminaStatus == AdvancedStaminaStatus.COMMON
                && this.stamina > 0.0F
                && this.isAnimationIdle()
                && (this.combatBehaviors == null || this.combatBehaviors.getCurrentBehavior() == null);
    }

    private boolean isAnimationIdle() {
        var animationPlayer = this.getAnimator().getPlayerFor(null);
        return animationPlayer == null || animationPlayer.isEmpty();
    }

    private boolean canContinueLocalGuard(LivingEntity target) {
        return this.getOriginal().tickCount < this.guardEndTick
                && this.canGuard()
                && !this.isUtilityActionActive()
                && this.isValidGuardTarget(target)
                && !this.isStunned()
                && this.staminaStatus == AdvancedStaminaStatus.COMMON
                && this.stamina > 0.0F;
    }

    private boolean isValidGuardTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        double distance = Math.max(0.0D, this.getGuardDistance());
        return this.getOriginal().distanceToSqr(target) <= distance * distance;
    }

    private void startLocalGuard() {
        this.activeGuardAnimation = this.getLocalGuardAnimation();
        if (this.activeGuardAnimation == null) {
            return;
        }
        this.guardingLocally = true;
        this.guardEndTick = this.getOriginal().tickCount + Math.max(1, this.getGuardDurationTicks());
        this.getOriginal().getNavigation().stop();
        this.playAnimationSynchronized(this.activeGuardAnimation, 0.0F);
    }

    private void stopLocalGuard() {
        if (!this.guardingLocally) {
            return;
        }

        this.guardingLocally = false;
        this.guardEndTick = 0;
        this.guardCooldownTicks = Math.max(this.guardCooldownTicks, Math.max(0, this.getGuardCooldownTicks()));

        AssetAccessor<? extends StaticAnimation> guardAnimation = this.activeGuardAnimation == null
                ? this.getLocalGuardAnimation()
                : this.activeGuardAnimation;
        this.activeGuardAnimation = null;
        if (guardAnimation == null) {
            return;
        }
        if (this.isLogicalClient()) {
            this.getAnimator().stopPlaying(guardAnimation);
        } else {
            this.stopPlaying(guardAnimation);
        }
    }

    protected AssetAccessor<? extends StaticAnimation> getLocalGuardAnimation() {
        AnimationAccessor<? extends StaticAnimation> mainHandGuard = this.getCapabilityGuardAnimation(
                InteractionHand.MAIN_HAND
        );
        if (mainHandGuard != null) {
            return mainHandGuard;
        }

        AnimationAccessor<? extends StaticAnimation> offHandGuard = this.getCapabilityGuardAnimation(
                InteractionHand.OFF_HAND
        );
        if (offHandGuard != null) {
            return offHandGuard;
        }

        return null;
    }

    private AnimationAccessor<? extends StaticAnimation> getCapabilityGuardAnimation(InteractionHand hand) {
        if (this.getOriginal().getItemInHand(hand).isEmpty()) {
            return null;
        }
        CapabilityItem capability = this.getAdvancedHoldingItemCapability(hand);
        if (capability.isEmpty() || capability.getWeaponCategory() == WeaponCategories.FIST) {
            return null;
        }
        Map<LivingMotion, AnimationAccessor<? extends StaticAnimation>> livingMotions =
                capability.getLivingMotionModifier(this, hand);
        if (livingMotions == null) {
            return null;
        }
        return livingMotions.getOrDefault(LivingMotions.BLOCK, livingMotions.get(LivingMotions.BLOCK_SHIELD));
    }

    public void playGuardBreakSound() {
        this.playSound(EpicFightSounds.NEUTRALIZE_MOBS.get(), 1.0F, 1.0F);
    }

    public void playGuardHitSound() {
        this.playSound(EpicFightSounds.CLASH.get(), 1.0F, 1.0F);
    }

    public AnimationAccessor<? extends StaticAnimation> getGuardHitAnimation(DamageSource damageSource) {
        CapabilityItem mainHandCap = this.getRedirectedHoldingItemCapability(InteractionHand.MAIN_HAND);
        if (mainHandCap.getWeaponCategory() == WeaponCategories.SWORD) {
            return mainHandCap.getStyle(this) == Styles.TWO_HAND
                    ? Animations.SWORD_DUAL_GUARD_HIT
                    : this.getOriginal().getRandom().nextBoolean()
                    ? Animations.SWORD_GUARD_ACTIVE_HIT1
                    : Animations.SWORD_GUARD_ACTIVE_HIT2;
        }
        return Animations.EMPTY_ANIMATION;
    }

    public void playGuardHitAnimation(DamageSource damageSource, boolean canCounter) {
        if (this.getOriginal().level() instanceof ServerLevel serverLevel) {
            Vec3 position = this.getOriginal().getEyePosition()
                    .add(this.getOriginal().getLookAngle().normalize().scale(1.25D));
            serverLevel.sendParticles(EpicFightParticles.HIT_BLUNT.get(), position.x, position.y, position.z,
                    1, 0.0D, 0.0D, 0.0D, 1.0D);
        }
        this.playAnimationSynchronized(this.getGuardHitAnimation(damageSource), 0.0F);
        this.playGuardHitSound();
    }

    public boolean dealStaminaDamage(DamageSource damageSource, float amount) {
        if (this.isLogicalClient() || !Float.isFinite(amount) || amount <= 0.0F
                || this.staminaStatus != AdvancedStaminaStatus.COMMON) {
            return false;
        }

        this.lastActionTime = this.getOriginal().tickCount;
        this.stamina = Math.max(0.0F, this.stamina - amount);
        if (this.stamina > 0.0F) {
            return false;
        }

        this.beginStaminaBreak(damageSource);
        return true;
    }

    private void beginStaminaBreak(DamageSource damageSource) {
        if (this.staminaStatus != AdvancedStaminaStatus.COMMON) {
            return;
        }

        this.staminaStatus = AdvancedStaminaStatus.BREAK;
        this.recoverTickCount = 0;
        this.interruptCombatActionsForStun();

        // The triggering hit must not replace neutralize with its ordinary hit stun.
        if (damageSource instanceof EpicFightDamageSource source) {
            source.setStunType(StunType.NONE);
            source.addRuntimeTag(EpicFightDamageTypeTags.NO_STUN);
        }
        if (damageSource != null && damageSource.getSourcePosition() != null) {
            this.getOriginal().lookAt(EntityAnchorArgument.Anchor.FEET, damageSource.getSourcePosition());
        }
        if (super.applyStun(StunType.NEUTRALIZE, 0.0F)) {
            this.stunnedLastTick = true;
            this.playGuardBreakSound();
            if (this.getOriginal().level() instanceof ServerLevel serverLevel) {
                Vec3 position = this.getOriginal().getEyePosition().add(this.getOriginal().getLookAngle().scale(2.0D));
                serverLevel.sendParticles(EpicFightParticles.NEUTRALIZE.get(), position.x, position.y, position.z,
                        1, 0.0D, 0.0D, 0.0D, 0.0D);
            }
        }
    }

    @Override
    public boolean applyStun(StunType stunType, float stunTime) {
        // Match CE's break/recovery stun protection without requiring its effects.
        // Execution animations use playAnimationSynchronized and remain available.
        if (this.staminaStatus != AdvancedStaminaStatus.COMMON) {
            return false;
        }
        boolean applied = super.applyStun(stunType, stunTime);
        if (applied) {
            this.interruptCombatActionsForStun();
            this.stunnedLastTick = true;
        }
        return applied;
    }

    public void onGuardHit(DamageSource damageSource) {
        EpicFightDamageSource epicFightDamageSource = damageSource instanceof EpicFightDamageSource source ? source : null;
        float impact = epicFightDamageSource == null ? 0.5F : epicFightDamageSource.calculateImpact();
        if (this.dealStaminaDamage(damageSource, impact)) {
            return;
        }

        this.stopLocalGuard();
        this.playGuardHitAnimation(damageSource, false);

        if (this.getOriginal().level() instanceof ServerLevel serverLevel) {
            EpicFightParticles.HIT_BLUNT.get().spawnParticleWithArgument(
                    serverLevel,
                    HitParticleType.FRONT_OF_EYES,
                    HitParticleType.ZERO,
                    this.getOriginal(),
                    damageSource.getEntity()
            );
        }
    }

    @Override
    public AttackResult tryHurt(DamageSource damageSource, float amount) {
        AttackResult result = super.tryHurt(damageSource, amount);
        if (!damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY) && this.combatBehaviors != null) {
            result = this.combatBehaviors.executeCurrentBehaviorOnHurt(this, damageSource, result);
        }

        if (damageSource.getDirectEntity() == null
                || !this.guardingLocally
                || damageSource.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || damageSource.is(EpicFightDamageTypeTags.UNBLOCKALBE)
                || damageSource.is(EpicFightDamageTypeTags.GUARD_PUNCTURE)) {
            return result;
        }

        if (result.resultType == ResultType.SUCCESS) {
            this.onGuardHit(damageSource);
            Entity attacker = damageSource.getEntity() == null ? damageSource.getDirectEntity() : damageSource.getEntity();
            LivingEntityPatch<?> attackerPatch = EpicFightCapabilities.getEntityPatch(attacker, LivingEntityPatch.class);
            if (attackerPatch != null) {
                attackerPatch.onAttackBlocked(damageSource, this);
            }
        }
        return AttackResult.blocked(0.0F);
    }

    @Override
    public AnimationAccessor<? extends StaticAnimation> getHitAnimation(StunType stunType) {
        return switch (stunType) {
            case LONG -> Animations.BIPED_HIT_LONG;
            case SHORT, HOLD -> Animations.BIPED_HIT_SHORT;
            case KNOCKDOWN -> Animations.BIPED_KNOCKDOWN;
            case NEUTRALIZE -> this.getRedirectedHoldingItemCapability(InteractionHand.MAIN_HAND).getWeaponCategory()
                    == WeaponCategories.GREATSWORD
                    ? Animations.GREATSWORD_GUARD_BREAK
                    : Animations.BIPED_COMMON_NEUTRALIZED;
            case FALL -> Animations.BIPED_LANDING;
            default -> null;
        };
    }

    public record WeaponMoveset(
            List<AnimationAccessor<? extends StaticAnimation>> normalAttacks,
            AnimationAccessor<? extends StaticAnimation> dashAttack,
            AnimationAccessor<? extends StaticAnimation> jumpAttack
    ) {
        public WeaponMoveset {
            normalAttacks = normalAttacks == null ? List.of() : List.copyOf(normalAttacks);
        }

        public static WeaponMoveset empty() {
            return new WeaponMoveset(List.of(), null, null);
        }

        public boolean isEmpty() {
            return this.normalAttacks.isEmpty() && this.dashAttack == null && this.jumpAttack == null;
        }

        private AnimationAccessor<? extends StaticAnimation> pickMovementAttack(Random random) {
            if (this.dashAttack == null) {
                return this.jumpAttack;
            }
            if (this.jumpAttack == null) {
                return this.dashAttack;
            }
            return random.nextBoolean() ? this.dashAttack : this.jumpAttack;
        }
    }

    public enum AdditionalAttackMode {
        FORCED,
        RANDOM
    }

    public static final class AdditionalAttackGroup {
        private static final float DEFAULT_RANDOM_CHANCE = 0.35F;

        private final AdditionalAttackMode mode;
        private final float chance;
        private final List<AnimationAccessor<? extends StaticAnimation>> animations;

        private AdditionalAttackGroup(
                AdditionalAttackMode mode,
                float chance,
                Collection<? extends AnimationAccessor<? extends StaticAnimation>> animations
        ) {
            this.mode = mode;
            this.chance = Math.max(0.0F, Math.min(1.0F, chance));
            this.animations = copyAnimations(animations);
        }

        @SafeVarargs
        public static AdditionalAttackGroup forced(AnimationAccessor<? extends StaticAnimation>... animations) {
            return new AdditionalAttackGroup(AdditionalAttackMode.FORCED, 1.0F, Arrays.asList(animations));
        }

        @SafeVarargs
        public static AdditionalAttackGroup random(AnimationAccessor<? extends StaticAnimation>... animations) {
            return random(DEFAULT_RANDOM_CHANCE, animations);
        }

        @SafeVarargs
        public static AdditionalAttackGroup random(
                float chance,
                AnimationAccessor<? extends StaticAnimation>... animations
        ) {
            return new AdditionalAttackGroup(AdditionalAttackMode.RANDOM, chance, Arrays.asList(animations));
        }

        private boolean shouldSelect(Random random) {
            return !this.animations.isEmpty()
                    && (this.mode == AdditionalAttackMode.FORCED || random.nextFloat() < this.chance);
        }

        private AnimationAccessor<? extends StaticAnimation> pick(Random random) {
            return this.animations.get(random.nextInt(this.animations.size()));
        }
    }
}
