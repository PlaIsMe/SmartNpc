package com.pla.smart_npc.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-owned observations and social combat decisions. Model prose never grants target authority. */
public final class AiWorldInteractions {
    public record Observation(UUID target, int expiresAt, boolean evade) {}
    private record Pair(UUID a, UUID b) {
        static Pair of(Entity a, Entity b) { return a.getUUID().compareTo(b.getUUID()) < 0 ? new Pair(a.getUUID(), b.getUUID()) : new Pair(b.getUUID(), a.getUUID()); }
    }
    private record Fact(String outcome, int expiresAt) {}
    private record Position(double x, double y, double z, int tick) {}
    private record SeenBlock(BlockPos position, String type, int expiresAt) {}
    private final AiChatService service;
    private final AiConfig config;
    private final Map<Pair, Integer> truces = new LinkedHashMap<>();
    private final Map<UUID, Observation> observations = new LinkedHashMap<>();
    private final Map<UUID, Fact> outcomes = new LinkedHashMap<>();
    private final Map<UUID, Integer> opportunities = new LinkedHashMap<>();
    private final Map<UUID, Position> positions = new LinkedHashMap<>();
    private final Map<UUID, Integer> arrivals = new LinkedHashMap<>();
    private final Map<UUID, UUID> commandArrivals = new LinkedHashMap<>();
    private final Map<UUID, Integer> arrivalCooldowns = new LinkedHashMap<>();
    private final Map<UUID, SeenBlock> seenBlocks = new LinkedHashMap<>();
    private int nextAmbient;
    private int cursor;

