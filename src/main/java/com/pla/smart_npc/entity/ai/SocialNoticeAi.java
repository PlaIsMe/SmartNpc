package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Brief attention, without taking goal flags or stopping/restarting a retained work plan. */
public final class SocialNoticeAi {
    private static final double NOTICE_RANGE = 6.0D;
    private static final double RANGE_SQR = NOTICE_RANGE * NOTICE_RANGE;
    private static final float NOTICE_CHANCE = 1.0F;
    private final PlayerNpcEntity npc;
    private final Map<UUID, Encounter> seen = new LinkedHashMap<>();
    private LivingEntity target;
    private BreakingBlockAi breaking;
    private int nextScanTick;
    private int untilTick;
    private int cooldownUntilTick;
    private float startBodyYaw;
    private float noticeHeadYaw;
    private float noticePitch;
    private float noticeBodyYaw;

    public SocialNoticeAi(PlayerNpcEntity npc) {
        this.npc = npc;
    }

    public boolean isActive() {
        return this.target != null;
    }

    public void trackBreaking(BreakingBlockAi breaking) {
        this.breaking = breaking;
    }

    public void releaseBreaking(BreakingBlockAi breaking) {
        if (this.breaking == breaking) {
            this.breaking = null;
        }
    }

    public void pauseWorkAnimation() {
        if (this.breaking != null) {
            this.breaking.pause();
        }
        this.npc.setSocialNoticePausesWork(true);
    }

    /** Selection is owned by the flagless notice goal and bounded by a per-NPC cadence. */
    public void beforeAiTick() {
        if (!this.npc.canSociallyNotice()) {
            this.stop();
            return;
        }
        if (this.target != null && (this.npc.tickCount >= this.untilTick
                || !this.canSee(this.target, this.startBodyYaw, 0.258819D))) {
            this.stop();
        }
        if (this.npc.tickCount < this.nextScanTick) {
            return;
        }
        this.nextScanTick = this.npc.tickCount + 20 + this.npc.getRandom().nextInt(6);
        this.seen.entrySet().removeIf(entry -> this.npc.tickCount - entry.getValue().lastSeenTick > 200);
        int checked = 0;
        for (LivingEntity candidate : this.npc.level().getEntitiesOfClass(LivingEntity.class,
                this.npc.getBoundingBox().inflate(NOTICE_RANGE, 3.0D, NOTICE_RANGE),
                entity -> entity != this.npc && (entity instanceof Player || entity instanceof PlayerNpcEntity))) {
            if (++checked > 16) {
                break;
            }
            if (!this.canSee(candidate, this.npc.yBodyRot, 0.5D)) {
                continue;
            }
            Encounter encounter = this.seen.get(candidate.getUUID());
            if (encounter == null || this.npc.tickCount - encounter.lastSeenTick >= 100) {
                encounter = new Encounter();
                this.seen.put(candidate.getUUID(), encounter);
            }
            encounter.lastSeenTick = this.npc.tickCount;
            while (this.seen.size() > 32) {
                this.seen.remove(this.seen.keySet().iterator().next());
            }
            if (this.target != null || this.npc.tickCount < this.cooldownUntilTick
                    || encounter.attempted) {
                continue;
            }
            // Seeing somebody while busy/cooling down is not a failed chance roll. Keep that
            // encounter eligible until we can actually decide, even if they remain in front.
            encounter.attempted = true;
            if (this.npc.getRandom().nextFloat() >= NOTICE_CHANCE) {
                continue;
            }
            this.target = candidate;
            this.startBodyYaw = this.npc.yBodyRot;
            this.noticeBodyYaw = this.npc.yBodyRot;
            this.noticeHeadYaw = this.npc.getYHeadRot();
            this.noticePitch = this.npc.getXRot();
            this.untilTick = this.npc.tickCount + 60 + this.npc.getRandom().nextInt(41);
            this.cooldownUntilTick = this.untilTick + 200 + this.npc.getRandom().nextInt(201);
        }
    }

    public void validateActive() {
        if (this.target != null && (!this.npc.canSociallyNotice() || this.npc.tickCount >= this.untilTick
                || !this.canSee(this.target, this.startBodyYaw, 0.258819D))) {
            this.stop();
        }
    }

    public void cancel() {
        this.stop();
    }

    /** Final bounded rotation wins over an allowed fishing/exploration owner's look request. */
    public void afterAiTick() {
        if (this.target == null) {
            return;
        }
        if (!this.npc.canSociallyNotice() || !this.canSee(this.target, this.startBodyYaw, 0.258819D)) {
            this.stop();
            return;
        }
        Vec3 delta = this.target.getEyePosition().subtract(this.npc.getEyePosition());
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
        float bodyTarget = this.startBodyYaw + Mth.clamp(Mth.wrapDegrees(yaw - this.startBodyYaw), -25.0F, 25.0F);
        // Moving exploration keeps its path-facing body; stationary work turns only a little.
        if (this.npc.isSocialNoticePausingWork() || this.npc.getNavigation().isDone()) {
            this.noticeBodyYaw = Mth.approachDegrees(this.noticeBodyYaw, bodyTarget, 2.0F);
            this.npc.yBodyRot = this.noticeBodyYaw;
            // Entity yaw is networked; yBodyRot alone would leave remote clients to infer it.
            this.npc.setYRot(this.noticeBodyYaw);
        }
        float headTarget = this.npc.yBodyRot + Mth.clamp(Mth.wrapDegrees(yaw - this.npc.yBodyRot), -65.0F, 65.0F);
        this.noticeHeadYaw = Mth.approachDegrees(this.noticeHeadYaw, headTarget, 6.0F);
        this.npc.setYHeadRot(this.noticeHeadYaw);
        float pitch = (float) (-Mth.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)) * Mth.RAD_TO_DEG);
        this.noticePitch = Mth.approachDegrees(this.noticePitch, Mth.clamp(pitch, -35.0F, 35.0F), 5.0F);
        this.npc.setXRot(this.noticePitch);
        if (this.npc.isSocialNoticePausingWork()) {
            this.pauseWorkAnimation();
        }
    }

    private boolean canSee(LivingEntity candidate, float facingYaw, double coneCosine) {
        if (!candidate.isAlive() || candidate.isRemoved() || candidate.level() != this.npc.level()
                || candidate.isInvisible() || candidate instanceof Player player && player.isSpectator()
                || this.npc.distanceToSqr(candidate) > RANGE_SQR) {
            return false;
        }
        Vec3 delta = candidate.position().subtract(this.npc.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (horizontal < 0.2D || Math.abs(delta.y) > 3.0D) {
            return false;
        }
        double angle = facingYaw * Mth.DEG_TO_RAD;
        return (-Math.sin(angle) * delta.x + Math.cos(angle) * delta.z) / horizontal >= coneCosine
                && this.npc.getSensing().hasLineOfSight(candidate);
    }

    private void stop() {
        this.target = null;
        this.npc.setSocialNoticePausesWork(false);
    }

    private static final class Encounter {
        private int lastSeenTick;
        private boolean attempted;
    }
}
