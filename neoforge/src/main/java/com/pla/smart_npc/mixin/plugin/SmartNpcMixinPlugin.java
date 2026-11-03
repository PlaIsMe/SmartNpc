package com.pla.smart_npc.mixin.plugin;

import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class SmartNpcMixinPlugin implements IMixinConfigPlugin {
    private static boolean isModLoadedEarly(String modId) {
        LoadingModList loadingModList = FMLLoader.getLoadingModList();
        return loadingModList != null && loadingModList.getModFileById(modId) != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.startsWith("com.pla.smart_npc.mixin.epicfight.")) {
            return isModLoadedEarly("epicfight");
        }
        if (mixinClassName.startsWith("com.pla.smart_npc.mixin.combat_evolution.")) {
            return isModLoadedEarly("combat_evolution");
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
