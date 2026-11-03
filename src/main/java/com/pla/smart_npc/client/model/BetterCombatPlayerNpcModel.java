package com.pla.smart_npc.client.model;

import com.pla.smart_npc.client.compat.BetterCombatClientCompat;
import com.pla.smart_npc.client.renderer.FakePlayerRenderState;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;

/**
 * PlayerModel variant used by the fake-player renderer so optional Better
 * Combat attack keyframes can be applied after vanilla pose calculation.
 */
public class BetterCombatPlayerNpcModel extends PlayerModel {
    public BetterCombatPlayerNpcModel(ModelPart root, boolean slim) {
        super(root, slim);
    }

    @Override
    public void setupAnim(AvatarRenderState state) {
        super.setupAnim(state);

        if (!(state instanceof FakePlayerRenderState npcState) || npcState.playerNpc == null) {
            return;
        }
        PlayerNpcEntity playerNpc = npcState.playerNpc;

        float partialTick = state.partialTick;
        boolean betterCombatApplied = BetterCombatClientCompat.applyAttackAnimation(this, playerNpc, partialTick);
        if (!betterCombatApplied) {
            betterCombatApplied = BetterCombatClientCompat.applyPoseAnimation(this, playerNpc, partialTick);
        }
        if (!betterCombatApplied) {
            if (npcState.hideHead) {
                this.head.visible = false;
                this.hat.visible = false;
            }
            return;
        }

        // In 26.1 the skin overlays are children of their base parts and inherit
        // the animated transform directly, so no post-animation copy is needed.
        if (npcState.hideHead) {
            this.head.visible = false;
            this.hat.visible = false;
        }
    }
}