    AiWorldInteractions(AiChatService service, AiConfig config) { this.service = service; this.config = config; }
    private static int now(Entity entity) { return entity.getServer().getTickCount(); }
    void arrived(PlayerNpcEntity npc, ServerPlayer player) {
        int tick = now(npc);
        if (!config.eventReactions || tick < arrivalCooldowns.getOrDefault(npc.getUUID(), 0)) return;
        put(arrivalCooldowns, npc.getUUID(), tick + 300);
        put(commandArrivals, player.getUUID(), npc.getUUID());
        put(arrivals, player.getUUID(), tick + 200);
    }
    private static <K, V> void put(Map<K, V> map, K key, V value) {
        if (map.size() >= 256 && !map.containsKey(key)) map.remove(map.keySet().iterator().next());
        map.put(key, value);
    }
    public Observation observation(PlayerNpcEntity npc) {
        Observation result = observations.get(npc.getUUID());
        return result != null && now(npc) < result.expiresAt() ? result : null;
    }
    void observe(PlayerNpcEntity npc, LivingEntity target) {
        put(observations, npc.getUUID(), new Observation(target.getUUID(), now(npc) + 600, false));
    }
    public boolean blocksTarget(PlayerNpcEntity npc, LivingEntity other) {
        return service.ready() && other != null && now(npc) < truces.getOrDefault(Pair.of(npc, other), 0);
    }
    public void attacked(PlayerNpcEntity npc, Entity attacker) {
        if (attacker != null) truces.remove(Pair.of(npc, attacker)); // A new hit breaks the agreement.
    }
    private boolean canFight(PlayerNpcEntity npc, LivingEntity other) {
        return config.actionsEnabled && npc.isAlive() && !npc.isNoAi() && !npc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                && other != null && other != npc && other.isAlive() && other.level() == npc.level()
                && !npc.isAlliedTo(other) && !other.isAlliedTo(npc) && !blocksTarget(npc, other)
                && npc.distanceToSqr(other) <= 256 && npc.hasLineOfSight(other)
                && (!(other instanceof ServerPlayer player) || !player.isCreative() && !player.isSpectator()
                && npc.getServer().isPvpAllowed() && AiPlayerPreferences.get(npc.getServer()).allows(player.getUUID()));
    }
    private boolean startFight(PlayerNpcEntity npc, LivingEntity other) {
        if (!canFight(npc, other)) return false;
        npc.setTarget(other);
        return npc.getTarget() == other;
    }
    private void stopFight(PlayerNpcEntity npc, LivingEntity rival) {
        put(truces, Pair.of(npc, rival), now(npc) + 1200);
        npc.setTarget(null);
        npc.setLastHurtByMob(null);
        npc.getNavigation().stop();
        if (rival instanceof PlayerNpcEntity other && other.getTarget() == npc) {
            other.setTarget(null);
            other.setLastHurtByMob(null);
            other.getNavigation().stop();
        }
    }
    public static boolean attackRequest(String text) { return AiDialogueIntent.attack(text); }
    private static List<LivingEntity> visible(PlayerNpcEntity npc) {
        // Loaded entities only; at most 24 visibility checks per admitted observation.
        return npc.level().getEntitiesOfClass(LivingEntity.class, npc.getBoundingBox().inflate(16), e -> e != npc && e.isAlive()
                && !(e instanceof ServerPlayer p && p.isSpectator())).stream().sorted(Comparator.comparingDouble(npc::distanceToSqr))
                .limit(24).filter(npc::hasLineOfSight).toList();
    }
    private static String kind(LivingEntity entity) { return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(); }
    private static boolean mentioned(LivingEntity entity, String text) {
        String name = entity.getName().getString().toLowerCase(Locale.ROOT);
        String type = kind(entity);
        String chinese = switch (type) { case "spider" -> "蜘蛛"; case "skeleton" -> "骷髅"; case "zombie" -> "僵尸"; case "creeper" -> "苦力怕"; case "cow" -> "牛"; case "pig" -> "猪"; case "enderman" -> "末影人"; default -> ""; };
        String vietnamese = switch (type) { case "spider" -> "nhện"; case "skeleton" -> "bộ xương"; case "zombie" -> "thây ma"; case "creeper" -> "quái nổ"; case "cow" -> "bò"; case "pig" -> "heo|lợn"; case "enderman" -> "người ender"; default -> ""; };
        return Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + Pattern.quote(name) + "|" + Pattern.quote(type.replace('_', ' ')) + ")(?![\\p{L}\\p{N}_])").matcher(text).find()
                || !chinese.isEmpty() && text.contains(chinese)
                || !vietnamese.isEmpty() && Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + vietnamese + ")(?![\\p{L}\\p{N}_])").matcher(text).find();
    }
    /** Returns true only for a deliberate silent decision. Called once, never during inference repair. */
    boolean hear(PlayerNpcEntity npc, LivingEntity speaker, String message) {
        outcomes.remove(npc.getUUID());
        String text = message.toLowerCase(Locale.ROOT);
        if (!config.actionsEnabled || AiDialogueIntent.smallTalk(text)) return false;
        String outcome = null;
        boolean silent = false;
        if (AiDialogueIntent.peace(text) && npc.getTarget() != null) {
            LivingEntity rival = npc.getTarget();
            boolean accepted = npc.getRandom().nextFloat() < 0.6F;
            if (accepted) stopFight(npc, rival);
            outcome = accepted ? "peace_accepted" : "peace_refused";
            silent = npc.getRandom().nextFloat() < 0.25F;
        } else if (AiDialogueIntent.insult(text, npc.getName().getString())) {
            // Direct player insults retaliate when eligible. NPC banter sometimes stays verbal.
            boolean attack = (speaker instanceof ServerPlayer || npc.getRandom().nextInt(4) == 0) && startFight(npc, speaker);
            outcome = attack ? "insult_retaliation" : "insult_no_attack";
        } else if (speaker instanceof ServerPlayer && attackRequest(text)) {
            List<LivingEntity> seen = visible(npc);
            LivingEntity target = seen.stream().filter(e -> e != speaker && mentioned(e, text)).findFirst().orElse(null);
            if (text.matches(".*\\b(?:kill|attack|fight|hit) me\\b.*") || text.contains("打我") || text.matches(".*(?:giết|đánh|đấm|tấn công) (?:tao|tôi|tớ|t)(?:[ !?.].*|$)")) target = speaker;
            Observation previous = observation(npc);
            if (target == null && previous != null && (text.matches(".*\\b(?:him|her|it|them)\\b.*") || text.contains("他") || text.contains("它") || text.matches(".*(?:nó|thằng đó|con đó|tụi nó).*"))) {
                target = seen.stream().filter(e -> e.getUUID().equals(previous.target())).findFirst().orElse(null);
            }
            boolean accepted = target != null && npc.getRandom().nextFloat() < 0.65F && startFight(npc, target);
            outcome = target == null ? "attack_target_missing" : accepted ? "attack_accepted" : "attack_refused";
        } else if (!AiDialogueIntent.gift(text) && (text.matches(".*\\b(?:see|look|there|nearby)\\b.*") || text.contains("看到") || text.contains("那边") || text.contains("看看") || text.matches(".*(?:nhìn|thấy|bên kia|đằng kia|gần đây).*"))) {
            List<LivingEntity> seen = visible(npc);
            LivingEntity target = seen.stream().filter(e -> e != speaker && mentioned(e, text)).findFirst().orElse(null);
            if (target != null) {
                put(observations, npc.getUUID(), new Observation(target.getUUID(), now(npc) + 200, false));
                outcome = "observed_requested_entity: " + target.getName().getString();
            } else {
                LivingEntity alternative = seen.stream().filter(e -> e != speaker).findFirst().orElse(null);
                if (alternative != null) put(observations, npc.getUUID(), new Observation(alternative.getUUID(), now(npc) + 200, false));
                outcome = "requested_entity_not_observed" + (alternative == null ? "" : "; visible instead: " + alternative.getName().getString());
            }
        }
        if (outcome != null) put(outcomes, npc.getUUID(), new Fact(outcome, now(npc) + 1200));
        return silent;
    }
    void augment(PlayerNpcEntity npc, Entity speaker, JsonObject context) {
        JsonObject world = context.getAsJsonObject("world");
        Biome.Precipitation precipitation = npc.level().getBiome(npc.blockPosition()).value().getPrecipitationAt(npc.blockPosition());
        boolean exposed = npc.level().canSeeSky(npc.blockPosition());
        String weather = npc.level().isRaining() && precipitation != Biome.Precipitation.NONE
                ? precipitation == Biome.Precipitation.SNOW ? "snow" : "rain" : "clear";
        world.addProperty("weather", weather);
        world.addProperty("exposed", exposed);
        world.addProperty("thunder", npc.level().isThundering());
        context.getAsJsonObject("npc").addProperty("cautious", npc.hasInterest(PlayerNpcInterest.CAUTIOUS));
        JsonObject self = context.getAsJsonObject("npc");
        self.add("position", coordinates(npc));
        self.addProperty("dimension", npc.level().dimension().location().toString());
        self.addProperty("combat_weapon", BuiltInRegistries.ITEM.getKey(npc.getMainWeaponItem().getItem()).toString());
        if (npc.isYsmActive()) {
            JsonObject appearance = new JsonObject();
            appearance.addProperty("model", npc.getYsmModelId());
            appearance.addProperty("emote", npc.getYsmEmote());
            self.add("ysm", appearance);
        }
        context.getAsJsonObject("speaker").addProperty("creative", speaker instanceof ServerPlayer player && player.isCreative());
        JsonArray seen = new JsonArray();
        List<LivingEntity> visible = visible(npc);
        visible.stream().limit(6).forEach(e -> seen.add(e.getName().getString() + " (" + kind(e) + ")"));
        world.add("visible_entities", seen);
        Observation observation = observation(npc);
        if (observation != null) visible.stream().filter(e -> e.getUUID().equals(observation.target())).findFirst().ifPresent(e -> {
            JsonObject observed = new JsonObject();
            observed.addProperty("name", e.getName().getString());
            observed.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            observed.add("position", coordinates(e));
            world.add("observed_entity", observed);
        });
        SeenBlock block = seenBlocks.get(npc.getUUID());
        if (block != null && now(npc) < block.expiresAt() && npc.level().hasChunkAt(block.position())
                && npc.distanceToSqr(Vec3.atCenterOf(block.position())) <= 256
                && BuiltInRegistries.BLOCK.getKey(npc.level().getBlockState(block.position()).getBlock()).toString().equals(block.type())) {
            BlockHitResult ray = npc.level().clip(new ClipContext(npc.getEyePosition(), Vec3.atCenterOf(block.position()), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc));
            if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(block.position())) {
                JsonObject observed = new JsonObject();
                observed.addProperty("type", block.type());
                JsonArray pos = new JsonArray();
                pos.add(block.position().getX()); pos.add(block.position().getY()); pos.add(block.position().getZ());
                observed.add("position", pos);
                world.add("observed_block", observed);
            }
        }
        Fact fact = outcomes.get(npc.getUUID());
        if (fact != null && now(npc) < fact.expiresAt() && !context.getAsJsonObject("speaker").get("message").getAsString().equals("WORLD_EVENT")) context.addProperty("interaction_result", fact.outcome());
    }
    private static JsonArray coordinates(Entity entity) {
        JsonArray result = new JsonArray();
        result.add(entity.blockPosition().getX());
        result.add(entity.blockPosition().getY());
        result.add(entity.blockPosition().getZ());
        return result;
    }
    boolean hasDecision(PlayerNpcEntity npc) {
        Fact fact = outcomes.get(npc.getUUID());
        return fact != null && now(npc) < fact.expiresAt();
    }
    static boolean smallTalk(String text) {
        return AiDialogueIntent.smallTalk(text);
    }
    /** One rotating observer per second, shared global cooldown; no per-NPC world scans each tick. */
    void tick(ServerPlayer viewer, List<PlayerNpcEntity> nearby, int tick) {
        truces.entrySet().removeIf(e -> tick >= e.getValue());
        observations.entrySet().removeIf(e -> tick >= e.getValue().expiresAt());
        outcomes.entrySet().removeIf(e -> tick >= e.getValue().expiresAt());
        seenBlocks.entrySet().removeIf(e -> tick >= e.getValue().expiresAt());
        if (nearby.isEmpty() || !config.eventReactions) return;
        Position before = positions.get(viewer.getUUID());
        put(positions, viewer.getUUID(), new Position(viewer.getX(), viewer.getY(), viewer.getZ(), tick));
        if (before != null && tick - before.tick() <= 100 && viewer.position().distanceToSqr(new Vec3(before.x(), before.y(), before.z())) > 64)
            put(arrivals, viewer.getUUID(), tick + 100);
        arrivals.entrySet().removeIf(entry -> tick >= entry.getValue());
        commandArrivals.keySet().removeIf(id -> !arrivals.containsKey(id));
        UUID commanded = commandArrivals.get(viewer.getUUID());
        if (commanded != null) {
            PlayerNpcEntity arrivedAt = nearby.stream().filter(n -> n.getUUID().equals(commanded)).findFirst().orElse(null);
            if (arrivedAt != null && arrivedAt.distanceToSqr(viewer) <= 16 && arrivedAt.hasLineOfSight(viewer)
                    && service.arrivalSpeech(arrivedAt, viewer)) {
                commandArrivals.remove(viewer.getUUID());
                arrivals.remove(viewer.getUUID());
                return;
            }
        }
        boolean arrived = arrivals.containsKey(viewer.getUUID());
        PlayerNpcEntity npc = arrived ? nearby.stream().min(Comparator.comparingDouble(viewer::distanceToSqr)).orElseThrow() : nearby.get(Math.floorMod(cursor++, nearby.size()));
        if (tick < nextAmbient || tick < opportunities.getOrDefault(npc.getUUID(), 0) || !service.speechAvailable(npc)) return;
        String event = null;
        Entity subject = viewer;
        List<String> facts = List.of();
        if (arrived && npc.distanceToSqr(viewer) <= 16 && npc.hasLineOfSight(viewer)) {
            arrivals.remove(viewer.getUUID());
            event = "sudden_arrival"; facts = List.of(viewer.getName().getString() + " suddenly appeared right beside you.");
        } else if (npc.getRandom().nextInt(5) == 0) {
            List<LivingEntity> seen = visible(npc);
            LivingEntity archer = seen.stream().filter(e -> e.isUsingItem() && e.getUseItem().getItem() instanceof BowItem
                    && e.getLookAngle().dot(npc.getEyePosition().subtract(e.getEyePosition()).normalize()) > 0.97).findFirst().orElse(null);
            if (archer != null) {
                subject = archer;
                boolean attack = npc.getRandom().nextBoolean() && startFight(npc, archer);
                if (!attack && config.actionsEnabled && npc.getTarget() == null) put(observations, npc.getUUID(), new Observation(archer.getUUID(), tick + 100, true));
                event = "bow_aimed"; facts = List.of(archer.getName().getString() + " is drawing a bow and aiming at you.", attack ? "You chose to fight back." : "You did not start a fight. You may try to move out of the aim.");
            } else if (npc.getTarget() != null && npc.getHealth() <= npc.getMaxHealth() * 0.6F) {
                subject = npc.getTarget(); event = "battle_plea";
                facts = List.of("You are fighting " + subject.getName().getString() + ".", "Ask them to stop fighting. They have not agreed yet.");
            } else {
                LivingEntity fighter = seen.stream().filter(e -> e instanceof Mob mob && mob.getTarget() != null || e.getLastHurtMob() != null && e.tickCount - e.getLastHurtMobTimestamp() < 80).findFirst().orElse(null);
                LivingEntity rival = fighter instanceof Mob mob && mob.getTarget() != null ? mob.getTarget() : fighter == null ? null : fighter.getLastHurtMob();
                if (rival != null && rival != npc && rival.isAlive() && seen.contains(rival)) {
                    subject = fighter; event = "witness_fight";
                    facts = List.of(fighter.getName().getString() + " is fighting or pursuing " + rival.getName().getString() + ".", "You can see both. You are an observer, not either fighter.");
                } else if (npc.level() instanceof ServerLevel level && level.getRaidAt(npc.blockPosition()) != null && level.getRaidAt(npc.blockPosition()).isActive()) {
                    subject = null; event = "raid_observed";
                    facts = List.of("An active raid is occurring at your current location.", "You have not saved the village or earned Hero of the Village.");
                } else if (seen.stream().anyMatch(e -> e instanceof Villager)) {
                    subject = seen.stream().filter(e -> e instanceof Villager).findFirst().orElseThrow();
                    observe(npc, (LivingEntity) subject);
                    event = "notice_villager";
                    facts = List.of("You can see a villager nearby.", "No trades or professions have been inspected. No villager has been trapped.");
                } else {
                    BlockHitResult hit = npc.level().clip(new ClipContext(npc.getEyePosition(), npc.getEyePosition().add(npc.getLookAngle().scale(8)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, npc));
                    if (hit.getType() == HitResult.Type.BLOCK) {
                        var state = npc.level().getBlockState(hit.getBlockPos());
                        boolean ore = state.getTags().anyMatch(tag -> tag.location().getPath().equals("ores") || tag.location().getPath().startsWith("ores/"))
                                || BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft") && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().endsWith("_ore");
                        if (ore) {
                            put(seenBlocks, npc.getUUID(), new SeenBlock(hit.getBlockPos().immutable(), BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), tick + 600));
                            subject = null; event = "ore_discovered";
                            facts = List.of("You are looking at ore: " + BuiltInRegistries.BLOCK.getKey(state.getBlock()), "Ore block coordinates: " + hit.getBlockPos().toShortString(), "Seeing ore does not mean you can mine it or have collected it.");
                        }
                    }
                    if (event == null && viewer.isCreative() && npc.hasLineOfSight(viewer)) {
                        event = "notice_creative"; facts = List.of(viewer.getName().getString() + " is in creative mode.", viewer.getAbilities().flying ? "They are currently flying." : "They can fly but are not currently flying.", "You may ask for items or creative help. Nothing has been given or summoned.");
                    }
                }
            }
        }
        if (event != null) {
            put(opportunities, npc.getUUID(), tick + 1200);
            nextAmbient = tick + 300;
            // Reactions may be silent; their bounded behavioral decision still applies.
            if (npc.getRandom().nextInt(4) != 0) service.eventSpeech(npc, event, subject, facts);
        }
    }
    void answerPlea(PlayerNpcEntity speaker, PlayerNpcEntity rival) {
        if (!config.actionsEnabled || rival.getTarget() != speaker && speaker.getTarget() != rival) return;
        boolean accepted = rival.getRandom().nextFloat() < 0.6F;
        if (accepted) stopFight(rival, speaker);
        if (rival.getRandom().nextInt(4) != 0) service.eventSpeech(rival, accepted ? "peace_accepted" : "peace_refused", speaker,
                List.of(accepted ? "Both NPCs agreed to stop attacking each other." : "You refused the request to stop fighting."));
    }
    void witnessSpeech(Entity fighter, String say) {
        if (!(fighter instanceof PlayerNpcEntity npc) || npc.getTarget() == null || !AiDialogueIntent.peace(say)) return;
        LivingEntity rival = npc.getTarget();
        boolean accepted = config.actionsEnabled && npc.getRandom().nextFloat() < 0.6F;
        if (accepted) stopFight(npc, rival);
        if (npc.getRandom().nextInt(4) != 0) service.eventSpeech(npc, accepted ? "peace_accepted" : "peace_refused", rival,
                List.of(accepted ? "You accepted a nearby observer's request for a truce." : "You refused a nearby observer's request for a truce."));
    }
    void clear() { truces.clear(); observations.clear(); outcomes.clear(); opportunities.clear(); positions.clear(); arrivals.clear(); commandArrivals.clear(); arrivalCooldowns.clear(); seenBlocks.clear(); nextAmbient = 0; }
    void forget(UUID id) {
        observations.remove(id); outcomes.remove(id); opportunities.remove(id); positions.remove(id); arrivals.remove(id);
        commandArrivals.entrySet().removeIf(entry -> entry.getKey().equals(id) || entry.getValue().equals(id));
        arrivalCooldowns.remove(id);
        seenBlocks.remove(id);
        truces.keySet().removeIf(pair -> pair.a().equals(id) || pair.b().equals(id));
    }
}
