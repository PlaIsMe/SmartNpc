package com.pla.smart_npc.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.pla.smart_npc.clazz.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringUtil;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FakePlayerTextureUtils {
    private static final Map<UUID, SkinType> SKIN_TYPE_CACHE = new ConcurrentHashMap<>();

    private FakePlayerTextureUtils() {
    }

    public static SkinType getPlayerSkinType(GameProfile profile) {
        if (!isComplete(profile)) {
            return SkinType.DEFAULT;
        }

        return SKIN_TYPE_CACHE.computeIfAbsent(profile.id(), ignored ->
                resolveSkin(profile).model() == PlayerModelType.SLIM
                        ? SkinType.SLIM
                        : SkinType.DEFAULT);
    }

    public static PlayerSkin getPlayerSkinData(FakePlayer entity) {
        GameProfile profile = entity.getProfile();
        PlayerSkin skin = isComplete(profile) ? resolveSkin(profile) : DefaultPlayerSkin.getDefaultSkin();
        entity.setTexture(MinecraftProfileTexture.Type.SKIN, skin.body().texturePath());
        if (skin.cape() != null) entity.setTexture(MinecraftProfileTexture.Type.CAPE, skin.cape().texturePath());
        if (skin.elytra() != null) entity.setTexture(MinecraftProfileTexture.Type.ELYTRA, skin.elytra().texturePath());
        return skin;
    }

    public static Identifier getPlayerSkin(FakePlayer entity) {
        return getTexture(entity, MinecraftProfileTexture.Type.SKIN).orElseGet(() -> {
            GameProfile profile = entity.getProfile();
            return isComplete(profile)
                    ? getPlayerSkinData(entity).body().texturePath()
                    : DefaultPlayerSkin.getDefaultTexture();
        });
    }

    public static Optional<Identifier> getPlayerCape(FakePlayer entity) {
        return getTexture(entity, MinecraftProfileTexture.Type.CAPE);
    }

    private static Optional<Identifier> getTexture(FakePlayer entity, MinecraftProfileTexture.Type type) {
        if (entity.isTextureAvailable(type)) {
            return Optional.ofNullable(entity.getTexture(type));
        }

        GameProfile profile = entity.getProfile();
        if (!isComplete(profile)) {
            return Optional.empty();
        }

        PlayerSkin skin = resolveSkin(profile);
        Identifier location = switch (type) {
            case SKIN -> skin.body().texturePath();
            case CAPE -> skin.cape() == null ? null : skin.cape().texturePath();
            case ELYTRA -> skin.elytra() == null ? null : skin.elytra().texturePath();
        };
        if (location != null) {
            entity.setTexture(type, location);
        }
        return Optional.ofNullable(location);
    }

    private static boolean isComplete(GameProfile profile) {
        return profile != null && profile.id() != null && !StringUtil.isNullOrEmpty(profile.name());
    }

    private static PlayerSkin resolveSkin(GameProfile profile) {
        return Minecraft.getInstance().getSkinManager().createLookup(profile, false).get();
    }

    public enum SkinType {
        DEFAULT,
        SLIM
    }
}
