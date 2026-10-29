package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

public final class BreakingBlockAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private static final int HIT_SOUND_INTERVAL_TICKS = 8;
    private static final int ATTACK_ANIMATION_INTERVAL_TICKS = 10;
    private static final int MAX_REQUIRED_BREAK_TICKS = 20 * 30;
    private static final int POST_BREAK_DELAY_TICKS = 5;
    private static final float MINING_SNEAK_CHANCE = 0.12F;
    private static final int MINING_SNEAK_MIN_TICKS = 20;
    private static final int MINING_SNEAK_RANDOM_TICKS = 35;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final SneakingAi sneakingAi;
    private BlockPos targetPos;
    private int breakTicks;
    private int requiredTicks;
    private int nextBreakStartTick;
    private String detail = "breaking block";
    private String toolDetail = "";
    private boolean paused;

    public BreakingBlockAi(PlayerNpcEntity playerNpc, ToolAi toolAi) {
        this.playerNpc = playerNpc;
        this.toolAi = toolAi;
        this.sneakingAi = new SneakingAi(playerNpc);
    }

    public boolean isRunning() {
        return this.targetPos != null;
    }

    public BlockPos targetPos() {
        return this.targetPos;
    }

    public TickResult tick(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            int requiredTicks,
            String detail
    ) {
        return this.tick(serverLevel, targetPos, targetPredicate, requiredTicks, detail, false);
    }

    public TickResult tick(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            int requiredTicks,
            String detail,
            boolean allowBlockEntity
    ) {
        return this.tick(serverLevel, targetPos, targetPredicate, requiredTicks, detail, allowBlockEntity, false);
    }

    public TickResult tick(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            int requiredTicks,
            String detail,
            boolean allowBlockEntity,
            boolean allowOwnedFarmDestruction
    ) {
        if (targetPos == null
                || targetPredicate == null
                || !serverLevel.hasChunkAt(targetPos)) {
            this.stop();
            return TickResult.FAILED;
        }

        BlockState state = serverLevel.getBlockState(targetPos);
        if (!targetPredicate.test(state)) {
            this.stop();
            return TickResult.DONE;
        }
        if (!this.canBreak(serverLevel, targetPos, state, allowBlockEntity, allowOwnedFarmDestruction)) {
            this.stop();
            return TickResult.FAILED;
        }

        this.toolAi.equipBestToolFor(state);
        this.toolDetail = this.toolAi.hasPreferredToolFor(state) ? "" : " without preferred tool";
        this.requiredTicks = requiredBreakTicks(serverLevel, targetPos, state, this.playerNpc);
        if (!targetPos.equals(this.targetPos)) {
            this.start(targetPos, this.requiredTicks, detail);
        }
        if (this.breakTicks <= 0 && this.playerNpc.tickCount < this.nextBreakStartTick) {
            this.updateDetail();
            return TickResult.RUNNING;
        }
        this.playerNpc.getLookControl().setLookAt(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 0.5D,
                targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        this.paused = false;
        this.playerNpc.getSocialNoticeAi().trackBreaking(this);
        this.sneakingAi.tickHeldSneak();
        this.breakTicks++;

//        if (ModList.get().isLoaded("epicfight")) {
//            EpicFight.keepDiggingState(this.playerNpc);
//        }
        this.tickMiningSwing();
        this.playerNpc.showBlockBreakProgress(targetPos, this.breakTicks, this.requiredTicks);
        if (this.breakTicks % HIT_SOUND_INTERVAL_TICKS == 0) {
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, targetPos, state, this.playerNpc);
        }

        if (this.breakTicks < this.requiredTicks) {
            this.updateDetail();
            return TickResult.RUNNING;
        }

        boolean destroyed = PlayerNpcBlockBreakUtil.destroyBlock(
                serverLevel,
                targetPos,
                state,
                this.playerNpc,
                allowOwnedFarmDestruction
        );
        if (destroyed) {
            this.playerNpc.hurtMainHandItem(1);
            this.nextBreakStartTick = this.playerNpc.tickCount + POST_BREAK_DELAY_TICKS;
        }
        this.stop();
        return destroyed ? TickResult.DONE : TickResult.FAILED;
    }

    public static int requiredBreakTicks(ServerLevel serverLevel, BlockPos pos, BlockState state, ItemStack heldStack) {
        if (state == null) {
            return MAX_REQUIRED_BREAK_TICKS;
        }

        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_REQUIRED_BREAK_TICKS;
        }
        if (hardness == 0.0F) {
            return 1;
        }

        ItemStack stack = heldStack == null ? ItemStack.EMPTY : heldStack;
        float toolSpeed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_REQUIRED_BREAK_TICKS;
        }
        return Math.min(MAX_REQUIRED_BREAK_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    public static int requiredBreakTicks(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        if (state == null || playerNpc == null) {
            return MAX_REQUIRED_BREAK_TICKS;
        }

        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_REQUIRED_BREAK_TICKS;
        }
        if (hardness == 0.0F) {
            return 1;
        }

        ItemStack stack = playerNpc.getMainHandItem();
        boolean correctTool = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        float progressPerTick = destroySpeedFor(playerNpc, state) / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_REQUIRED_BREAK_TICKS;
        }
        return Math.min(MAX_REQUIRED_BREAK_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private static float destroySpeedFor(PlayerNpcEntity playerNpc, BlockState state) {
        ItemStack stack = playerNpc.getMainHandItem();
        float speed = stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state);
        if (speed <= 0.0F) {
            speed = 1.0F;
        }

        if (speed > 1.0F && !stack.isEmpty()) {
            speed += (float) playerNpc.getAttributeValue(Attributes.MINING_EFFICIENCY);
        }

        MobEffectInstance haste = playerNpc.getEffect(MobEffects.HASTE);
        if (haste != null) {
            speed *= 1.0F + (float) (haste.getAmplifier() + 1) * 0.2F;
        }

        MobEffectInstance fatigue = playerNpc.getEffect(MobEffects.MINING_FATIGUE);
        if (fatigue != null) {
            speed *= miningFatigueMultiplier(fatigue.getAmplifier());
        }

        if (playerNpc.isEyeInFluid(FluidTags.WATER)) {
            speed *= (float) playerNpc.getAttributeValue(Attributes.SUBMERGED_MINING_SPEED);
        }
        if (!playerNpc.onGround()) {
            speed /= 5.0F;
        }
        return speed;
    }

    private static float miningFatigueMultiplier(int amplifier) {
        return switch (amplifier) {
            case 0 -> 0.3F;
            case 1 -> 0.09F;
            case 2 -> 0.0027F;
            default -> 8.1E-4F;
        };
    }

    /** Suspend visible mining without discarding progress while a caller revalidates its ray. */
    public void pause() {
        if (this.targetPos == null || this.paused) {
            return;
        }
        this.paused = true;
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.sneakingAi.stopSneaking();
//        if (ModList.get().isLoaded("epicfight")) {
//            EpicFight.stopDiggingAnimation(this.playerNpc);
//        }
    }

    public void stop() {
        this.playerNpc.getSocialNoticeAi().releaseBreaking(this);
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.sneakingAi.stopSneaking();
        this.targetPos = null;
        this.breakTicks = 0;
        this.requiredTicks = 0;
        this.detail = "breaking block";
        this.toolDetail = "";
        this.paused = false;
//        if (ModList.get().isLoaded("epicfight")) {
//            EpicFight.stopDiggingAnimation(this.playerNpc);
//        }
    }

    public String detail() {
        if (this.targetPos == null) {
            return "";
        }
        String progress = this.requiredTicks > 0
                ? " " + Math.min(this.breakTicks, this.requiredTicks) + "/" + this.requiredTicks + "t"
                : "";
        return this.detail + this.toolDetail + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ()
                + progress;
    }

    private void start(BlockPos targetPos, int requiredTicks, String detail) {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.targetPos = targetPos.immutable();
        this.breakTicks = 0;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.detail = detail == null || detail.isBlank() ? "breaking block" : detail;
        this.playerNpc.getNavigation().stop();
        this.sneakingAi.rollHeldSneak(
                this.playerNpc.getRandom(),
                MINING_SNEAK_CHANCE,
                MINING_SNEAK_MIN_TICKS,
                MINING_SNEAK_RANDOM_TICKS
        );
        this.updateDetail();
    }

    private boolean canBreak(
            ServerLevel serverLevel,
            BlockPos targetPos,
            BlockState state,
            boolean allowBlockEntity,
            boolean allowOwnedFarmDestruction
    ) {
        return serverLevel.isInWorldBounds(targetPos)
                && serverLevel.getWorldBorder().isWithinBounds(targetPos)
                && (allowOwnedFarmDestruction
                || !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, targetPos))
                && state.getDestroySpeed(serverLevel, targetPos) >= 0.0F
                && state.getFluidState().isEmpty()
                && (allowBlockEntity || serverLevel.getBlockEntity(targetPos) == null);
    }

    private void updateDetail() {
        String currentDetail = this.detail();
        if (!currentDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(currentDetail);
        }
    }

    private void tickMiningSwing() {
        if (this.breakTicks != 1 && this.breakTicks % ATTACK_ANIMATION_INTERVAL_TICKS != 0) {
            return;
        }

        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.playerNpc.triggerMainHandAttackAnimation();

//        if (ModList.get().isLoaded("epicfight")) {
//            EpicFight.playDiggingAnimation(this.playerNpc);
//        }
    }
}
