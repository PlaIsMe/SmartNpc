package com.pla.smart_npc.client.compat.epicysm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;

import com.pla.smart_npc.SmartNpc;

/** Retargeting and two-hand IK adapted from EpicYSM by Argorice (MIT).
 * See assets/smart_npc/licenses/EpicYSM-MIT.txt. Native skeleton access is supplied by Smart NPC. */
final class YsmPoseSolver {

    public record Placement(Quaternionf rotation, Vector3f offset) {
    }

    private static final Map<String, String[]> PIVOT_SOURCES = new LinkedHashMap<>();

    static {
        PIVOT_SOURCES.put("Root", new String[] { "AllBody", "MAllBody", "UpBody" });
        PIVOT_SOURCES.put("Torso", new String[] { "UpBody", "AllBody", "DownBody" });
        PIVOT_SOURCES.put("Chest", new String[] { "UpperBody", "UpBody" });
        PIVOT_SOURCES.put("Head", new String[] { "Head", "AllHead" });
        PIVOT_SOURCES.put("Shoulder_R", new String[] { "RightArm" });
        PIVOT_SOURCES.put("Arm_R", new String[] { "RightArm" });
        PIVOT_SOURCES.put("Hand_R", new String[] { "RightForeArm", "RightArm" });
        PIVOT_SOURCES.put("Elbow_R", new String[] { "RightForeArm", "RightArm" });
        PIVOT_SOURCES.put("Tool_R", new String[] { "RightHandLocator", "RightHand", "RightForeArm" });
        PIVOT_SOURCES.put("Shoulder_L", new String[] { "LeftArm" });
        PIVOT_SOURCES.put("Arm_L", new String[] { "LeftArm" });
        PIVOT_SOURCES.put("Hand_L", new String[] { "LeftForeArm", "LeftArm" });
        PIVOT_SOURCES.put("Elbow_L", new String[] { "LeftForeArm", "LeftArm" });
        PIVOT_SOURCES.put("Tool_L", new String[] { "LeftHandLocator", "LeftHand", "LeftForeArm" });
        PIVOT_SOURCES.put("Thigh_R", new String[] { "RightLeg" });
        PIVOT_SOURCES.put("Leg_R", new String[] { "RightLowerLeg", "RightLeg" });
        PIVOT_SOURCES.put("Knee_R", new String[] { "RightLowerLeg", "RightLeg" });
        PIVOT_SOURCES.put("Thigh_L", new String[] { "LeftLeg" });
        PIVOT_SOURCES.put("Leg_L", new String[] { "LeftLowerLeg", "LeftLeg" });
        PIVOT_SOURCES.put("Knee_L", new String[] { "LeftLowerLeg", "LeftLeg" });
    }

    private static final Map<String, String> BONE_TO_JOINT = new LinkedHashMap<>();

    static {
        BONE_TO_JOINT.put("head", "Head");
        BONE_TO_JOINT.put("allhead", "Head");
        BONE_TO_JOINT.put("hair", "Head");
        BONE_TO_JOINT.put("face", "Head");
        BONE_TO_JOINT.put("upperbody", "Chest");
        BONE_TO_JOINT.put("mupperbody", "Chest");
        BONE_TO_JOINT.put("breast", "Chest");
        BONE_TO_JOINT.put("collar", "Chest");
        BONE_TO_JOINT.put("arm", "Chest");
        BONE_TO_JOINT.put("upbody", "Torso");
        BONE_TO_JOINT.put("allbody", "Torso");
        BONE_TO_JOINT.put("mallbody", "Torso");
        BONE_TO_JOINT.put("downbody", "Torso");
        BONE_TO_JOINT.put("body", "Torso");
        BONE_TO_JOINT.put("skirt", "Torso");
        BONE_TO_JOINT.put("waist", "Torso");
        BONE_TO_JOINT.put("leg", "Torso");
        BONE_TO_JOINT.put("leftarm", "Arm_L");
        BONE_TO_JOINT.put("leftforearm", "Hand_L");
        BONE_TO_JOINT.put("lefthand", "Hand_L");
        BONE_TO_JOINT.put("rightarm", "Arm_R");
        BONE_TO_JOINT.put("rightforearm", "Hand_R");
        BONE_TO_JOINT.put("righthand", "Hand_R");
        BONE_TO_JOINT.put("leftleg", "Thigh_L");
        BONE_TO_JOINT.put("leftlowerleg", "Leg_L");
        BONE_TO_JOINT.put("leftfoot", "Leg_L");
        BONE_TO_JOINT.put("rightleg", "Thigh_R");
        BONE_TO_JOINT.put("rightlowerleg", "Leg_R");
        BONE_TO_JOINT.put("rightfoot", "Leg_R");
    }

    private static final String[] ANY_JOINT = { "Root", "Torso", "Chest", "Head", "Arm_L", "Arm_R",
            "Hand_L", "Hand_R", "Thigh_L", "Thigh_R", "Leg_L", "Leg_R" };

    private static final String[] BODY_JOINTS = { "Root", "Torso", "Chest", "Head" };

    private final Map<String, YsmSkeleton.Bone> byName = new HashMap<>();

