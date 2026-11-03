package com.pla.smart_npc.client.compat.ysm;

import com.elfmcys.yesstevemodel.O0OoOoO0oo0O00O0ooo0o0OO;
import com.elfmcys.yesstevemodel.OOOoooO00oo000OOOOoO0OO0;
import com.elfmcys.yesstevemodel.OoOo0OOOoo0o0oOOoooooo0O;
import com.elfmcys.yesstevemodel.o00O0ooooooOoo0oOO0oo0O0;
import com.elfmcys.yesstevemodel.ooOoo0O0O0o0O00OO0O00ooo;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat.Frame;
import com.pla.smart_npc.client.renderer.FakePlayerTextureUtils;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** Pinned to the released 2.6.5 NeoForge mc26.1-hotfix JAR; obfuscated symbols are its actual API. */
public final class Ysm265Renderer implements YesSteveModelClientCompat.Renderer {
    private final ooOoo0O0O0o0O00OO0O00ooo renderer;
    private final AvatarRenderer<DisplayPlayer> stateExtractor;
    private final Map<PlayerNpcEntity, DisplayState> displays = new WeakHashMap<>();

    public Ysm265Renderer(EntityRendererProvider.Context context) {
        this.renderer = new ooOoo0O0O0o0O00OO0O00ooo(context);
        this.stateExtractor = new AvatarRenderer<>(context, false);
    }

    @Override
    public @Nullable Frame extract(PlayerNpcEntity npc, AvatarRenderState npcState, float partialTick) {
        // This is YSM's connection-synchronized cache, never its separate local-model registry.
        var serverModel = o00O0ooooooOoo0oOO0oo0O0.OO0OoO00ooOOo0o00O000OoO(npc.getYsmModelId()).orElse(null);
        if (serverModel == null) {
            return null;
        }
        DisplayState display = this.displays.computeIfAbsent(npc, DisplayState::new);
        // Finish native work before changing the proxy read by its animation queries.
        display.animation.OOOoooO00oo000OOOOoO0OO0();
        display.copyFrom(npc);
        if (!display.model.equals(npc.getYsmModelId()) || !display.texture.equals(npc.getYsmModelTexture())) {
            display.model = npc.getYsmModelId();
            display.texture = npc.getYsmModelTexture();
            display.animation.OO0OoO00ooOOo0o00O000OoO(display.model, display.texture);
        }
        display.animation.o0Oo00O0Ooo00oOo0O000Ooo();
        if (display.animation.O000O0000O00000oo0o0OooO() != serverModel) {
            return null;
        }
        if (display.emoteSequence != npc.getYsmEmoteSequence() || !display.emote.equals(npc.getYsmEmote())) {
            display.animation.ooOo0O0OO0OoO00o0OooOOOo();
            display.emote = npc.getYsmEmote();
            display.emoteSequence = npc.getYsmEmoteSequence();
            if (!display.emote.isEmpty()) {
                display.animation.O0OoOoO0oo0O00O0ooo0o0OO(display.emote);
            }
        }
        AvatarRenderState snapshot = this.stateExtractor.createRenderState();
        this.stateExtractor.extractRenderState(display.player, snapshot, partialTick);
        copyNpcPresentation(npcState, snapshot);
        // YSM attaches its dispatcher interception key here. Keep it on this private snapshot,
        // so SmartNpc still owns normal/inspector dispatch and optional-mod failure handling.
        var task = OOOoooO00oo000OOOOoO0OO0.OO0OoO00ooOOo0o00O000OoO(display.animation, snapshot);
        var frame = task.O0oo00O0OoooOOOO00ooO000();
        if (frame == null || display.animation.O000O0000O00000oo0o0OooO() != serverModel) {
            return null;
        }
        // Native extraction copies bone buffers and item transforms. Resolve now, before a
        // later frame changes the unticked proxy; submission consumes the captured data only.
        return new NativeFrame(this.renderer, snapshot, frame);
    }

    private static void copyNpcPresentation(AvatarRenderState source, AvatarRenderState target) {
        target.entityType = source.entityType;
        target.id = source.id;
        target.x = source.x;
        target.y = source.y;
        target.z = source.z;
        target.boundingBoxWidth = source.boundingBoxWidth;
        target.boundingBoxHeight = source.boundingBoxHeight;
        target.eyeHeight = source.eyeHeight;
        target.bedOrientation = source.bedOrientation;
        target.distanceToCameraSq = source.distanceToCameraSq;
        target.nameTag = source.nameTag;
        target.scoreText = source.scoreText;
        target.nameTagAttachment = source.nameTagAttachment;
        target.isInvisible = source.isInvisible;
        target.isInvisibleToPlayer = source.isInvisibleToPlayer;
        target.isDiscrete = source.isDiscrete;
        target.lightCoords = source.lightCoords;
        target.outlineColor = source.outlineColor;
        target.hasRedOverlay = source.hasRedOverlay;
        target.isUpsideDown = source.isUpsideDown;
        target.bodyRot = source.bodyRot;
        target.yRot = source.yRot;
        target.xRot = source.xRot;
        target.walkAnimationPos = source.walkAnimationPos;
        target.walkAnimationSpeed = source.walkAnimationSpeed;
        target.scale = source.scale;
        target.ageScale = source.ageScale;
        target.isCrouching = source.isCrouching;
        target.capeFlap = source.capeFlap;
        target.capeLean = source.capeLean;
        target.capeLean2 = source.capeLean2;
        target.skin = source.skin;
        target.showHat = source.showHat;
        target.showJacket = source.showJacket;
        target.showLeftPants = source.showLeftPants;
        target.showRightPants = source.showRightPants;
        target.showLeftSleeve = source.showLeftSleeve;
        target.showRightSleeve = source.showRightSleeve;
        target.showCape = source.showCape;
    }

