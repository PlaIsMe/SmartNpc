package com.pla.smart_npc.client.compat.epicysm;

import com.elfmcys.yesstevemodel.o0ooO0ooO00oo0o00Oo00000;
import com.elfmcys.yesstevemodel.OoooO0OO0000O00oo0Oo00OO;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.compat.ysm.neoforge.Ysm265Renderer;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import org.joml.Vector3f;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.HashMap;
import java.util.Map;

/** Client-only; constructed only after the NeoForge renderer has checked for Epic Fight. */
public final class EpicFightYsmPose implements Ysm265Renderer.PoseBridge {
    private o0ooO0ooO00oo0o00Oo00000 model;
    private YsmPoseSolver solver;
    private final Map<String, float[]> rests = new HashMap<>();
    private final Map<String, Integer> slots = new HashMap<>();
    private float[] saved;
    private boolean applied;

    @Override
    public void apply(PlayerNpcEntity npc, OoooO0OO0000O00oo0Oo00OO animation, PoseStack poses, float partialTick) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(npc, LivingEntityPatch.class);
        // Beds and NPC emotes retain YSM's own animation and alignment. All Epic Fight
        // combat/work poses use the actual NPC patch, never the display player's patch.
        if (patch == null || !patch.overrideRender() || patch.getAnimator() == null || npc.isSleeping() || !npc.getYsmEmote().isEmpty()) {
            return;
        }
        Pose pose = patch.getAnimator().getPose(partialTick);
        if (pose == null) {
            return;
        }
        float width = animation.c_();
        float height = animation.e_();
        YsmPoseSolver.setModelScale((float) Math.cbrt(Math.abs(width * width * height)));
        var currentModel = animation.O00OOOo00Oo0OO0000oOo0oo();
        if (currentModel != this.model) {
            this.rebuild(currentModel);
        }
        if (this.solver == null || !this.solver.isReady()) {
            return;
        }
        this.solver.setRightHandInAction(patch.getEntityState().inaction());
        var placements = this.solver.solve(pose, this.rests);
        if (placements.isEmpty()) {
            return;
        }
        float[] transforms = this.model.oOo0OO0O0o000OO0O000oo0o();
        if (this.saved == null || this.saved.length != transforms.length) {
            this.saved = new float[transforms.length];
        }
        System.arraycopy(transforms, 0, this.saved, 0, transforms.length);
        this.applied = true;
        Vector3f euler = new Vector3f();
        for (var entry : placements.entrySet()) {
            Integer slot = this.slots.get(entry.getKey());
            if (slot == null) {
                continue;
            }
            var placement = entry.getValue();
            placement.rotation().getEulerAnglesZYX(euler);
            transforms[slot] = euler.x;
            transforms[slot + 1] = euler.y;
            transforms[slot + 2] = euler.z;
            // YSM composes Z/Y/X rotations and negates position X in its native draw.
            transforms[slot + 3] = -placement.offset().x;
            transforms[slot + 4] = placement.offset().y;
            transforms[slot + 5] = placement.offset().z;
        }
        // The retargeter handles root travel. Whole-body scale belongs on the render
        // stack so vanish/resize animations also affect YSM's body and held-item passes.
        var root = pose.getJointTransformData().get("Root");
        if (root != null) {
            var scale = root.scale();
            if (Float.isFinite(scale.x) && Float.isFinite(scale.y) && Float.isFinite(scale.z)) {
                poses.scale(scale.x, scale.y, scale.z);
            }
        }
    }

    private void rebuild(o0ooO0ooO00oo0o00Oo00000 model) {
        this.model = model;
        this.solver = null;
        this.rests.clear();
        this.slots.clear();
        this.saved = null;
        if (model == null) {
            return;
        }
        var definitions = model.OOo0o0000Ooo0o00OO0oOOoO().oOo0OO0O0o000OO0O000oo0o();
        if (definitions.size() > 8192) {
            return;
        }
        for (int i = 0; i < definitions.size(); i++) {
            var definition = definitions.get(i);
            String name = definition.oOo0OO0O0o000OO0O000oo0o();
            var rest = model.OO000o0ooOooooOOOOO0Ooo0().get(definition.oOoo00O0o0oO0o0oO00OO0O0()).O0OO0O0o00o0o00oOoO0o0oO();
            this.rests.put(name, new float[] {rest.x, rest.y, rest.z});
            this.slots.put(name, i * 12);
        }
        // Non-humanoid rigs remain fully renderable through YSM without inventing joints.
        boolean head = this.rests.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("Head"));
        boolean arms = this.rests.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("LeftArm"))
                && this.rests.keySet().stream().anyMatch(name -> name.equalsIgnoreCase("RightArm"));
        if (head && arms) {
            this.solver = new YsmPoseSolver(YsmSkeleton.read(model), this.rests);
        }
    }

    @Override
    public void restore() {
        if (this.applied && this.saved != null && this.model != null) {
            System.arraycopy(this.saved, 0, this.model.oOo0OO0O0o000OO0O000oo0o(), 0, this.saved.length);
            this.applied = false;
        }
    }
}
