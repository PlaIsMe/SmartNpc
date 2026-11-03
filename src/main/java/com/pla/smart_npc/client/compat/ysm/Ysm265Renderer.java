package com.pla.smart_npc.client.compat.ysm;

import com.elfmcys.yesstevemodel.O0OooOo0oOOoOoOoOooO000o;
import com.elfmcys.yesstevemodel.o0OOO0o0o0OOo000oO00o00O;
import com.elfmcys.yesstevemodel.o0OooO00ooo0OO000O0OoOoO;
import com.elfmcys.yesstevemodel.o0ooO0o0ooo000OOO0o0oo0o;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.pla.smart_npc.client.compat.YesSteveModelClientCompat;
import com.pla.smart_npc.client.renderer.FakePlayerTextureUtils;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/** Pinned to the released 2.6.5 Forge 1.20.1 JAR; obfuscated symbols are its actual API. */
public final class Ysm265Renderer implements YesSteveModelClientCompat.Renderer {
    private final DisplayRenderer renderer;
    private final Map<PlayerNpcEntity, DisplayState> displays = new WeakHashMap<>();

    public Ysm265Renderer(EntityRendererProvider.Context context) {
        this.renderer = new DisplayRenderer(context);
    }

    @Override
    public boolean render(PlayerNpcEntity npc, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        // This is YSM's connection-synchronized cache. Never read its separate local-model registry.
        var serverModel = o0OooO00ooo0OO000O0OoOoO.Oo0Oo0o00O00Oo0OOoOOoooo(npc.getYsmModelId()).orElse(null);
        if (serverModel == null) {
            return false;
        }
        DisplayState display = this.displays.computeIfAbsent(npc, DisplayState::new);
        display.copyFrom(npc);
        if (!display.model.equals(npc.getYsmModelId()) || !display.texture.equals(npc.getYsmModelTexture())) {
            display.model = npc.getYsmModelId();
            display.texture = npc.getYsmModelTexture();
            display.animation.Oo0Oo0o00O00Oo0OOoOOoooo(display.model, display.texture);
        }
        display.animation.oOo0o0000OOOO0OooooO00oo();
        // The YSM loader may still be preparing native resources. Its default-model fallback
        // must never be mistaken for the server's selected model.
        if (display.animation.O0ooooOO0oOo000O0Oo00OOO() != serverModel
                || display.animation.o0OOooo0o0OO00OoOOOo0o0O(partialTick) == null) {
            return false;
        }
        if (display.emoteSequence != npc.getYsmEmoteSequence() || !display.emote.equals(npc.getYsmEmote())) {
            display.animation.oo00O0OoO0o00000O0o000oo();
            display.emote = npc.getYsmEmote();
            display.emoteSequence = npc.getYsmEmoteSequence();
            if (!display.emote.isEmpty()) {
                display.animation.oo0OoO00oOoo000O0000o0oo(display.emote);
            }
        }
        // Call the animatable overload: the proxy is never added to the level or player list.
        this.renderer.Oo0Oo0o00O00Oo0OOoOOoooo(display.animation, yaw, partialTick, poseStack, buffers, light);
        return true;
    }

    private static final class DisplayRenderer extends o0ooO0o0ooo000OOO0o0oo0o {
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
        private final o0OOO0o0o0OOo000oO00o00O animation;
        private String model = "";
        private String texture = "";
        private String emote = "";
        private int emoteSequence = -1;
        private int lastTick = -1;

        private DisplayState(PlayerNpcEntity npc) {
            this.player = new DisplayPlayer((ClientLevel) npc.level(), new GameProfile(npc.getUUID(), npc.getName().getString()));
            this.animation = new o0OOO0o0o0OOo000oO00o00O(this.player);
            this.player.animation = LazyOptional.of(() -> this.animation);
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

    private static final class DisplayPlayer extends RemotePlayer {
        private LazyOptional<o0OOO0o0o0OOo000oO00o00O> animation = LazyOptional.empty();
        private ResourceLocation skin;
        private ResourceLocation cape;
        private boolean inWater;
        private boolean inLava;
        private boolean climbing;
        private float swimAmount;

        private DisplayPlayer(ClientLevel level, GameProfile profile) {
            super(level, profile);
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
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
            // A display proxy must not acquire another mod's player combat renderer. Expose
            // only YSM's animation capability; it has no gameplay or inventory ownership.
            if (capability == O0OooOo0oOOoOoOoOooO000o.Oo0Oo0o00O00Oo0OOoOOoooo && this.animation != null) {
                return this.animation.cast();
            }
            return LazyOptional.empty();
        }
    }
}
