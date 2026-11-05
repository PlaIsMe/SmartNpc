package com.pla.smart_npc.client.compat.ysm.neoforge;

import com.elfmcys.yesstevemodel.OoooO0OO0000O00oo0Oo00OO;
import com.elfmcys.yesstevemodel.oo0o0Oooo0OOO00OOo0OO000;
import com.elfmcys.yesstevemodel.Oo00o00OOooooO0OooooOooO;
import com.elfmcys.yesstevemodel.OOoOoO0o0o0o000OO0o0ooo0;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.client.compat.epicysm.EpicFightYsmPose;
import dev.architectury.platform.Platform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import com.pla.smart_npc.client.renderer.FakePlayerTextureUtils;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.WeakHashMap;

/** Pinned to the released 2.6.5 NeoForge 1.21.1 JAR; obfuscated symbols are its actual API. */
public final class Ysm265Renderer implements YesSteveModelClientCompat.Renderer {
    private final DisplayRenderer renderer;
    private final Map<PlayerNpcEntity, DisplayState> displays = new WeakHashMap<>();

    public Ysm265Renderer(EntityRendererProvider.Context context) {
        this.renderer = new DisplayRenderer(context);
    }

    @Override
    public boolean render(PlayerNpcEntity npc, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        // This is YSM's connection-synchronized cache. Never read its separate local-model registry.
        var serverModel = oo0o0Oooo0OOO00OOo0OO000.oOo0OO0O0o000OO0O000oo0o(npc.getYsmModelId()).orElse(null);
        if (serverModel == null) {
            return false;
        }
        DisplayState display = this.displays.computeIfAbsent(npc, DisplayState::new);
        display.copyFrom(npc);
        if (!display.model.equals(npc.getYsmModelId()) || !display.texture.equals(npc.getYsmModelTexture())) {
            display.model = npc.getYsmModelId();
            display.texture = npc.getYsmModelTexture();
            display.animation.oOo0OO0O0o000OO0O000oo0o(display.model, display.texture);
        }
        display.animation.O0oo0O0O0O0oO0oooOOo00o0();
        // The YSM loader may still be preparing native resources. Its default-model fallback
        // must never be mistaken for the server's selected model.
        if (display.animation.oo0o0Oooo0OOO00OOo0OO000() != serverModel
                || display.animation.oOoo00O0o0oO0o0oO00OO0O0(partialTick) == null) {
            return false;
        }
        if (display.emoteSequence != npc.getYsmEmoteSequence() || !display.emote.equals(npc.getYsmEmote())) {
            display.animation.o00oO00o0Oo0oO0OoOO00OOO();
            display.emote = npc.getYsmEmote();
            display.emoteSequence = npc.getYsmEmoteSequence();
            if (!display.emote.isEmpty()) {
                display.animation.OoooO0OO0000O00oo0Oo00OO(display.emote);
            }
        }
        // Call the animatable overload: the proxy is never added to the level or player list.
        this.renderer.display = display;
        this.renderer.npc = npc;
        try {
            this.renderer.oOo0OO0O0o000OO0O000oo0o(display.animation, yaw, partialTick, poseStack, buffers, light);
        } finally {
            try {
                if (display.poseBridge != null) {
                    display.poseBridge.restore();
                }
            } finally {
                this.renderer.display = null;
                this.renderer.npc = null;
            }
        }
        return true;
    }

    /** Keeps Epic Fight classes out of YSM-only field and method signatures. */
    public interface PoseBridge {
        void apply(PlayerNpcEntity npc, OoooO0OO0000O00oo0Oo00OO animation, PoseStack poses, float partialTick);
        void restore();
    }

    private static final class DisplayRenderer extends Oo00o00OOooooO0OooooOooO {
        private DisplayState display;
        private PlayerNpcEntity npc;

        private DisplayRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public boolean shouldShowName(Player player) {
            return false; // FakePlayerRenderer keeps the NPC's real name and inspector rules.
        }