    private final List<YsmSkeleton.Bone> ordered = new ArrayList<>();

    private final Map<String, Matrix4f> jointBindLocal = new LinkedHashMap<>();
    private final Map<String, Matrix4f> jointBindWorld = new LinkedHashMap<>();
    private final Map<String, Matrix4f> restWorldByBone = new HashMap<>();

    private final Map<String, String> boneJoint = new HashMap<>();

    private boolean oneTrunk;

    @Nullable
    private String jointNamed(String key) {
        if (this.oneTrunk && ("upbody".equals(key) || "mupbody".equals(key))) {
            return "Chest";
        }

        return BONE_TO_JOINT.get(key);
    }

    private final java.util.Set<String> sourced = new java.util.HashSet<>();
    private final List<String> jointOrder = new ArrayList<>();
    private final Map<String, String> jointParent = new HashMap<>();
    private final Map<String, Joint> jointByName = new HashMap<>();

    private boolean ready;

    public boolean isReady() {
        return this.ready;
    }

    private final Map<String, float[]> restRotations = new HashMap<>();

    public YsmPoseSolver(YsmSkeleton skeleton, Map<String, float[]> rests) {
        try {
            for (Map.Entry<String, float[]> entry : rests.entrySet()) {
                this.restRotations.put(key(entry.getKey()), entry.getValue());
            }

            this.build(skeleton);
        } catch (RuntimeException | LinkageError t) {
            SmartNpc.LOGGER.debug("Could not build a skeleton for the live model", t);
            this.ready = false;
        }
    }

    // Match the biped joint chain to this model's pivots and resting orientation once.
    private void build(YsmSkeleton skeleton) {
        for (YsmSkeleton.Bone bone : skeleton.bones()) {
            this.byName.put(key(bone.name()), bone);
        }

        this.oneTrunk = !this.byName.containsKey("upperbody") && !this.byName.containsKey("mupperbody");

        for (YsmSkeleton.Bone bone : skeleton.bones()) {
            this.addInOrder(bone, 0);

            if (bone.parent() != null) {
                this.roots.add(key(bone.parent()));
            }
        }
        for (String[] pair : new String[][] { { "leftarm", "rightarm" }, { "leftleg", "rightleg" },
                { "lefthand", "righthand" }, { "leftfoot", "rightfoot" } }) {
            YsmSkeleton.Bone left = this.byName.get(pair[0]);
            YsmSkeleton.Bone right = this.byName.get(pair[1]);

            if (left != null && right != null && Math.abs(left.pivotX() - right.pivotX()) > 0.01F) {
                this.mirrorX = left.pivotX() > right.pivotX();
                break;
            }
        }

        Armature biped = Armatures.BIPED.get();
        this.collectJoints(biped.rootJoint, null);

        Map<String, Vector3f> places = new LinkedHashMap<>();
        Map<String, Vector3f> bipedPlaces = new LinkedHashMap<>();
        this.collectBipedPlaces(biped.rootJoint, new Matrix4f(), bipedPlaces);
        float scale = this.modelScale(bipedPlaces);
        this.bipedScale = scale;
        this.buildBiped(bipedPlaces, scale);

        for (String joint : this.jointOrder) {
            Vector3f found = this.rawPlaceOf(joint);

            if (found != null) {
                places.put(joint, found);
                this.sourced.add(joint);
                continue;
            }
            Vector3f want = bipedPlaces.get(joint);

            if (want != null) {
                places.put(joint, new Vector3f(want).mul(scale));
            }
        }

        this.repair(places, bipedPlaces, scale);
        this.placeBody(places);

        Map<String, Matrix4f> bindWorld = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            Vector3f place = places.get(joint);

            if (place == null) {
                place = this.fallbackPlace(joint, bindWorld);
            }

            if (place == null) {
                return;
            }

            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : bindWorld.get(parent);
            Quaternionf restWorld = parentWorld.getNormalizedRotation(new Quaternionf())
                    .mul(this.bindRotation(joint));
            Matrix4f world = new Matrix4f().translation(place).rotate(restWorld);
            bindWorld.put(joint, world);
            this.jointBindWorld.put(joint, new Matrix4f(world));
            this.jointBindLocal.put(joint, new Matrix4f(parentWorld).invert().mul(world));
        }
        for (YsmSkeleton.Bone bone : this.ordered) {
            this.restWorldByBone.put(key(bone.name()), this.restWorld(bone));
        }

        this.findCarriers();
        this.countSubtrees();

        for (YsmSkeleton.Bone bone : this.ordered) {
            String joint = this.jointFor(bone, places);

            if (joint != null) {
                this.boneJoint.put(key(bone.name()), joint);
            }

        }

