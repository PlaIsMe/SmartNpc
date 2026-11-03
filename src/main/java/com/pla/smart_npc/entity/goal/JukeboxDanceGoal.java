package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;

public class JukeboxDanceGoal extends Goal {
    private static final int JUKEBOX_SCAN_RADIUS = 12;
    private static final double DANCE_DISTANCE_SQR = 5.0D * 5.0D;
    private static final int MIN_DANCE_TICKS = 80;
    private static final int MAX_DANCE_TICKS = 180;
    private static final int COOLDOWN_TICKS = 20 * 70;
    private static final int CAN_USE_CHECK_INTERVAL_TICKS = 40;
    private static final int REPATH_INTERVAL_TICKS = 20;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(CAN_USE_CHECK_INTERVAL_TICKS);
    private final double speed;
    private Action action = Action.NONE;
    private BlockPos jukeboxPos;
    private PlayerNpcEntity dancerToDisturb;
    private int danceTicks;
    private int repathTicks;

    public JukeboxDanceGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getJukeboxDanceCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.dancerToDisturb = this.findDancingNpc();
        if (this.dancerToDisturb != null && this.playerNpc.getRandom().nextFloat() < 0.012F) {
            this.action = Action.DISTURB;
            return true;
        }

        this.jukeboxPos = this.findPlayingJukebox(serverLevel);
        if (this.jukeboxPos != null && this.playerNpc.getRandom().nextFloat() < 0.35F) {
            this.action = Action.DANCE;
            return true;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        BlockPos homeJukebox = this.findHomeJukebox(serverLevel, home.get());
        if (homeJukebox == null
                && InventoryUtils.hasItem(this.playerNpc, Items.JUKEBOX)
                && this.hasMusicDisc()
                && this.findJukeboxPlacement(serverLevel, home.get()) != null) {
            this.action = Action.PLACE_JUKEBOX;
            return true;
        }

        if (homeJukebox != null
                && !this.hasRecord(serverLevel, homeJukebox)
                && this.hasMusicDisc()) {
            this.jukeboxPos = homeJukebox;
            this.action = Action.INSERT_DISC;
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.action == Action.DANCE
                && this.danceTicks > 0
                && this.jukeboxPos != null
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.hasRecord(serverLevel, this.jukeboxPos);
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        switch (this.action) {
            case PLACE_JUKEBOX -> this.placeHomeJukebox(serverLevel);
            case INSERT_DISC -> this.insertDisc(serverLevel);
            case DISTURB -> this.disturbDance();
            case DANCE -> this.startDancing();
            default -> {
            }
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.jukeboxPos == null
                || this.action != Action.DANCE) {
            return;
        }

        this.danceTicks--;
        this.playerNpc.setDancing(true);
        this.playerNpc.getLookControl().setLookAt(this.jukeboxPos.getX() + 0.5D, this.jukeboxPos.getY() + 0.5D, this.jukeboxPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(this.jukeboxPos.getX() + 0.5D, this.jukeboxPos.getY(), this.jukeboxPos.getZ() + 0.5D) > DANCE_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0) {
                this.moveToJukebox();
            }
            return;
        }

        boolean sneaking = (this.danceTicks / 7) % 2 == 0;
        this.playerNpc.setShiftKeyDown(sneaking);
        this.playerNpc.setPose(sneaking ? Pose.CROUCHING : Pose.STANDING);
        if (this.danceTicks % 18 == 0 && this.playerNpc.onGround()) {
            this.playerNpc.jump();
        }
        if (this.danceTicks % 28 == 0) {
            this.moveAroundJukebox(serverLevel);
        } else if (this.playerNpc.getNavigation().isDone()) {
            this.playerNpc.getNavigation().stop();
        }
    }

