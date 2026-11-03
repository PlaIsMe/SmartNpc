package com.pla.smart_npc.client.model;

import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;

/**
 * PlayerModel variant used by the fake-player renderer so optional Better
 * Combat attack keyframes can be applied after vanilla pose calculation.
 */
public class BetterCombatPlayerNpcModel<T extends FakePlayer> extends PlayerModel<T> {
    public BetterCombatPlayerNpcModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(T entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
        super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        if (!(entity instanceof PlayerNpcEntity playerNpc)) {
            return;
        }

        float partialTick = ageInTicks - entity.tickCount;
        partialTick = Math.max(0.0F, Math.min(1.0F, partialTick));
        boolean betterCombatApplied = BetterCombatClientCompat.applyAttackAnimation(this, playerNpc, partialTick);
        if (!betterCombatApplied) {
            betterCombatApplied = BetterCombatClientCompat.applyPoseAnimation(this, playerNpc, partialTick);
        }
        if (!betterCombatApplied) {
            return;
        }

        // PlayerAnimator normally injects before PlayerModel copies base parts
        // into the skin overlays. We apply at TAIL, so repeat those copies here.
        this.hat.copyFrom(this.head);
        this.jacket.copyFrom(this.body);
        this.leftSleeve.copyFrom(this.leftArm);
        this.rightSleeve.copyFrom(this.rightArm);
        this.leftPants.copyFrom(this.leftLeg);
        this.rightPants.copyFrom(this.rightLeg);
    }
}