        this.ready = this.jointBindLocal.size() == this.jointOrder.size() && !this.ordered.isEmpty();
    }

    private float bipedScale = 1.0F;

    private final Map<String, Matrix4f> bipedBindLocal = new LinkedHashMap<>();

    private void buildBiped(Map<String, Vector3f> bipedPlaces, float scale) {
        Map<String, Matrix4f> world = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            Vector3f place = bipedPlaces.get(joint);

            if (place == null) {
                this.bipedBindLocal.clear();
                return;
            }

            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : world.get(parent);
            Quaternionf restWorld = parentWorld.getNormalizedRotation(new Quaternionf()).mul(this.bindRotation(joint));
            Matrix4f own = new Matrix4f().translation(new Vector3f(place).mul(scale)).rotate(restWorld);
            world.put(joint, own);
            this.bipedBindLocal.put(joint, new Matrix4f(parentWorld).invert().mul(own));
        }
    }

    private Map<String, Matrix4f> poseBiped(Pose pose) {
        Map<String, Matrix4f> posed = new LinkedHashMap<>();

        if (this.bipedBindLocal.size() != this.jointOrder.size()) {
            return posed;
        }

        for (String joint : this.jointOrder) {
            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : posed.get(parent);
            Matrix4f frame = new Matrix4f(parentWorld).mul(this.bipedBindLocal.get(joint));
            Vector3f shift = localTranslation(pose, joint);
            float ruler = unitsPerBlock();
            posed.put(joint, frame.translate(shift.x * ruler, shift.y * ruler, shift.z * ruler)
                    .rotate(localRotation(pose, joint)));
        }

        return posed;
    }

    private static final float HANDS_NEAR = 0.7F;
    private static final float HANDS_FAR = 0.85F;

    private static final float HANDS_RATE = 20.0F;

    private static final float RIGHT_RATE = 8.0F;

    private float handsWeight;

    private boolean rightAllowed;
    private float rightWeight;

    public void setRightHandInAction(boolean value) {
        this.rightAllowed = value;
    }

    private long handsStepped;

    // Fit both arms when an animation brings the hands together on one weapon.
    private void meetHands(Map<String, Matrix4f> posed, Pose pose) {
        Map<String, Matrix4f> biped = this.poseBiped(pose);
        Matrix4f bipedRight = biped.get("Tool_R");
        Matrix4f bipedLeft = biped.get("Tool_L");
        Matrix4f right = posed.get("Tool_R");
        Matrix4f left = posed.get("Tool_L");

        if (bipedRight == null || bipedLeft == null || right == null || left == null || this.bipedScale <= 0.0F) {
            return;
        }

        Matrix4f relation = new Matrix4f(bipedRight).invert().mul(bipedLeft);

        float apart = bipedLeft.getTranslation(new Vector3f()).distance(bipedRight.getTranslation(new Vector3f()))
                / this.bipedScale;
        if (!Float.isFinite(apart)) {
            apart = HANDS_FAR;
        }

        if (!Float.isFinite(this.handsWeight)) {
            this.handsWeight = 0.0F;
        }

        long now = System.nanoTime();
        float seconds = this.handsStepped == 0L ? 0.0F : Math.min(0.25F, (now - this.handsStepped) / 1.0e9F);
        this.handsStepped = now;
        float goal = apart <= HANDS_NEAR ? 1.0F : apart >= HANDS_FAR ? 0.0F : (HANDS_FAR - apart) / (HANDS_FAR - HANDS_NEAR);
        float step = HANDS_RATE * seconds;
        this.handsWeight = Math.max(0.0F, Math.min(1.0F, this.handsWeight + Math.max(-step, Math.min(step, goal - this.handsWeight))));

        if (this.handsWeight <= 0.0F) {
            return;
        }
        float rightStep = RIGHT_RATE * seconds;
        float rightGoal = this.rightAllowed ? 1.0F : 0.0F;

        if (!Float.isFinite(this.rightWeight)) {
            this.rightWeight = 0.0F;
        }

        this.rightWeight = Math.max(0.0F, Math.min(1.0F,
                this.rightWeight + Math.max(-rightStep, Math.min(rightStep, rightGoal - this.rightWeight))));
        float rightShare = this.handsWeight * this.rightWeight;
        Matrix4f bipedAnchor = biped.get("Chest");
        Matrix4f anchor = posed.get("Chest");

        if (bipedAnchor == null || anchor == null) {
            bipedAnchor = biped.get("Arm_R");
            anchor = posed.get("Arm_R");
        }

        if (rightShare > 0.0F && bipedAnchor != null && anchor != null) {
            Vector3f fromAnchor = bipedRight.getTranslation(new Vector3f()).sub(bipedAnchor.getTranslation(new Vector3f()));
            Vector3f placeRight = anchor.getTranslation(new Vector3f()).add(fromAnchor);
            Quaternionf faceRight = bipedRight.getNormalizedRotation(new Quaternionf());
            Matrix4f holdRight = new Matrix4f().translation(placeRight).rotate(faceRight);
            Vector3f shift = this.balance(posed, placeRight, new Matrix4f(holdRight).mul(relation).getTranslation(new Vector3f()));

            if (shift != null) {
                holdRight = new Matrix4f().translation(placeRight.add(shift)).rotate(faceRight);
            }

            Matrix4f wantRight = between(right, holdRight, rightShare);
            this.reachArm(posed, "R", wantRight);
            right = posed.get("Tool_R");
        }
        Matrix4f meetLeft = new Matrix4f(right).mul(relation);
        Matrix4f wantLeft = between(left, meetLeft, this.handsWeight);
        this.reachArm(posed, "L", wantLeft);

    }

    @Nullable
    private Vector3f balance(Map<String, Matrix4f> posed, Vector3f placeRight, Vector3f placeLeft) {
        float shortRight = this.shortBy(posed, "R", placeRight);
        float shortLeft = this.shortBy(posed, "L", placeLeft);

        if (!Float.isFinite(shortRight) || !Float.isFinite(shortLeft) || shortRight <= 0.0F && shortLeft <= 0.0F
                || shortRight > 0.0F && shortLeft > 0.0F) {
            return null;
        }

        boolean rightShort = shortRight > 0.0F;
        Matrix4f arm = posed.get(rightShort ? "Arm_R" : "Arm_L");

        if (arm == null) {
            return null;
        }
        Vector3f towards = arm.getTranslation(new Vector3f()).sub(rightShort ? placeRight : placeLeft);

        if (towards.lengthSquared() < 1.0e-8F) {
            return null;
        }

        float amount = Math.min(rightShort ? shortRight : shortLeft, rightShort ? -shortLeft : -shortRight);
        return amount > 1.0e-4F ? towards.normalize().mul(amount) : null;
    }

    private float shortBy(Map<String, Matrix4f> posed, String side, Vector3f place) {
        Matrix4f arm = posed.get("Arm_" + side);
        float reach = this.reach(posed, side);

        if (arm == null || !Float.isFinite(reach)) {
            return Float.NaN;
        }

        return place.distance(arm.getTranslation(new Vector3f())) - reach;
    }

    private float reach(Map<String, Matrix4f> joints, String side) {
        Matrix4f arm = joints.get("Arm_" + side);
        Matrix4f hand = joints.get("Hand_" + side);
        Matrix4f tool = joints.get("Tool_" + side);

        if (arm == null || hand == null || tool == null) {
            return Float.NaN;
        }

        Vector3f elbow = hand.getTranslation(new Vector3f());
        return arm.getTranslation(new Vector3f()).distance(elbow) + elbow.distance(tool.getTranslation(new Vector3f()));
    }

    private static Matrix4f between(Matrix4f from, Matrix4f to, float part) {
        Vector3f place = from.getTranslation(new Vector3f()).lerp(to.getTranslation(new Vector3f()), part);
        Quaternionf turn = from.getNormalizedRotation(new Quaternionf()).slerp(to.getNormalizedRotation(new Quaternionf()), part);
        return new Matrix4f().translation(place).rotate(turn);
    }

    private float reachArm(Map<String, Matrix4f> posed, String side, Matrix4f want) {
        String armName = "Arm_" + side;
        String handName = "Hand_" + side;
        String toolName = "Tool_" + side;
        Matrix4f arm = posed.get(armName);
        Matrix4f hand = posed.get(handName);
        Matrix4f tool = posed.get(toolName);

        if (arm == null || hand == null || tool == null) {
            return Float.NaN;
        }

        Vector3f target = want.getTranslation(new Vector3f());
        Quaternionf facing = want.getNormalizedRotation(new Quaternionf());
        Vector3f shoulder = arm.getTranslation(new Vector3f());
        Vector3f elbow = hand.getTranslation(new Vector3f());
        Vector3f wrist = tool.getTranslation(new Vector3f());
        float upper = shoulder.distance(elbow);
        float lower = elbow.distance(wrist);
        Vector3f toTarget = new Vector3f(target).sub(shoulder);

        if (upper < 1.0e-4F || lower < 1.0e-4F || toTarget.lengthSquared() < 1.0e-8F) {
            return Float.NaN;
        }

        float reach = Math.max(Math.abs(upper - lower) + 1.0e-3F, Math.min(upper + lower - 1.0e-3F, toTarget.length()));
        Vector3f dir = new Vector3f(toTarget).normalize();
        Vector3f bend = new Vector3f(elbow).sub(shoulder);
        bend.sub(new Vector3f(dir).mul(bend.dot(dir)));

        if (bend.lengthSquared() < 1.0e-6F) {
            Vector3f old = new Vector3f(wrist).sub(shoulder);
            bend = new Vector3f(dir).cross(new Vector3f(old).cross(dir));

            if (bend.lengthSquared() < 1.0e-6F) {
                bend = new Vector3f(dir).cross(new Vector3f(0.0F, 1.0F, 0.0F));
            }

            if (bend.lengthSquared() < 1.0e-6F) {
                bend = new Vector3f(1.0F, 0.0F, 0.0F);
            }
        }

        bend.normalize();
        float cos = (upper * upper + reach * reach - lower * lower) / (2.0F * upper * reach);
        cos = Math.max(-1.0F, Math.min(1.0F, cos));
        float sin = (float) Math.sqrt(Math.max(0.0F, 1.0F - cos * cos));
        Vector3f newElbow = new Vector3f(shoulder).add(new Vector3f(dir).mul(upper * cos)).add(new Vector3f(bend).mul(upper * sin));
        Quaternionf turnArm = new Quaternionf().rotationTo(new Vector3f(elbow).sub(shoulder), new Vector3f(newElbow).sub(shoulder));
        this.turnAbout(posed, armName, shoulder, turnArm);

        Vector3f elbowNow = posed.get(handName).getTranslation(new Vector3f());
        Vector3f wristNow = posed.get(toolName).getTranslation(new Vector3f());
        Quaternionf turnHand = new Quaternionf().rotationTo(new Vector3f(wristNow).sub(elbowNow), new Vector3f(target).sub(elbowNow));
        this.turnAbout(posed, handName, elbowNow, turnHand);
        Vector3f wristEnd = posed.get(toolName).getTranslation(new Vector3f());
        posed.put(toolName, new Matrix4f().translation(wristEnd).rotate(facing));
        return wristEnd.distance(target);
    }

    private void turnAbout(Map<String, Matrix4f> posed, String joint, Vector3f about, Quaternionf turn) {
        Matrix4f before = posed.get(joint);

        if (before == null) {
            return;
        }

        Matrix4f spin = new Matrix4f().translation(about).rotate(turn).translate(-about.x, -about.y, -about.z);
        Map<String, Matrix4f> old = new java.util.HashMap<>();

        for (String name : this.jointOrder) {
            if (this.isBelow(name, joint)) {
                old.put(name, posed.get(name));
            }
        }

        posed.put(joint, new Matrix4f(spin).mul(before));

        for (String name : this.jointOrder) {
            if (name.equals(joint) || !this.isBelow(name, joint)) {
                continue;
            }

            String parent = this.jointParent.get(name);
            Matrix4f parentOld = parent == null ? null : old.getOrDefault(parent, parent.equals(joint) ? before : null);
            Matrix4f mine = old.get(name);

            if (parentOld == null || mine == null || posed.get(parent) == null) {
                continue;
            }

            Matrix4f local = new Matrix4f(parentOld).invert().mul(mine);
            posed.put(name, new Matrix4f(posed.get(parent)).mul(local));
        }
    }

    private boolean isBelow(String joint, String ancestor) {
        for (String at = joint; at != null; at = this.jointParent.get(at)) {
            if (at.equals(ancestor)) {
                return true;
            }
        }

        return false;
    }

    private void addInOrder(YsmSkeleton.Bone bone, int depth) {
        if (depth > 32 || this.ordered.contains(bone)) {
            return;
        }

        YsmSkeleton.Bone parent = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

        if (parent != null) {
            this.addInOrder(parent, depth + 1);
        }

        if (!this.ordered.contains(bone)) {
            this.ordered.add(bone);
        }
    }

    private void collectJoints(Joint joint, @Nullable String parent) {
        this.jointOrder.add(joint.getName());
        this.jointParent.put(joint.getName(), parent);
        this.jointByName.put(joint.getName(), joint);

        for (Joint child : joint.getSubJoints()) {
            this.collectJoints(child, joint.getName());
        }
    }

    private Quaternionf bindRotation(String joint) {
        Joint found = this.jointByName.get(joint);
        return found == null ? new Quaternionf() : new Quaternionf(rotationOf(found.getLocalTransform()));
    }

    private boolean mirrorX;

    private static final float UNITS_PER_BLOCK = 16.0F / 0.7F;

    private static float drawnAt;

    public static void setModelScale(float value) {
        drawnAt = Float.isFinite(value) && value > 0.05F && value < 8.0F ? value : 0.0F;
    }

    private static float unitsPerBlock() {
        return drawnAt > 0.0F ? 16.0F / drawnAt : UNITS_PER_BLOCK;
    }

    private Vector3f pivotOf(YsmSkeleton.Bone bone) {
        return new Vector3f(this.mirrorX ? -bone.pivotX() : bone.pivotX(), bone.pivotY(), bone.pivotZ());
    }

    private Vector3f restWorldPivot(YsmSkeleton.Bone bone) {
        return this.restWorld(bone).transformPosition(this.pivotOf(bone));
    }

    private static final java.util.Set<String> STRAIGHT = java.util.Set.of(
            "arm", "leftarm", "rightarm", "leftforearm", "rightforearm", "lefthand", "righthand");

    private Matrix4f restWorld(YsmSkeleton.Bone bone) {
        Matrix4f world = new Matrix4f();
        List<YsmSkeleton.Bone> chain = new ArrayList<>();
        YsmSkeleton.Bone at = bone;

        for (int step = 0; at != null && step < 32; step++) {
            chain.add(0, at);
            at = at.parent() == null ? null : this.byName.get(key(at.parent()));
        }

        for (YsmSkeleton.Bone link : chain) {
            Vector3f pivot = this.pivotOf(link);
            String name = key(link.name());
            float[] rest = STRAIGHT.contains(name) ? ZERO : this.restRotations.getOrDefault(name, ZERO);
            world.translate(pivot.x, pivot.y, pivot.z)
                    .rotate(bedrock(rest))
                    .translate(-pivot.x, -pivot.y, -pivot.z);
        }

        return world;
    }

    static Quaternionf rotationOf(OpenMatrix4f matrix) {
        float lx = (float) Math.sqrt(matrix.m00 * matrix.m00 + matrix.m01 * matrix.m01 + matrix.m02 * matrix.m02);
        float ly = (float) Math.sqrt(matrix.m10 * matrix.m10 + matrix.m11 * matrix.m11 + matrix.m12 * matrix.m12);
        float lz = (float) Math.sqrt(matrix.m20 * matrix.m20 + matrix.m21 * matrix.m21 + matrix.m22 * matrix.m22);

        if (!(lx > 1.0e-5F) || !(ly > 1.0e-5F) || !(lz > 1.0e-5F)) {
            return new Quaternionf();
        }

        org.joml.Matrix3f basis = new org.joml.Matrix3f(
                matrix.m00 / lx, matrix.m01 / lx, matrix.m02 / lx,
                matrix.m10 / ly, matrix.m11 / ly, matrix.m12 / ly,
                matrix.m20 / lz, matrix.m21 / lz, matrix.m22 / lz);
        Quaternionf turn = new Quaternionf().setFromNormalized(basis);

        if (!Float.isFinite(turn.x) || !Float.isFinite(turn.y) || !Float.isFinite(turn.z) || !Float.isFinite(turn.w)
                || turn.x * turn.x + turn.y * turn.y + turn.z * turn.z + turn.w * turn.w < 1.0e-6F) {
            return new Quaternionf();
        }

        return turn.normalize();
    }

    @Nullable
    private Vector3f rawPlaceOf(String joint) {
        for (String candidate : PIVOT_SOURCES.getOrDefault(joint, new String[0])) {
            YsmSkeleton.Bone bone = this.byName.get(key(candidate));

            if (bone != null) {
                return this.restWorldPivot(bone);
            }
        }

        return null;
    }

    @Nullable
    private String jointFor(YsmSkeleton.Bone bone, Map<String, Vector3f> places) {
        YsmSkeleton.Bone at = bone;
        String inherited = null;

        for (int step = 0; at != null && step < 32; step++) {
            inherited = this.jointNamed(key(at.name()));

            if (inherited != null) {
                break;
            }

            at = at.parent() == null ? null : this.byName.get(key(at.parent()));
        }

        Matrix4f rest = this.restWorldByBone.get(key(bone.name()));

        if (rest == null) {
            return inherited;
        }
        if (!this.chainKnown(bone)) {
            return null;
        }

        Vector3f where = rest.transformPosition(this.pivotOf(bone));
        if (inherited != null) {
            return inherited;
        }
        if (bone.parent() == null && this.below(bone) > 8) {
            return "Root";
        }

        if (this.carries.contains(key(bone.name())) || atOrigin(where, places.get("Head"))
                || this.below(bone) > 8) {
            return "Torso";
        }

        String best = this.nearest(where, ANY_JOINT, places, true);
        return best != null ? best : this.nearest(where, BODY_JOINTS, places, false);
    }

    private static boolean atOrigin(Vector3f where, @Nullable Vector3f head) {
        return head != null && head.y > 0.01F && where.length() < 0.05F * head.y;
    }

    private final java.util.Set<String> carries = new java.util.HashSet<>();

    private final Map<String, Integer> subtree = new HashMap<>();

    private int below(YsmSkeleton.Bone bone) {
        return this.subtree.getOrDefault(key(bone.name()), 0);
    }

    private boolean container(YsmSkeleton.Bone bone) {
        if (this.below(bone) <= 8) {
            return false;
        }

        Vector3f pivot = this.pivotOf(bone);
        float height = this.modelHeight();
        return height > 0.01F && pivot.length() < 0.05F * height;
    }

    private float modelHeight() {
        Matrix4f head = this.jointBindWorld.get("Head");
        return head == null ? 24.0F : Math.max(1.0F, head.m31());
    }

    private void countSubtrees() {
        for (YsmSkeleton.Bone bone : this.ordered) {
            YsmSkeleton.Bone at = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

            for (int step = 0; at != null && step < 32; step++) {
                this.subtree.merge(key(at.name()), 1, Integer::sum);
                at = at.parent() == null ? null : this.byName.get(key(at.parent()));
            }
        }
    }

    private void findCarriers() {
        for (YsmSkeleton.Bone bone : this.ordered) {
            if (!BONE_TO_JOINT.containsKey(key(bone.name()))) {
                continue;
            }

            YsmSkeleton.Bone at = bone.parent() == null ? null : this.byName.get(key(bone.parent()));

            for (int step = 0; at != null && step < 32; step++) {
                this.carries.add(key(at.name()));
                at = at.parent() == null ? null : this.byName.get(key(at.parent()));
            }
        }
    }

    private final java.util.Set<String> roots = new java.util.HashSet<>();

    private boolean chainKnown(YsmSkeleton.Bone bone) {
        YsmSkeleton.Bone at = bone;

        for (int step = 0; step < 32; step++) {
            String above = at.parent();

            if (above == null) {
                return step > 0 || this.roots.contains(key(at.name()));
            }

            YsmSkeleton.Bone parent = this.byName.get(key(above));

            if (parent == null) {
                return false;
            }

            at = parent;
        }

        return false;
    }

    private static boolean isBody(String joint) {
        for (String body : BODY_JOINTS) {
            if (body.equals(joint)) {
                return true;
            }
        }

        return false;
    }

    @Nullable
    private String nearest(Vector3f where, String[] candidates, Map<String, Vector3f> places, boolean ownBoneOnly) {
        String best = null;
        float nearest = Float.MAX_VALUE;

        for (String joint : candidates) {
            if (ownBoneOnly && !this.sourced.contains(joint)) {
                continue;
            }

            Vector3f place = places.get(joint);

            if (place == null) {
                continue;
            }

            float distance = place.distance(where);

            if (distance < nearest) {
                nearest = distance;
                best = joint;
            }
        }

        return best;
    }

    private float modelScale(Map<String, Vector3f> biped) {
        Vector3f bipedHead = biped.get("Head");
        Vector3f ourHead = this.rawPlaceOf("Head");

        if (bipedHead == null || bipedHead.y < 0.01F || ourHead == null || ourHead.y <= 0.01F) {
            return unitsPerBlock();
        }
        float tall = ourHead.y / (bipedHead.y * unitsPerBlock());
        return Math.max(0.05F, Math.min(5.0F, tall)) * unitsPerBlock();
    }

    @Nullable
    private Vector3f fallbackPlace(String joint, Map<String, Matrix4f> bindWorld) {
        String parent = this.jointParent.get(joint);
        Matrix4f parentWorld = parent == null ? null : bindWorld.get(parent);
        return parentWorld == null ? null : parentWorld.getTranslation(new Vector3f());
    }

    private void collectBipedPlaces(Joint joint, Matrix4f parentWorld, Map<String, Vector3f> out) {
        OpenMatrix4f local = joint.getLocalTransform();
        Matrix4f step = new Matrix4f().translation(new Vector3f(local.m30, local.m31, local.m32))
                .rotate(rotationOf(local));
        Matrix4f world = new Matrix4f(parentWorld).mul(step);
        Vector3f place = world.getTranslation(new Vector3f());
        out.put(joint.getName(), new Vector3f(place));

        for (Joint child : joint.getSubJoints()) {
            this.collectBipedPlaces(child, world, out);
        }
    }

    private void placeBody(Map<String, Vector3f> places) {
        Vector3f thighL = places.get("Thigh_L");
        Vector3f thighR = places.get("Thigh_R");

        if (thighL != null && thighR != null) {
            places.put("Root", new Vector3f(0.0F,
                    (thighL.y + thighR.y) * 0.5F, (thighL.z + thighR.z) * 0.5F));
        }

        Vector3f root = places.get("Root");
        Vector3f chest = places.get("Chest");

        if (root != null && chest != null) {
            places.put("Torso", new Vector3f(0.0F, root.y + (chest.y - root.y) * 0.143F,
                    root.z + (chest.z - root.z) * 0.143F));
        }

        Vector3f head = places.get("Head");

        if (head != null) {
            places.put("Shoulder_L", new Vector3f(0.0F, head.y, head.z));
            places.put("Shoulder_R", new Vector3f(0.0F, head.y, head.z));
        }
    }

    private void repair(Map<String, Vector3f> places, Map<String, Vector3f> biped, float scale) {
        Vector3f bipedHead = biped.get("Head");

        if (bipedHead == null) {
            return;
        }
        float head = bipedHead.y * scale;

        if (!(head > 0.01F)) {
            return;
        }

        java.util.Set<String> bad = new java.util.HashSet<>();

        for (String joint : this.jointOrder) {
            Vector3f ours = places.get(joint);
            Vector3f want = biped.get(joint);

            if (ours == null || want == null) {
                bad.add(joint);
                continue;
            }

            float expected = want.y * scale;

            if (!Float.isFinite(ours.x) || !Float.isFinite(ours.y) || !Float.isFinite(ours.z)
                    || Math.abs(ours.y - expected) > 0.40F * head
                    || Math.abs(ours.x) > 0.9F * head || Math.abs(ours.z) > 0.9F * head) {
                bad.add(joint);
            }
        }
        for (String limb : new String[] { "Thigh", "Knee", "Leg", "Shoulder", "Arm", "Elbow", "Hand", "Tool" }) {
            Vector3f leftSide = places.get(limb + "_L");
            Vector3f rightSide = places.get(limb + "_R");

            if (leftSide != null && rightSide != null
                    && Math.abs(leftSide.y - rightSide.y) > 0.25F * head) {
                bad.add(limb + "_L");
                bad.add(limb + "_R");
            }
        }

        for (String[] chain : JOINT_CHAINS) {
            boolean anyBad = false;

            for (String joint : chain) {
                anyBad |= bad.contains(joint);
            }

            if (!anyBad) {
                continue;
            }

            for (String joint : chain) {
                Vector3f want = biped.get(joint);

                if (want != null) {
                    places.put(joint, new Vector3f(want).mul(scale));
                }
            }
        }
    }


    private static final String[][] JOINT_CHAINS = {
            { "Root", "Torso", "Chest", "Head" },
            { "Shoulder_L", "Arm_L", "Elbow_L", "Hand_L", "Tool_L" },
            { "Shoulder_R", "Arm_R", "Elbow_R", "Hand_R", "Tool_R" },
            { "Thigh_L", "Knee_L", "Leg_L" },
            { "Thigh_R", "Knee_R", "Leg_R" } };

    // Carry each bone by the change in its owning joint, then recover local TRS from
    // the posed parent. Translating as well as rotating preserves hands and root travel.
    public Map<String, Placement> solve(Pose pose, Map<String, float[]> rests) {
        Map<String, Placement> out = new HashMap<>();

        if (!this.ready) {
            return out;
        }

        Map<String, Matrix4f> posed = new LinkedHashMap<>();

        for (String joint : this.jointOrder) {
            String parent = this.jointParent.get(joint);
            Matrix4f parentWorld = parent == null ? new Matrix4f() : posed.get(parent);
            Quaternionf delta = localRotation(pose, joint);
            Matrix4f frame = new Matrix4f(parentWorld).mul(this.jointBindLocal.get(joint));
            Vector3f shift = localTranslation(pose, joint);

            if (shift.x == 0.0F && shift.y == 0.0F && shift.z == 0.0F) {
                posed.put(joint, frame.rotate(delta));
                continue;
            }
            float ruler = unitsPerBlock();
            posed.put(joint, frame.translate(shift.x * ruler, shift.y * ruler, shift.z * ruler)
                    .rotate(delta));
        }
        try {
            this.meetHands(posed, pose);
        } catch (RuntimeException | LinkageError t) {
            SmartNpc.LOGGER.debug("Could not bring the hands together", t);
        }
        Map<String, Matrix4f> boneWorld = new HashMap<>();

        for (YsmSkeleton.Bone bone : this.ordered) {
            Matrix4f parentWorld = bone.parent() == null ? new Matrix4f()
                    : boneWorld.getOrDefault(key(bone.parent()), new Matrix4f());
            Vector3f pivot = this.pivotOf(bone);
            String joint = this.boneJoint.get(key(bone.name()));
            Matrix4f bind = joint == null ? null : this.jointBindWorld.get(joint);
            Matrix4f rest = joint == null ? null : this.restWorldByBone.get(key(bone.name()));
            Matrix4f target = joint == null || bind == null || rest == null ? null
                    : new Matrix4f(posed.get(joint)).mul(new Matrix4f(bind).invert()).mul(rest);
            Quaternionf rotation;
            Vector3f offset;

            if (target == null) {
                float[] own = rests.getOrDefault(bone.name(), ZERO);
                rotation = fromRest(own);
                offset = new Vector3f();
            } else {
                Matrix4f inverseParent = new Matrix4f(parentWorld).invert();
                rotation = inverseParent.getNormalizedRotation(new Quaternionf())
                        .mul(target.getNormalizedRotation(new Quaternionf()));
                offset = inverseParent.transformPosition(target.transformPosition(new Vector3f(pivot)))
                        .sub(pivot);
                Vector3f written = this.mirrorX ? new Vector3f(-offset.x, offset.y, offset.z) : offset;
                out.put(bone.name(), new Placement(rotation, written));
            }

            boneWorld.put(key(bone.name()), new Matrix4f(parentWorld)
                    .translate(pivot.x + offset.x, pivot.y + offset.y, pivot.z + offset.z)
                    .rotate(rotation)
                    .translate(-pivot.x, -pivot.y, -pivot.z));
        }
        for (Placement placement : out.values()) {
            Vector3f offset = placement.offset();
            Quaternionf turn = placement.rotation();

            if (!Float.isFinite(offset.x) || !Float.isFinite(offset.y) || !Float.isFinite(offset.z)
                    || !Float.isFinite(turn.x) || !Float.isFinite(turn.y) || !Float.isFinite(turn.z) || !Float.isFinite(turn.w)) {
                if (!this.saidNotANumber) {
                    this.saidNotANumber = true;
                    SmartNpc.LOGGER.debug("Skeleton solver: the pose had no numbers in it this frame (a joint scaled to nothing);"
                            + " nothing was written, the model keeps its last pose for the frame");
                }

                return new HashMap<>();
            }
        }

        return out;
    }

    private boolean saidNotANumber;

    private static final float[] ZERO = new float[3];

    private static Quaternionf fromRest(float[] rest) {
        return bedrock(rest);
    }

    private static Quaternionf bedrock(float[] rest) {
        return new Quaternionf().rotateZ(rest[2]).rotateY(rest[1]).rotateX(rest[0]);
    }

    private static Vector3f localTranslation(Pose pose, String joint) {
        try {
            if (!pose.hasTransform(joint)) {
                return new Vector3f();
            }

            OpenMatrix4f moved = pose.orElseEmpty(joint).toMatrix();
            return moved == null ? new Vector3f() : new Vector3f(moved.m30, moved.m31, moved.m32);
        } catch (RuntimeException | LinkageError t) {
            return new Vector3f();
        }
    }

    private static Quaternionf localRotation(Pose pose, String joint) {
        try {
            if (!pose.hasTransform(joint)) {
                return new Quaternionf();
            }

            OpenMatrix4f turned = pose.orElseEmpty(joint).toMatrix();
            return turned == null ? new Quaternionf() : rotationOf(turned);
        } catch (RuntimeException | LinkageError t) {
            return new Quaternionf();
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
