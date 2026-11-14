package com.pla.smart_npc.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.animation.AnimationController;
import com.zigythebird.playeranimcore.animation.AnimationData;
import com.zigythebird.playeranimcore.animation.HumanoidAnimationController;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.animation.layered.modifier.MirrorModifier;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import com.zigythebird.playeranimcore.enums.PlayState;
import com.zigythebird.playeranimcore.loading.UniversalAnimLoader;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.function.Function;

import static org.junit.Assert.*;

public class PlayerAnimationSampler26Test {
    @Test
    public void loopingPosesMatchAnEstablishedControllerForBothHands() throws Exception {
        for (String pose : new String[]{"two_handed_polearm", "two_handed_sword", "two_handed_heavy",
                "two_handed_scythe", "two_handed_katana", "two_handed_bow", "two_handed_crossbow"}) {
            Animation animation = loadPose(pose);
            for (boolean mirror : new boolean[]{false, true}) {
                HumanoidAnimationController reference = newReferenceController();
                if (mirror) {
                    MirrorModifier modifier = new MirrorModifier();
                    modifier.enabled = true;
                    reference.addModifier(modifier, 0);
                }
                reference.triggerAnimation(animation);
                for (int tick = 0; tick < 40; tick++) {
                    for (float partial : new float[]{0.0F, 0.25F, 0.75F}) {
                        reference.setupAnim(new AnimationData(0.0F, partial, false));
                        IAnimation sampled = (IAnimation) PlayerAnimationSampler26.samplePose(animation, tick + partial, mirror);
                        for (String part : new String[]{"left_arm", "right_arm", "left_item", "right_item"}) {
                            PlayerAnimBone expected = reference.get3DTransform(part);
                            PlayerAnimBone actual = sampled.get3DTransform(part);
                            String context = pose + " " + part + " tick=" + tick + " partial=" + partial + " mirror=" + mirror;
                            assertVector(context, expected.position, actual.position);
                            assertVector(context, expected.rotation, actual.rotation);
                            assertVector(context, expected.scale, actual.scale);
                        }
                    }
                    reference.tick(new AnimationData(0.0F, 0.0F, false));
                }
            }
        }
    }

    @Test
    public void polearmRegressionRequiresLoopHistoryNotJustWrappedTime() throws Exception {
        Animation animation = loadPose("two_handed_polearm");
        float age = 20.25F;
        IAnimation fresh = (IAnimation) PlayerAnimationSampler26.sample(
                animation, PlayerAnimationSampler26.loopedTime(animation, age), false);
        IAnimation looped = (IAnimation) PlayerAnimationSampler26.samplePose(animation, age, false);
        assertFalse("The old per-frame controller must reproduce the startup interpolation",
                fresh.get3DTransform("left_arm").rotation.equals(looped.get3DTransform("left_arm").rotation, 0.0001F));
        IAnimation laterFrame = (IAnimation) PlayerAnimationSampler26.samplePose(animation, age + 0.5F, false);
        assertVector("Established polearm grip stays steady", looped.get3DTransform("left_arm").rotation,
                laterFrame.get3DTransform("left_arm").rotation);
    }

    @Test
    public void itemTranslationUsesArmSpaceBeforeVanillaItemRotation() {
        IAnimation animation = new IAnimation() {
            public boolean isActive() { return true; }
            public void get3DTransform(PlayerAnimBone bone) {
                bone.position.set(4.0F, 8.0F, 12.0F);
                bone.rotation.set(0.2F, 0.3F, 0.4F);
                bone.scale.set(1.1F, 0.9F, 1.2F);
            }
        };
        PoseStack actual = new PoseStack();
        PlayerAnimationSampler26.applyHeldItemTranslation(actual, animation, "rightItem");
        actual.mulPose(Axis.XP.rotationDegrees(-90.0F));
        actual.mulPose(Axis.YP.rotationDegrees(180.0F));
        actual.translate(1.0F / 16.0F, 0.125F, -0.625F);
        PlayerAnimationSampler26.applyHeldItemTransform(actual, animation, "rightItem");

        Matrix4f expected = new Matrix4f().translate(0.25F, -0.5F, 0.75F)
                .rotateX((float) -Math.PI / 2.0F).rotateY((float) Math.PI)
                .translate(1.0F / 16.0F, 0.125F, -0.625F)
                .rotateZ(-0.3F).rotateY(-0.4F).rotateX(-0.2F).scale(1.1F, 0.9F, 1.2F);
        assertTrue("Translation must precede the vanilla item rotations",
                expected.equals(actual.last().pose(), 0.0001F));
    }

    private static Animation loadPose(String pose) throws Exception {
        try (InputStream input = PlayerAnimationSampler26Test.class.getResourceAsStream(
                "/assets/bettercombat/player_animations/pose_" + pose + ".json")) {
            assertNotNull(pose, input);
            return UniversalAnimLoader.loadAnimations(input).values().iterator().next();
        }
    }

    private static HumanoidAnimationController newReferenceController() throws Exception {
        // PAL 1.2.9 changes the Molang return type; use the stable factory API
        // so this test also runs against the user's installed library version.
        Method factory = Class.forName("com.zigythebird.playeranimcore.molang.MolangLoader")
                .getMethod("createNewEngine", AnimationController.class);
        Function<AnimationController, Object> engine = controller -> {
            try {
                return factory.invoke(null, controller);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
        };
        AnimationController.AnimationStateHandler handler = (controller, data, setter) -> PlayState.CONTINUE;
        return HumanoidAnimationController.class
                .getConstructor(AnimationController.AnimationStateHandler.class, Function.class)
                .newInstance(handler, engine);
    }

    private static void assertVector(String context, Vector3f expected, Vector3f actual) {
        assertEquals(context + " x", expected.x, actual.x, 0.0001F);
        assertEquals(context + " y", expected.y, actual.y, 0.0001F);
        assertEquals(context + " z", expected.z, actual.z, 0.0001F);
    }
}