    @Override
    public void stop() {
        if (this.action == Action.DANCE) {
            this.playerNpc.setDancing(false);
            this.playerNpc.setShiftKeyDown(false);
            this.playerNpc.setPose(Pose.STANDING);
            this.playerNpc.setJukeboxDanceCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 90));
        }
        this.action = Action.NONE;
        this.jukeboxPos = null;
        this.dancerToDisturb = null;
        this.danceTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void placeHomeJukebox(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            this.finishInstantAction();
            return;
        }

        BlockPos placement = this.findJukeboxPlacement(serverLevel, home.get());
        ItemStack jukebox = this.playerNpc.consumeInventoryItem(Items.JUKEBOX, 1).orElse(ItemStack.EMPTY);
        if (placement != null && !jukebox.isEmpty()) {
            if (this.placingBlockAi.placeBlock(serverLevel, placement, Blocks.JUKEBOX.defaultBlockState())) {
                this.playerNpc.getLookControl().setLookAt(placement.getX() + 0.5D, placement.getY() + 0.5D, placement.getZ() + 0.5D, 40.0F, 40.0F);
                this.playerNpc.setCurrentAiState("ai.player_npc.setting_up_jukebox");
            } else if (!InventoryUtils.addItem(this.playerNpc, jukebox)) {
                this.playerNpc.spawnAtLocation(jukebox);
            }
        } else if (!jukebox.isEmpty() && !InventoryUtils.addItem(this.playerNpc, jukebox)) {
            this.playerNpc.spawnAtLocation(jukebox);
        }
        this.finishInstantAction();
    }

    private void insertDisc(ServerLevel serverLevel) {
        if (this.jukeboxPos == null || !(serverLevel.getBlockEntity(this.jukeboxPos) instanceof JukeboxBlockEntity jukebox)) {
            this.finishInstantAction();
            return;
        }

        ItemStack disc = this.playerNpc.consumeInventoryItem(stack -> stack.has(DataComponents.JUKEBOX_PLAYABLE), 1).orElse(ItemStack.EMPTY);
        if (disc.isEmpty()) {
            this.finishInstantAction();
            return;
        }

        disc.setCount(1);
        jukebox.setTheItem(disc.copy());
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.playerNpc.getLookControl().setLookAt(this.jukeboxPos.getX() + 0.5D, this.jukeboxPos.getY() + 0.5D, this.jukeboxPos.getZ() + 0.5D, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.setting_up_jukebox");
        this.finishInstantAction();
    }

    private void disturbDance() {
        if (this.dancerToDisturb != null && this.dancerToDisturb.isAlive()) {
            this.playerNpc.setTarget(this.dancerToDisturb);
            this.playerNpc.setCurrentAiState("ai.player_npc.disturbing_dance");
            this.playerNpc.setCurrentAiDetail(this.dancerToDisturb.getDisplayName().getString());
        }
        this.finishInstantAction();
    }

    private void startDancing() {
        this.danceTicks = MIN_DANCE_TICKS + this.playerNpc.getRandom().nextInt(MAX_DANCE_TICKS - MIN_DANCE_TICKS + 1);
        this.repathTicks = 0;
        this.playerNpc.setDancing(true);
        this.playerNpc.setCurrentAiState("ai.player_npc.dancing");
        if (this.jukeboxPos != null) {
            this.playerNpc.setCurrentAiDetail(this.jukeboxPos.getX() + " " + this.jukeboxPos.getY() + " " + this.jukeboxPos.getZ());
            this.moveToJukebox();
        }
    }

    private void moveToJukebox() {
        if (this.jukeboxPos == null) {
            return;
        }
        this.playerNpc.getNavigation().moveTo(
                this.jukeboxPos.getX() + 0.5D,
                this.jukeboxPos.getY(),
                this.jukeboxPos.getZ() + 0.5D,
                this.speed
        );
        this.repathTicks = REPATH_INTERVAL_TICKS;
    }

    private void finishInstantAction() {
        this.playerNpc.setJukeboxDanceCooldown(20 * 20 + this.playerNpc.getRandom().nextInt(20 * 40));
        this.action = Action.NONE;
        this.jukeboxPos = null;
        this.dancerToDisturb = null;
    }

    private PlayerNpcEntity findDancingNpc() {
        return this.playerNpc.level()
                .getEntitiesOfClass(PlayerNpcEntity.class, this.playerNpc.getBoundingBox().inflate(JUKEBOX_SCAN_RADIUS), entity -> entity != this.playerNpc
                        && entity.isAlive()
                        && entity.isDancing()
                        && !this.playerNpc.isTeamAlliedWith(entity))
                .stream()
                .min(Comparator.comparingDouble(this.playerNpc::distanceToSqr))
                .orElse(null);
    }

    private BlockPos findPlayingJukebox(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-JUKEBOX_SCAN_RADIUS, -3, -JUKEBOX_SCAN_RADIUS), center.offset(JUKEBOX_SCAN_RADIUS, 3, JUKEBOX_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (serverLevel.getBlockState(immutable).is(Blocks.JUKEBOX) && this.hasRecord(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findHomeJukebox(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea home) {
        for (BlockPos pos : BlockPos.betweenClosed(home.origin(), home.origin().offset(home.width() - 1, 3, home.depth() - 1))) {
            BlockPos immutable = pos.immutable();
            if (serverLevel.getBlockState(immutable).is(Blocks.JUKEBOX)) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findJukeboxPlacement(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea home) {
        BlockPos preferred = PlayerNpcHomeUtil.interiorPos(home, Math.max(1, home.width() / 2), Math.max(1, home.depth() / 2));
        if (this.canPlaceAt(serverLevel, home, preferred)) {
            return preferred;
        }

        for (int x = 1; x < home.width() - 1; x++) {
            for (int z = 1; z < home.depth() - 1; z++) {
                BlockPos pos = PlayerNpcHomeUtil.interiorPos(home, x, z);
                if (this.canPlaceAt(serverLevel, home, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private boolean canPlaceAt(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea home, BlockPos pos) {
        return PlayerNpcHomeUtil.isInside(home, pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private boolean hasRecord(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.getBlockState(pos).is(Blocks.JUKEBOX)
                && serverLevel.getBlockState(pos).getValue(JukeboxBlock.HAS_RECORD);
    }

    private boolean hasMusicDisc() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.has(DataComponents.JUKEBOX_PLAYABLE));
    }

    private void moveAroundJukebox(ServerLevel serverLevel) {
        if (this.jukeboxPos == null) {
            return;
        }

        for (int attempt = 0; attempt < 6; attempt++) {
            int xOffset = this.playerNpc.getRandom().nextInt(5) - 2;
            int zOffset = this.playerNpc.getRandom().nextInt(5) - 2;
            if (xOffset == 0 && zOffset == 0) {
                continue;
            }
            BlockPos dancePos = this.jukeboxPos.offset(xOffset, 0, zOffset);
            if (this.canDanceAt(serverLevel, dancePos)) {
                this.playerNpc.getNavigation().moveTo(dancePos.getX() + 0.5D, dancePos.getY(), dancePos.getZ() + 0.5D, Math.min(this.speed, 0.55D));
                return;
            }
        }
        this.playerNpc.getNavigation().stop();
    }

    private boolean canDanceAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender();
    }

    private enum Action {
        NONE,
        PLACE_JUKEBOX,
        INSERT_DISC,
        DANCE,
        DISTURB
    }
}
