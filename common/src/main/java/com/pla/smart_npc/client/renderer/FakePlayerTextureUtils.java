package com.pla.smart_npc.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.pla.smart_npc.clazz.FakePlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringUtil;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class FakePlayerTextureUtils {
    private static final Map<UUID, SkinType> SKIN_TYPE_CACHE = new ConcurrentHashMap<>();

    private FakePlayerTextureUtils() {
    }

    public static SkinType getPlayerSkinType(GameProfile profile) {
        if (!isComplete(profile)) {
            return SkinType.DEFAULT;
        }

        SkinType cachedType = SKIN_TYPE_CACHE.get(profile.getId());
        if (cachedType != null) {
            return cachedType;
        }

        Optional<PlayerSkin> resolvedSkin = getResolvedSkin(profile);
        if (resolvedSkin.isEmpty()) {
            return SkinType.DEFAULT;
        }
        SkinType skinType = resolvedSkin.get().model() == PlayerSkin.Model.SLIM
                ? SkinType.SLIM
                : SkinType.DEFAULT;
        SKIN_TYPE_CACHE.put(profile.getId(), skinType);
        return skinType;
    }

    public static ResourceLocation getPlayerSkin(FakePlayer entity) {
        return getTexture(entity, MinecraftProfileTexture.Type.SKIN).orElseGet(() -> {
            GameProfile profile = entity.getProfile();
            return isComplete(profile)
                    ? DefaultPlayerSkin.get(profile).texture()
                    : DefaultPlayerSkin.getDefaultTexture();
        });
    }

    public static Optional<ResourceLocation> getPlayerCape(FakePlayer entity) {
        return getTexture(entity, MinecraftProfileTexture.Type.CAPE);
    }

    private static Optional<ResourceLocation> getTexture(FakePlayer entity, MinecraftProfileTexture.Type type) {
        if (entity.isTextureAvailable(type)) {
            return Optional.ofNullable(entity.getTexture(type));
        }

        GameProfile profile = entity.getProfile();
        if (!isComplete(profile)) {
            return Optional.empty();
        }

        PlayerSkin skin = getResolvedSkin(profile).orElse(null);
        if (skin == null) {
            // SkinManager resolves and downloads textures asynchronously. Do not cache its
            // temporary default skin; a later render pass will observe the completed lookup.
            return Optional.empty();
        }
        ResourceLocation location = switch (type) {
            case SKIN -> skin.texture();
            case CAPE -> skin.capeTexture();
            case ELYTRA -> skin.elytraTexture();
        };
        if (location != null) {
            entity.setTexture(type, location);
        }
        return Optional.ofNullable(location);
    }

    private static Optional<PlayerSkin> getResolvedSkin(GameProfile profile) {
        CompletableFuture<PlayerSkin> lookup = Minecraft.getInstance().getSkinManager().getOrLoad(profile);
        return Optional.ofNullable(lookup.getNow(null));
    }

    private static boolean isComplete(GameProfile profile) {
        return profile != null && profile.getId() != null && !StringUtil.isNullOrEmpty(profile.getName());
    }

    public enum SkinType {
        DEFAULT,
        SLIM
    }
}