        @Override
        public void oOo0OO0O0o000OO0O000oo0o(OOoOoO0o0o0o000OO0o0ooo0 animation, PoseStack poses, float partialTick, MultiBufferSource buffers, VertexConsumer consumer, int light, int overlay, float red, float green, float blue, float alpha) {
            super.oOo0OO0O0o000OO0O000oo0o(animation, poses, partialTick, buffers, consumer, light, overlay, red, green, blue, alpha);
            // YSM has finished interpolating its frame here; bones have not yet been
            // consumed by the body or held-item layers. No buffer interception is needed.
            if (this.display == null || this.display.poseFailed || !Platform.isModLoaded("epicfight")) {
                return;
            }
            try {
                if (this.display.poseBridge == null) {
                    this.display.poseBridge = new EpicFightYsmPose();
                }
                this.display.poseBridge.apply(this.npc, this.display.animation, poses, partialTick);
            } catch (LinkageError | RuntimeException error) {
                if (this.display.poseBridge != null) {
                    this.display.poseBridge.restore();
                    this.display.poseBridge = null;
                }
                this.display.poseFailed = true;
                SmartNpc.LOGGER.warn("Smart NPC could not apply its Epic Fight pose to a YSM display; retaining YSM animation for this NPC.", error);
            }
        }
    }

    private static final class DisplayState {
        private final DisplayPlayer player;
        private final OoooO0OO0000O00oo0Oo00OO animation;
        private String model = "";
        private String texture = "";
        private String emote = "";
        private int emoteSequence = -1;
        private int lastTick = -1;
        private PoseBridge poseBridge;
        private boolean poseFailed;

        private DisplayState(PlayerNpcEntity npc) {
            this.player = new DisplayPlayer((ClientLevel) npc.level(), new GameProfile(npc.getUUID(), npc.getName().getString()));
            this.animation = new OoooO0OO0000O00oo0Oo00OO(this.player);
            // YSM resolves player animation through this attachment in the NeoForge release.
            this.player.setData(OoooO0OO0000O00oo0Oo00OO.oOo0OO0O0o000OO0O000oo0o, this.animation);
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
                this.player.walkAnimation.update(npc.walkAnimation.speed(), 1.0F);
                this.player.copySkin(npc);
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    this.player.setItemSlot(slot, npc.getItemBySlot(slot).copy());
                }
                if (npc.isUsingItem()) {
                    if (!this.player.isUsingItem() || this.player.getUsedItemHand() != npc.getUsedItemHand()) {
                        this.player.startUsingItem(npc.getUsedItemHand());
                    }
                    this.player.copyUseTicks(npc.getUseItemRemainingTicks());
                } else {
                    this.player.stopUsingItem();
                }
            }
        }
    }

    private static final class DisplayPlayer extends RemotePlayer implements YesSteveModelClientCompat.DisplayProxy {
        private GameProfile skinProfile;
        private PlayerSkin skin;
        private boolean inWater;
        private boolean inLava;
        private boolean climbing;
        private float swimAmount;

        private DisplayPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isSpectator() {
            // The NPC UUID has a spectator tab-list entry, but this visual proxy
            // represents a living NPC whose native YSM item layers must be rendered.
            return false;
        }

        private void copyUseTicks(int ticks) {
            this.useItemRemaining = ticks;
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
            this.skinProfile = npc.getProfile();
            PlayerSkin.Model model = FakePlayerTextureUtils.getPlayerSkinType(npc.getProfile()) == FakePlayerTextureUtils.SkinType.SLIM ? PlayerSkin.Model.SLIM : PlayerSkin.Model.WIDE;
            var cape = FakePlayerTextureUtils.getPlayerCape(npc).orElse(null);
            this.skin = new PlayerSkin(FakePlayerTextureUtils.getPlayerSkin(npc), null, cape, cape, model, false);
        }

        @Override
        public GameProfile getGameProfile() {
            return this.skinProfile != null ? this.skinProfile : super.getGameProfile();
        }

        @Override
        public @NotNull PlayerSkin getSkin() {
            return this.skin == null ? super.getSkin() : this.skin;
        }
    }
}
