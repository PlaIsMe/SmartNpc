package com.pla.smart_npc.client.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.zigythebird.playeranim.animation.PlayerAnimResources;
import com.zigythebird.playeranim.util.RenderUtil;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.animation.AnimationController;
import com.zigythebird.playeranimcore.animation.AnimationData;
import com.zigythebird.playeranimcore.animation.HumanoidAnimationController;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.animation.layered.modifier.MirrorModifier;
import com.zigythebird.playeranimcore.bones.PlayerAnimBone;
import com.zigythebird.playeranimcore.enums.PlayState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.Identifier;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * Typed adapter for Player Animation Library 1.2.x (Minecraft 26.1).
 *
 * <p>The public surface intentionally uses {@link Object}. This class is only
 * reached after Better Combat's optional-mod gate, so SmartNpc can still load
 * without either Better Combat or Player Animation Library installed.</p>
 */
final class PlayerAnimationSampler26 {
    private PlayerAnimationSampler26() {
    }

    @Nullable
    static Object animation(Identifier id) {
        return PlayerAnimResources.getAnimationOptional(id).orElse(null);
    }

    static float endTick(Object value) {
        Animation animation = asAnimation(value);
        Object endTick = animation.data().getNullable("endTick");
        return endTick instanceof Number number ? number.floatValue() : animation.length();
    }

    static float stopTick(Object value) {
        return asAnimation(value).length();
    }

    static float loopedTime(Object value, float absoluteTime) {
        Animation animation = asAnimation(value);
        float length = Math.max(0.001F, animation.length());
        float time = Math.max(0.0F, absoluteTime);
        if (time < length || !animation.loopType().shouldPlayAgain(null, animation)) {
            return Math.min(time, Math.max(0.0F, length - 0.0001F));
        }

        float restart = Math.max(0.0F, Math.min(animation.loopType().restartFromTick(null, animation), length));
        float loopLength = length - restart;
        if (loopLength <= 0.001F) {
            return restart;
        }
        return restart + ((time - restart) % loopLength);
    }

    static Object sample(Object value, float animationTime, boolean mirrored) {
        Animation animation = asAnimation(value);
        HumanoidAnimationController controller = newController();

        if (mirrored) {
            MirrorModifier mirror = new MirrorModifier();
            mirror.enabled = true;
            controller.addModifier(mirror, 0);
        }

        controller.triggerAnimation(animation, Math.max(0.0F, animationTime));
        controller.setupAnim(new AnimationData(0.0F, 0.0F, false));
        return controller;
    }

    private static HumanoidAnimationController newController() {
        AnimationController.AnimationStateHandler stateHandler =
                (ignoredController, ignoredData, ignoredSetter) -> PlayState.CONTINUE;
        Function<AnimationController, Object> molangFactory = PlayerAnimationSampler26::createMolangEngine;

        try {
            for (Constructor<?> constructor : HumanoidAnimationController.class.getConstructors()) {
                if (constructor.getParameterCount() == 2
                        && constructor.getParameterTypes()[0] == AnimationController.AnimationStateHandler.class
                        && constructor.getParameterTypes()[1] == Function.class) {
                    return (HumanoidAnimationController) constructor.newInstance(stateHandler, molangFactory);
                }
            }
            throw new NoSuchMethodException("No compatible HumanoidAnimationController constructor");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create a Player Animation Library controller", unwrap(exception));
        }
    }

    private static Object createMolangEngine(AnimationController controller) {
        try {
            Class<?> loader = Class.forName("com.zigythebird.playeranimcore.molang.MolangLoader");
            Method factory = loader.getMethod("createNewEngine", AnimationController.class);
            return factory.invoke(null, controller);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not create a Player Animation Library Molang engine", unwrap(exception));
        }
    }

    static void applyPart(Object sampledAnimation, String legacyPartName, ModelPart part) {
        IAnimation animation = asSampledAnimation(sampledAnimation);
        PlayerAnimBone bone = new PlayerAnimBone(normalizePartName(legacyPartName));

        // This is the same sequence used by Player Animation Library's
        // PlayerModel mixin: seed unanimated axes from vanilla, sample, then
        // translate the model part relative to its initial pose.
        RenderUtil.copyVanillaPart(part, bone);
        animation.get3DTransform(bone);
        RenderUtil.translatePartToBone(part, bone, part.getInitialPose());
    }

    static void applyHeldItemTransform(PoseStack poseStack, Object sampledAnimation, String legacyPartName) {
        IAnimation animation = asSampledAnimation(sampledAnimation);
        PlayerAnimBone bone = new PlayerAnimBone(normalizePartName(legacyPartName));
        animation.get3DTransform(bone);

        Vector3f position = bone.position;
        Vector3f rotation = bone.rotation;
        Vector3f scale = bone.scale;

        // Match Player Animation Library 1.2.x's ItemInHandLayer mixin.
        poseStack.translate(position.x / 16.0F, -position.y / 16.0F, position.z / 16.0F);
        if (rotation.z != 0.0F) {
            poseStack.mulPose(Axis.ZP.rotation(-rotation.y));
        }
        if (rotation.y != 0.0F) {
            poseStack.mulPose(Axis.YP.rotation(-rotation.z));
        }
        if (rotation.x != 0.0F) {
            poseStack.mulPose(Axis.XP.rotation(-rotation.x));
        }
        poseStack.scale(scale.x, scale.y, scale.z);
    }

    private static Animation asAnimation(Object value) {
        if (value instanceof Animation animation) {
            return animation;
        }
        throw new IllegalArgumentException("Expected a Player Animation Library Animation");
    }

    private static IAnimation asSampledAnimation(Object value) {
        if (value instanceof IAnimation animation) {
            return animation;
        }
        throw new IllegalArgumentException("Expected a sampled Player Animation Library animation");
    }

    private static String normalizePartName(String partName) {
        return switch (partName) {
            case "leftArm" -> "left_arm";
            case "rightArm" -> "right_arm";
            case "leftLeg" -> "left_leg";
            case "rightLeg" -> "right_leg";
            case "leftItem" -> "left_item";
            case "rightItem" -> "right_item";
            default -> partName;
        };
    }

    private static Throwable unwrap(ReflectiveOperationException exception) {
        return exception instanceof InvocationTargetException invocationException
                && invocationException.getCause() != null
                ? invocationException.getCause()
                : exception;
    }
}
