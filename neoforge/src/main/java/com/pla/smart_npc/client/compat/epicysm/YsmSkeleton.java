package com.pla.smart_npc.client.compat.epicysm;

import com.elfmcys.yesstevemodel.o0ooO0ooO00oo0o00Oo00000;
import com.elfmcys.yesstevemodel.ooOO0OoOoO0o0o00oO0oo00o;
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

    static YsmSkeleton read(o0ooO0ooO00oo0o00Oo00000 model) {
        Map<String, String> parents = new HashMap<>();
        addParents(model.O0o0OoOOooOo0O0OOoo0Oo00(), parents);
        addParents(model.oOOO00ooO0oOOoOOo0OoOOOo(), parents);
        addParents(model.ooOooOO0oO00o00o0o0oOOoO(), parents);
        Map<String, String> spelling = new HashMap<>();
        for (var definition : model.OOo0o0000Ooo0o00OO0oOOoO().oOo0OO0O0o000OO0O000oo0o()) {
            String name = definition.oOo0OO0O0o000OO0O000oo0o();
            spelling.put(name.toLowerCase(Locale.ROOT), name);
        }
        List<Bone> bones = new ArrayList<>();
        // Definition order also determines the twelve-float TRS slot in the native model.
        for (var definition : model.OOo0o0000Ooo0o00OO0oOOoO().oOo0OO0O0o000OO0O000oo0o()) {
            var bone = model.OO000o0ooOooooOOOOO0Ooo0().get(definition.oOoo00O0o0oO0o0oO00OO0O0());
            String name = bone.OOO0oooOOo00OOooo0OooOOo();
            String parent = parents.get(name);
            if (parent == null) {
                for (String candidate : KNOWN_PARENTS.getOrDefault(name.toLowerCase(Locale.ROOT), List.of())) {
                    if (spelling.containsKey(candidate)) {
                        parent = spelling.get(candidate);
                        break;
                    }
                }
            }
            bones.add(new Bone(name, bone.OOo0O00Ooo00O0Ooo0OoOo0o(), bone.OO0Oo0O00OOOo0oOo0oooooO(), bone.O0O0Oo0Oooo0OOoOOO0ooo0O(), parent));
        }
        return new YsmSkeleton(List.copyOf(bones));
    }

    private static void addParents(List<List<ooOO0OoOoO0o0o00oO0oo00o>> chains, Map<String, String> parents) {
        for (var chain : chains) {
            for (int i = 1; i < chain.size(); i++) {
                parents.putIfAbsent(chain.get(i).OOO0oooOOo00OOooo0OooOOo(), chain.get(i - 1).OOO0oooOOo00OOooo0OooOOo());
            }
        }
    }
}
