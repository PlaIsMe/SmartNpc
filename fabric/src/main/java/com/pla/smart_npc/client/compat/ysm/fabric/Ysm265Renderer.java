package com.pla.smart_npc.client.compat.ysm.fabric;

import com.elfmcys.yesstevemodel.ooO0OOoo0o0OoO00oOO0o0o0;
import com.elfmcys.yesstevemodel.Oo0OOOo00Oo00000o0oOoOoo;
import com.elfmcys.yesstevemodel.O0OO00OO0ooo0OO00OOoO0O0;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import com.pla.smart_npc.client.renderer.FakePlayerTextureUtils;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.FishingRodCastState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import com.elfmcys.yesstevemodel.Oo0O0ooOo0O00o0OO0OO0O00;
import com.elfmcys.yesstevemodel.O0Oo000000o0OO0OOoooOoo0;
import com.elfmcys.yesstevemodel.OOOOo00O0OOOOooO0o00OooO;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** Pinned to the released 2.6.5 Fabric 1.20.1 JAR; obfuscated symbols are its actual API. */
public final class Ysm265Renderer implements YesSteveModelClientCompat.Renderer {
    private final DisplayRenderer renderer;
    private final Map<PlayerNpcEntity, DisplayState> displays = new WeakHashMap<>();

    public Ysm265Renderer(EntityRendererProvider.Context context) {
        this.renderer = new DisplayRenderer(context);
    }

    @Override
    public boolean render(PlayerNpcEntity npc, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        // This is YSM's connection-synchronized cache. Never read its separate local-model registry.
        var serverModel = Oo0OOOo00Oo00000o0oOoOoo.OoOo0OooO0OOO0Oo00000o00(npc.getYsmModelId()).orElse(null);
        if (serverModel == null) {
            return false;
        }
        DisplayState display = this.displays.computeIfAbsent(npc, DisplayState::new);
        display.copyFrom(npc);
        if (!display.model.equals(npc.getYsmModelId()) || !display.texture.equals(npc.getYsmModelTexture())) {
            display.model = npc.getYsmModelId();
            display.texture = npc.getYsmModelTexture();
            display.animation.OoOo0OooO0OOO0Oo00000o00(display.model, display.texture);
        }
        display.animation.OO0OO0oOO0oOoOo0o0ooOo0o();
        // The YSM loader may still be preparing native resources. Its default-model fallback
        // must never be mistaken for the server's selected model.
        if (display.animation.OoOOo0OOoooO00o00o0ooO0o() != serverModel
                || display.animation.OoooO000OO0OoO0o0o0ooooO(partialTick) == null) {
            return false;
        }
        if (display.emoteSequence != npc.getYsmEmoteSequence() || !display.emote.equals(npc.getYsmEmote())) {
            display.animation.oOOOO0O0OOoo000O0ooo0OOO();
            display.emote = npc.getYsmEmote();
            display.emoteSequence = npc.getYsmEmoteSequence();
            if (!display.emote.isEmpty()) {
                display.animation.OO0OOo0o0oOOOOoOO00O0o0O(display.emote);
            }
        }
        // Call the animatable overload: the proxy is never added to the level or player list.
        this.renderer.OoOo0OooO0OOO0Oo00000o00(display.animation, yaw, partialTick, poseStack, buffers, light);
        return true;
    }

    private static final class DisplayRenderer extends O0OO00OO0ooo0OO00OOoO0O0 {
        private DisplayRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public boolean shouldShowName(Player player) {
            return false; // FakePlayerRenderer keeps the NPC's real name and inspector rules.
        }
    }

    private static final class DisplayState {
        private final DisplayPlayer player;
        private final ooO0OOoo0o0OoO00oOO0o0o0 animation;
        private String model = "";
        private String texture = "";
        private String emote = "";
        private int emoteSequence = -1;
        private int lastTick = -1;

        private DisplayState(PlayerNpcEntity npc) {
            this.player = new DisplayPlayer((ClientLevel) npc.level(), new GameProfile(npc.getUUID(), npc.getName().getString()));
            this.animation = new ooO0OOoo0o0OoO00oOO0o0o0(this.player);
            this.player.animation = this.animation;
        }

        private void copyFrom(PlayerNpcEntity npc) {
            this.player.mainHandRodCast = npc.isFishingRodCast(InteractionHand.MAIN_HAND);
            this.player.offhandRodCast = npc.isFishingRodCast(InteractionHand.OFF_HAND);
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
                this.player.skinProfile = npc.getProfile();
                this.player.skin = FakePlayerTextureUtils.getPlayerSkin(npc);
                this.player.cape = FakePlayerTextureUtils.getPlayerCape(npc).orElse(null);
                for (EquipmentSlot slot : EquipmentSlot.values()) {
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

    private static final class DisplayPlayer extends RemotePlayer implements OOOOo00O0OOOOooO0o00OooO, FishingRodCastState {
        private boolean mainHandRodCast;
        private boolean offhandRodCast;
        private ooO0OOoo0o0OoO00oOO0o0o0 animation;
        private GameProfile skinProfile;
        private ResourceLocation skin;
        private ResourceLocation cape;
        private boolean inWater;
        private boolean inLava;
        private boolean climbing;
        private float swimAmount;

        private DisplayPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
        }

        @Override
        public boolean isFishingRodCast(InteractionHand hand) {
            return hand == InteractionHand.MAIN_HAND ? this.mainHandRodCast : this.offhandRodCast;
        }

        @Override
        public boolean isSpectator() {
            // The NPC UUID has a spectator tab-list entry. AbstractClientPlayer reads
            // that entry, but YSM must render this living NPC's held-item layers.
            return false;
        }

        @Override
        public GameProfile getGameProfile() {
            // YSM Steve/Alex reads SkinManager using the profile, rather than the
            // skin-texture getter. Keep the resolved NPC profile for that lookup.
            return this.skinProfile != null && this.skinProfile.isComplete() ? this.skinProfile : super.getGameProfile();
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

        @Override
        public @NotNull ResourceLocation getSkinTextureLocation() {
            return this.skin == null ? super.getSkinTextureLocation() : this.skin;
        }

        @Override
        public @Nullable ResourceLocation getCloakTextureLocation() {
            return this.cape;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> OoOo0OooO0OOO0Oo00000o00(Oo0O0ooOo0O00o0OO0OO0O00<T> capability) {
            // The display proxy exposes only YSM's player-animation attachment.
            if (capability == O0Oo000000o0OO0OOoooOoo0.OoOo0OooO0OOO0Oo00000o00 && this.animation != null) {
                return Optional.of((T) this.animation);
            }
            return Optional.empty();
        }
    }
}
