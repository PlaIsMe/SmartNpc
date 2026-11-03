package com.pla.smart_npc.client.renderer;

import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.model.EquipmentAssetManager;

/** SmartNpc cape compatibility now delegates to vanilla's render-state cape layer. */
public final class FakePlayerCapeLayer extends CapeLayer {
    public FakePlayerCapeLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,
                               EntityModelSet modelSet, EquipmentAssetManager equipmentAssets) {
        super(parent, modelSet, equipmentAssets);
    }
}
