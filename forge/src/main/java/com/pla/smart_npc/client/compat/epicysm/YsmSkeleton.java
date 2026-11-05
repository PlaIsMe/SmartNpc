package com.pla.smart_npc.client.compat.epicysm;

import com.elfmcys.yesstevemodel.OOOO0O0O000O000000oOOO0o;
import com.elfmcys.yesstevemodel.Oo0o00oOOo0OO000000O0oO0;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The selected display player's native model, never a search of YSM's global caches. */
record YsmSkeleton(List<Bone> bones) {
    // YSM exposes chains for attachment points, which need not mention every body bone.
    // Standard humanoid fallbacks follow EpicYSM's live skeleton reader (Argorice, MIT).
    private static final Map<String, List<String>> KNOWN_PARENTS = Map.ofEntries(
            Map.entry("mallbody", List.of("root")),
            Map.entry("allbody", List.of("mallbody", "root")),
            Map.entry("upbody", List.of("allbody", "mallbody", "root")),
            Map.entry("mupbody", List.of("upbody", "allbody")),
            Map.entry("downbody", List.of("allbody", "mallbody", "root")),
            Map.entry("mupperbody", List.of("upbody", "mupbody", "allbody")),
            Map.entry("upperbody", List.of("mupperbody", "upbody", "allbody")),
            Map.entry("breast", List.of("upperbody", "mupperbody")),
            Map.entry("collar", List.of("upperbody", "mupperbody")),
            Map.entry("arm", List.of("upperbody", "mupperbody", "upbody")),
            Map.entry("leftarm", List.of("arm", "upperbody", "mupperbody", "upbody")),
            Map.entry("rightarm", List.of("arm", "upperbody", "mupperbody", "upbody")),
            Map.entry("leftforearm", List.of("leftarm")),
            Map.entry("rightforearm", List.of("rightarm")),
            Map.entry("lefthand", List.of("leftforearm", "leftarm")),
            Map.entry("righthand", List.of("rightforearm", "rightarm")),
            Map.entry("lefthandlocator", List.of("lefthand", "leftforearm")),
            Map.entry("righthandlocator", List.of("righthand", "rightforearm")),
            Map.entry("allhead", List.of("upperbody", "mupperbody", "upbody")),
            Map.entry("mhead", List.of("allhead", "upperbody", "upbody")),
            Map.entry("head", List.of("mhead", "allhead", "upperbody", "upbody")),
            Map.entry("hair", List.of("head", "mhead", "allhead")),
            Map.entry("face", List.of("head", "mhead", "allhead")),
            Map.entry("leg", List.of("downbody", "allbody")),
            Map.entry("leftleg", List.of("leg", "downbody", "allbody")),
            Map.entry("rightleg", List.of("leg", "downbody", "allbody")),
            Map.entry("leftlowerleg", List.of("leftleg")),
            Map.entry("rightlowerleg", List.of("rightleg")),
            Map.entry("leftfoot", List.of("leftlowerleg", "leftleg")),
            Map.entry("rightfoot", List.of("rightlowerleg", "rightleg")));

    record Bone(String name, float pivotX, float pivotY, float pivotZ, @Nullable String parent) {
    }

    static YsmSkeleton read(OOOO0O0O000O000000oOOO0o model) {
        Map<String, String> parents = new HashMap<>();
        addParents(model.Ooooo0oooO0oooOOOoO0000O(), parents);
        addParents(model.oooooooOOoOOoO00OooOo00O(), parents);
        addParents(model.Oo00o0OooOOo0ooOoo0oO0o0(), parents);
        Map<String, String> spelling = new HashMap<>();
        for (var definition : model.oOOOo0OOO0ooooo0O00OO0o0().Oo0Oo0o00O00Oo0OOoOOoooo()) {
            String name = definition.Oo0Oo0o00O00Oo0OOoOOoooo();
            spelling.put(name.toLowerCase(Locale.ROOT), name);
        }
        List<Bone> bones = new ArrayList<>();
        // Definition order also determines the twelve-float TRS slot in the native model.
        for (var definition : model.oOOOo0OOO0ooooo0O00OO0o0().Oo0Oo0o00O00Oo0OOoOOoooo()) {
            var bone = model.O00OOOooOoooOoo0o0o0oO0O().get(definition.o0OOooo0o0OO00OoOOOo0o0O());
            String name = bone.oOOo0Ooo0oOoo0O0OOOOo0oo();
            String parent = parents.get(name);
            if (parent == null) {
                for (String candidate : KNOWN_PARENTS.getOrDefault(name.toLowerCase(Locale.ROOT), List.of())) {
                    if (spelling.containsKey(candidate)) {
                        parent = spelling.get(candidate);
                        break;
                    }
                }
            }
            bones.add(new Bone(name, bone.o0OOO0o0o0OOo000oO00o00O(), bone.O0OooOo0oOOoOoOoOooO000o(), bone.ooOO000o0O0OOOoO0Oo0o0Oo(), parent));
        }
        return new YsmSkeleton(List.copyOf(bones));
    }

    private static void addParents(List<List<Oo0o00oOOo0OO000000O0oO0>> chains, Map<String, String> parents) {
        for (var chain : chains) {
            for (int i = 1; i < chain.size(); i++) {
                parents.putIfAbsent(chain.get(i).oOOo0Ooo0oOoo0O0OOOOo0oo(), chain.get(i - 1).oOOo0Ooo0oOoo0O0OOOOo0oo());
            }
        }
    }
}