    private record NativeFrame(ooOoo0O0O0o0O00OO0O00ooo renderer, AvatarRenderState state, OoOo0OOOoo0o0oOOoooooo0O<?> frame) implements Frame {
        @Override
        public void submit(PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
            this.renderer.OO0OoO00ooOOo0o00O000OoO(this.state, this.frame, poseStack, collector, camera);
        }
    }

    private static final class DisplayState {
        private final DisplayPlayer player;
        private final O0OoOoO0oo0O00O0ooo0o0OO animation;
        private String model = "";
        private String texture = "";
        private String emote = "";
        private int emoteSequence = -1;
        private int lastTick = -1;

        private DisplayState(PlayerNpcEntity npc) {
            this.player = new DisplayPlayer((ClientLevel) npc.level(), new GameProfile(npc.getUUID(), npc.getName().getString()));
            this.animation = new O0OoOoO0oo0O00O0ooo0o0OO(this.player);
            // YSM resolves player animation through this attachment in the NeoForge release.
            this.player.setData(O0OoOoO0oo0O00O0ooo0o0OO.OO0OoO00ooOOo0o00O000OoO, this.animation);
        }

        private void copyFrom(PlayerNpcEntity npc) {
            this.player.setPos(npc.getX(), npc.getY(), npc.getZ());
            this.player.xo = npc.xo;
            this.player.yo = npc.yo;
            this.player.zo = npc.zo;
            this.player.xOld = npc.xOld;
            this.player.yOld = npc.yOld;
            this.player.zOld = npc.zOld;
            this.player.setYRot(npc.getYRot());
            this.player.setXRot(npc.getXRot());
            this.player.yRotO = npc.yRotO;
            this.player.xRotO = npc.xRotO;
            this.player.yBodyRot = npc.yBodyRot;
            this.player.yBodyRotO = npc.yBodyRotO;
            this.player.yHeadRot = npc.yHeadRot;
            this.player.yHeadRotO = npc.yHeadRotO;
            this.player.tickCount = npc.tickCount;
            this.player.hurtTime = npc.hurtTime;
            this.player.deathTime = npc.deathTime;
            this.player.attackAnim = npc.attackAnim;
            this.player.oAttackAnim = npc.oAttackAnim;
            this.player.swinging = npc.swinging;
            // YSM restarts its hand controller at swingTime == 0. This display-only
            // player never ticks, so copy the real timer together with the swing flag.
            this.player.swingTime = npc.swingTime;
            this.player.swingingArm = npc.swingingArm;
            this.player.setDeltaMovement(npc.getDeltaMovement());
            this.player.setOnGround(npc.onGround());
            this.player.setPose(npc.getPose());
            this.player.setShiftKeyDown(npc.isShiftKeyDown());
            this.player.setSprinting(npc.isSprinting());
            this.player.setSwimming(npc.isSwimming());
            this.player.setInvisible(npc.isInvisible());
            this.player.setMainArm(npc.getMainArm());
            this.player.copyMotionFlags(npc);
            this.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(npc.getMaxHealth());
            this.player.setHealth(npc.getHealth());
            if (this.lastTick != npc.tickCount) {
                this.lastTick = npc.tickCount;
                this.player.walkAnimation.update(npc.walkAnimation.speed(), 1.0F, 1.0F);
                this.player.copySkin(npc);
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    this.player.setItemSlot(slot, npc.getItemBySlot(slot).copy());
                }
                this.player.copyUseState(npc);
            }
        }
    }

    private static final class DisplayPlayer extends RemotePlayer {
        private PlayerSkin skin;
        private boolean inWater;
        private boolean inLava;
        private boolean climbing;
        private float swimAmount;

        private DisplayPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        private void copyUseState(PlayerNpcEntity npc) {
            // Client startUsingItem does not set the synchronized flags. Copy visual state
            // directly instead of invoking gameplay item-use hooks on a display-only player.
            boolean using = npc.isUsingItem();
            this.setLivingEntityFlag(2, using && npc.getUsedItemHand() == InteractionHand.OFF_HAND);
            this.setLivingEntityFlag(1, using);
            this.useItem = using ? this.getItemInHand(npc.getUsedItemHand()) : ItemStack.EMPTY;
            this.useItemRemaining = using ? npc.getUseItemRemainingTicks() : 0;
        }

        private void copyMotionFlags(PlayerNpcEntity npc) {
            this.inWater = npc.isInWater();
            this.inLava = npc.isInLava();
            this.climbing = npc.onClimbable();
            this.swimAmount = npc.getSwimAmount(1.0F);
            this.setSharedFlag(7, npc.isFallFlying());
            this.setSharedFlag(0, npc.isOnFire());
            this.setRemainingFireTicks(npc.getRemainingFireTicks());
            this.fallDistance = npc.fallDistance;
        }

        @Override
        public boolean isInWater() {
            return this.inWater;
        }

        @Override
        public boolean isInLava() {
            return this.inLava;
        }

        @Override
        public boolean onClimbable() {
            return this.climbing;
        }

        @Override
        public float getSwimAmount(float partialTick) {
            return this.swimAmount;
        }

        private void copySkin(PlayerNpcEntity npc) {
            this.skin = FakePlayerTextureUtils.getPlayerSkinData(npc);
        }

        @Override
        public @NonNull PlayerSkin getSkin() {
            return this.skin == null ? super.getSkin() : this.skin;
        }
    }
}
