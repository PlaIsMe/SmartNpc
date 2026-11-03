package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** A bounded, non-worker invitation accepted through reciprocal crouching. */
public final class TeamUpGoal extends Goal {
    private static final int INVITATION_CHECK_INTERVAL_TICKS = 20 * 5;
    private static final int RETRY_COOLDOWN_TICKS = 20 * 30;
    private static final int NPC_REPLY_PHASE_TICKS = 4;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(INVITATION_CHECK_INTERVAL_TICKS);
    @Nullable
    private LivingEntity inviteTarget;
    private int waitTicks;
    private int gestureTicks;
    private int offerTicks;
    private int offerCrouches;
    private int swingTick;
    private int jumpTick;
    private boolean invitationStarted;
    private boolean lastResponseCrouching;
    private int responsePresses;
    private boolean responseReady;

    public TeamUpGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.TEAMUP)
                || this.playerNpc.isTeamFollower()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.isTeamUpRequestPending()
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        this.inviteTarget = PlayerNpcTeamUpManager.findInviteCandidate(this.playerNpc);
        return this.inviteTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.invitationStarted
                && this.waitTicks > 0
                && this.inviteTarget != null
                && PlayerNpcTeamUpManager.canContinueInvitation(this.playerNpc, this.inviteTarget);
    }

    @Override
    public void start() {
        if (this.inviteTarget == null) {
            return;
        }
        this.offerTicks = 40 + this.playerNpc.getRandom().nextInt(21);
        this.offerCrouches = 5 + this.playerNpc.getRandom().nextInt(6);
        this.waitTicks = this.offerTicks + 40 + this.playerNpc.getRandom().nextInt(21);
        this.swingTick = this.playerNpc.getRandom().nextFloat() < 0.35F ? 8 + this.playerNpc.getRandom().nextInt(this.offerTicks - 16) : -1;
        this.jumpTick = this.playerNpc.getRandom().nextFloat() < 0.20F ? this.offerTicks / 2 : -1;
        this.gestureTicks = 0;
        this.responsePresses = 0;
        this.responseReady = false;
        this.lastResponseCrouching = this.inviteTarget.isShiftKeyDown();
        this.invitationStarted = PlayerNpcTeamUpManager.beginInvitation(this.playerNpc, this.inviteTarget,
                this.playerNpc.getServer().getTickCount() + this.waitTicks);
        if (!this.invitationStarted) {
            this.waitTicks = 0;
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setCurrentAiState("ai.player_npc.awaiting_teamup_reply");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (this.inviteTarget == null || !PlayerNpcTeamUpManager.canContinueInvitation(this.playerNpc, this.inviteTarget)) {
            this.waitTicks = 0;
            return;
        }
        this.waitTicks--;
        this.gestureTicks++;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.inviteTarget, 35.0F, 35.0F);
        boolean offering = this.gestureTicks < this.offerTicks;
        int phase = this.gestureTicks * (this.offerCrouches * 2) / this.offerTicks;
        this.playerNpc.setShiftKeyDown(offering && phase % 2 == 0);
        if (this.gestureTicks == this.swingTick) {
            // Gesture only: never call attack, use, interact or damage APIs.
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        }
        if (this.gestureTicks == this.jumpTick && this.playerNpc.onGround()
                && this.playerNpc.distanceToSqr(this.inviteTarget) <= 4.0D
                && !this.playerNpc.isInWater() && !this.playerNpc.isInLava()) {
            this.playerNpc.getJumpControl().jump();
        }
        if (this.gestureTicks == this.offerTicks) {
            this.updateDetail();
        }
        if (this.inviteTarget instanceof PlayerNpcEntity npcTarget) {
            npcTarget.getNavigation().stop();
            npcTarget.getLookControl().setLookAt(this.playerNpc, 35.0F, 35.0F);
            int responseTick = this.gestureTicks - this.offerTicks - 4;
            npcTarget.setShiftKeyDown(responseTick >= 0 && responseTick < NPC_REPLY_PHASE_TICKS * 4
                    && (responseTick / NPC_REPLY_PHASE_TICKS) % 2 == 0);
        }
        this.observeResponseGesture();
        if (!offering && this.responseReady) {
            boolean accepted = this.inviteTarget instanceof PlayerNpcEntity npcTarget
                    ? PlayerNpcTeamUpManager.acceptNpcResponse(this.playerNpc, npcTarget)
                    : this.inviteTarget instanceof ServerPlayer player
                    && PlayerNpcTeamUpManager.acceptPlayerGesture(this.playerNpc, player);
            if (accepted) {
                this.waitTicks = 0;
            }
        }
    }

    @Override
    public void stop() {
        PlayerNpcTeamUpManager.cancelInvitation(this.playerNpc, this.inviteTarget);
        if (!this.playerNpc.isTeamFollower()) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            this.playerNpc.setCurrentAiDetail("");
        }
        this.canUseThrottle.retryIn(
                this.playerNpc,
                RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15 + 1)
        );
        this.invitationStarted = false;
        this.inviteTarget = null;
        this.waitTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Only exact-target safety, look and gesture edges run here; acquisition keeps its
        // five-second throttle and never creates paths or broad scans from the running goal.
        return true;
    }

    private void observeResponseGesture() {
        boolean crouching = this.inviteTarget.isShiftKeyDown();
        Vec3 towardInviter = this.playerNpc.getEyePosition().subtract(this.inviteTarget.getEyePosition());
        Vec3 view = this.inviteTarget instanceof PlayerNpcEntity npcTarget
                ? Vec3.directionFromRotation(npcTarget.getXRot(), npcTarget.getYHeadRot())
                : this.inviteTarget.getViewVector(1.0F);
        boolean facing = towardInviter.lengthSqr() < 0.01D
                || view.dot(towardInviter.normalize()) >= 0.5D;
        if (!facing) {
            this.responsePresses = 0;
            this.responseReady = false;
        }
        if (crouching != this.lastResponseCrouching) {
            this.lastResponseCrouching = crouching;
            if (crouching && facing) {
                // One sampled rising edge per server tick is sufficient. The former four-tick
                // debounce rejected the very quick reciprocal tapping the inviter demonstrates.
                this.responsePresses++;
            }
            // Human replies may arrive during the offer. Remember them until the complete
            // offer finishes; NPC replies finish visibly before ownership changes clear crouch.
            this.responseReady = facing && this.responsePresses >= 2
                    && (!(this.inviteTarget instanceof PlayerNpcEntity) || !crouching);
        }
    }

    private void updateDetail() {
        if (this.inviteTarget != null) {
            this.playerNpc.setCurrentAiDetail((this.gestureTicks < this.offerTicks ? "inviting " : "waiting for crouch reply from ")
                    + this.inviteTarget.getDisplayName().getString());
        }
    }

}
