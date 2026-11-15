package com.pla.smart_npc.util;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.ai.AiChatService;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.task.DelayedTask;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

public class ChatUtil {
    // Session-only speech pacing. Weak keys retain neither unloaded entities nor stopped servers.
    private static final Map<Entity, Long> NEXT_LEGACY_REACTION = new WeakHashMap<>();
    private static final Map<MinecraftServer, Long> NEXT_LEGACY_SERVER_REACTION = new WeakHashMap<>();

    public static void joinGame(Entity entity) {
        joinGame(entity, entity.getDisplayName());
    }

    public static void joinGame(Entity entity, String name) {
        joinGame(entity, Component.literal(name));
    }

    public static void leaveGame(Entity entity) {
        if (entity instanceof PlayerNpcEntity npc) {
            AiChatService service = AiChatService.get(npc.getServer());
            if (service != null) service.lifecycleSpeech(npc, false);
            legacyReaction(npc, PlayerNpcChatTemplateLoader.LEAVING_GAME, null, 3);
        }
        broadcastSystemMessage(entity, withNpcPrefix(
                Component.translatable("chat.player_npc.left", entity.getDisplayName())
                        .withStyle(ChatFormatting.YELLOW)
        ));
    }

    public static void callForHelp(PlayerNpcEntity speaker, LivingEntity threat) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.CALL_HELP, threat, threat.getDisplayName());
    }

    public static void teamUpGreeting(PlayerNpcEntity speaker, LivingEntity target) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.TEAMUP_REQUEST, target, target.getDisplayName());
    }

    public static void teamUpAcceptance(PlayerNpcEntity speaker) {
        AiChatService service = AiChatService.get(speaker.getServer());
        if (service != null && service.eventSpeech(speaker, "teamup_accept", null, java.util.List.of("You accepted a team invitation."))) return;
        PlayerNpcChatTemplateLoader.selectMessage(PlayerNpcChatTemplateLoader.TEAMUP_ACCEPT, speaker, null, speaker.getRandom())
                .ifPresent(template -> broadcastNpcChat(speaker, formatTemplate(template)));
    }

    /** Adds event dialogue only in legacy mode; never takes movement flags or changes gameplay. */
    public static void legacyReaction(PlayerNpcEntity speaker, String event, @Nullable Entity target, int chance, Object... args) {
        if (!canChat(speaker) || speaker.level().isClientSide() || !speaker.isAlive() || speaker.isRemoved()
                || speaker.isNoAi() || usesAiSpeech(speaker)) return;
        MinecraftServer server = speaker.getServer();
        if (server.getPlayerList().getPlayers().stream().noneMatch(player -> player.level() == speaker.level())) return;
        long now = server.overworld().getGameTime();
        if (now < NEXT_LEGACY_REACTION.getOrDefault(speaker, 0L)
                || now < NEXT_LEGACY_SERVER_REACTION.getOrDefault(server, 0L)
                || speaker.getRandom().nextInt(Math.max(1, chance)) != 0) return;
        PlayerNpcChatTemplateLoader.selectMessage(event, speaker, target, speaker.getRandom())
                .ifPresent(template -> broadcastNpcChat(speaker, formatTemplate(template, args)));
    }

    public static void legacyHurtReaction(PlayerNpcEntity speaker, float previousHealth) {
        if (speaker.getHealth() >= previousHealth) return;
        boolean low = speaker.getHealth() <= speaker.getMaxHealth() * 0.5F;
        // One opportunity per downward crossing, not one roll for every hit while already low.
        if (low && previousHealth <= speaker.getMaxHealth() * 0.5F) return;
        legacyReaction(speaker, low ? PlayerNpcChatTemplateLoader.LOW_HEALTH : PlayerNpcChatTemplateLoader.HURT_REACTION, null, 3);
    }

    public static void throwTrash(PlayerNpcEntity speaker) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.THROW_TRASH, null);
    }

    public static void missingHomeChest(PlayerNpcEntity speaker) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.MISSING_HOME_CHEST, null);
    }

    public static void brokenBedWhileSleeping(PlayerNpcEntity speaker, Entity breaker) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.BROKEN_BED, breaker, breaker.getDisplayName());
    }

    public static void warnDeath(PlayerNpcEntity victim, Entity threat) {
        broadcastEventWithTargetPolicy(
                victim,
                PlayerNpcChatTemplateLoader.WARN_DEATH,
                threat,
                !isPlayerLikeThreat(threat),
                threat.getDisplayName()
        );
    }

    public static void burnItem(PlayerNpcEntity speaker, @Nullable Entity itemEntity, Component itemName) {
        broadcastEvent(speaker, PlayerNpcChatTemplateLoader.BURN_ITEM, itemEntity, itemName);
    }

    public static void broadcastDeathSummary(PlayerNpcEntity victim, Component deathMessage) {
        broadcastSystemMessage(victim, deathMessage);
    }

    /**
     * Player-like killers get an immediate warning and the delayed NPC reaction. Other deaths
     * retain Minecraft's normal death summary instead.
     */
    public static void reportDeath(PlayerNpcEntity victim, Component deathMessage, Entity killer) {
        if (usesAiSpeech(victim)) {
            broadcastDeathSummary(victim, deathMessage);
            AiChatService.get(victim.getServer()).deathSpeech(victim, killer, deathMessage.getString());
            scheduleLeaveGame(victim);
            return;
        }
        boolean hasConfiguredMonsterReaction = killer != null && (
                PlayerNpcChatTemplateLoader.hasExplicitTargetMatch(
                        PlayerNpcChatTemplateLoader.WARN_DEATH, victim, killer)
                        || PlayerNpcChatTemplateLoader.hasExplicitTargetMatch(
                        PlayerNpcChatTemplateLoader.DEATH_REACTION, victim, killer));
        if (isPlayerLikeThreat(killer) || hasConfiguredMonsterReaction) {
            warnDeath(victim, killer);
            scheduleDeathReaction(victim, killer);
        } else {
            broadcastDeathSummary(victim, deathMessage);
        }
    }

    public static void scheduleKillerTaunt(PlayerNpcEntity killer, Entity victim) {
        if (!canChat(killer)) {
            return;
        }
        boolean requireExplicitTarget = !isPlayerLikeThreat(victim);

        new DelayedTask(Mth.nextInt(RandomSource.create(), 70, 100)) {
            @Override
            public void run() {
                if (!killer.isAlive() || killer.isRemoved()) return;
                broadcastEventWithTargetPolicy(
                        killer,
                        PlayerNpcChatTemplateLoader.KILLER_TAUNT,
                        victim,
                        requireExplicitTarget,
                        victim.getDisplayName()
                );
            }
        };
    }

    public static void scheduleDeathReaction(PlayerNpcEntity victim, Entity killer) {
        boolean requireExplicitTarget = !isPlayerLikeThreat(killer);
        if (!canChat(victim)) return;
        if (requireExplicitTarget && !PlayerNpcChatTemplateLoader.hasExplicitTargetMatch(
                PlayerNpcChatTemplateLoader.DEATH_REACTION, victim, killer)) {
            // A datapack with only warn_death must not suppress the departure notification.
            scheduleLeaveGame(victim);
            return;
        }

        new DelayedTask(Mth.nextInt(RandomSource.create(), 40, 80)) {
            @Override
            public void run() {
                broadcastEventWithTargetPolicy(
                        victim,
                        PlayerNpcChatTemplateLoader.DEATH_REACTION,
                        killer,
                        requireExplicitTarget,
                        killer.getDisplayName()
                );
                scheduleLeaveGame(victim);
            }
        };
    }

    public static boolean isPlayerLikeThreat(Entity threat) {
        return threat instanceof Player || threat instanceof PlayerNpcEntity;
    }

    public static boolean shouldPlayerNpcTauntKill(PlayerNpcEntity killer, Entity victim) {
        return killer != null && victim != null && (isPlayerLikeThreat(victim)
                || usesAiSpeech(killer)
                || PlayerNpcChatTemplateLoader.hasExplicitTargetMatch(
                PlayerNpcChatTemplateLoader.KILLER_TAUNT, killer, victim));
    }

    public static boolean shouldReportPlayerNpcDeath(PlayerNpcEntity victim) {
        return victim != null;
    }

    private static void joinGame(Entity entity, Component name) {
        broadcastSystemMessage(entity, withNpcPrefix(
                Component.translatable("chat.player_npc.joined", name)
                        .withStyle(ChatFormatting.YELLOW)
        ));
        if (entity instanceof PlayerNpcEntity npc) {
            AiChatService service = AiChatService.get(npc.getServer());
            if (service != null) service.lifecycleSpeech(npc, true);
            legacyReaction(npc, PlayerNpcChatTemplateLoader.JOINED_GAME, null, 3);
        }
    }

    private static void scheduleLeaveGame(Entity entity) {
        new DelayedTask(Mth.nextInt(RandomSource.create(), 25, 100)) {
            @Override
            public void run() {
                leaveGame(entity);
            }
        };
    }

    private static void broadcastEvent(
            PlayerNpcEntity speaker,
            String event,
            @Nullable Entity target,
            Object... args
    ) {
        broadcastEventWithTargetPolicy(speaker, event, target, false, args);
    }

    private static void broadcastEventWithTargetPolicy(
            PlayerNpcEntity speaker,
            String event,
            @Nullable Entity target,
            boolean requireExplicitTarget,
            Object... args
    ) {
        if (!canChat(speaker)) {
            return;
        }

        AiChatService service = AiChatService.get(speaker.getServer());
        java.util.List<String> facts = java.util.Arrays.stream(args).map(argument -> argument instanceof Component component ? component.getString() : String.valueOf(argument)).toList();
        // Legacy templates are a fallback, not an admission gate for generated event dialogue.
        if (service != null && service.eventSpeech(speaker, event, target, facts)) return;
        PlayerNpcChatTemplateLoader.selectMessage(event, speaker, target, speaker.getRandom(), requireExplicitTarget)
                .ifPresent(template -> broadcastNpcChat(speaker, formatTemplate(template, args)));
    }
    private static boolean usesAiSpeech(PlayerNpcEntity npc) {
        AiChatService service = AiChatService.get(npc.getServer());
        return service != null && service.ownsEventSpeech(npc);
    }

    private static void broadcastNpcChat(Entity speaker, Component body) {
        if (!canChat(speaker)) {
            return;
        }

        broadcastSystemMessage(speaker, formatNpcChat(speaker, body));
        MinecraftServer server = speaker.getServer();
        long now = server.overworld().getGameTime();
        NEXT_LEGACY_REACTION.put(speaker, now + 900);
        NEXT_LEGACY_SERVER_REACTION.put(server, now + 200);
    }

    /** Shared formatting for the proximity-limited AI channel and legacy event chat. */
    public static Component formatNpcChat(Entity speaker, Component body) {
        return formatNpcChat(speaker.getDisplayName(), body);
    }

    public static Component formatNpcChat(Component speakerName, Component body) {
        net.minecraft.network.chat.MutableComponent message = withNpcPrefix(Component.empty());
        message.append("<")
                .append(speakerName)
                .append("> ")
                .append(body);
        return message;
    }

    private static net.minecraft.network.chat.MutableComponent withNpcPrefix(Component body) {
        net.minecraft.network.chat.MutableComponent message = Component.empty();
        if (SmartNpcConfig.SHOW_NPC_CHAT_PREFIX.get()) {
            message.append(Component.literal("[NPC] ")
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        }
        return message.append(body);
    }

    private static Component formatTemplate(String template, Object... args) {
        net.minecraft.network.chat.MutableComponent result = Component.empty();
        int sequentialArgument = 0;
        int literalStart = 0;

        for (int index = 0; index < template.length(); index++) {
            if (template.charAt(index) != '%' || index + 1 >= template.length()) {
                continue;
            }

            int argumentIndex = -1;
            int tokenEnd = index;
            char next = template.charAt(index + 1);
            if (next == '%') {
                appendLiteral(result, template, literalStart, index);
                result.append("%");
                index++;
                literalStart = index + 1;
                continue;
            }
            if (next == 's') {
                argumentIndex = sequentialArgument++;
                tokenEnd = index + 1;
            } else if (Character.isDigit(next)) {
                int cursor = index + 1;
                while (cursor < template.length() && Character.isDigit(template.charAt(cursor))) {
                    cursor++;
                }
                if (cursor + 1 < template.length()
                        && template.charAt(cursor) == '$'
                        && template.charAt(cursor + 1) == 's') {
                    try {
                        argumentIndex = Integer.parseInt(template.substring(index + 1, cursor)) - 1;
                        tokenEnd = cursor + 1;
                    } catch (NumberFormatException ignored) {
                        argumentIndex = -1;
                    }
                }
            }

            if (argumentIndex < 0 || argumentIndex >= args.length) {
                continue;
            }
            appendLiteral(result, template, literalStart, index);
            appendArgument(result, args[argumentIndex]);
            index = tokenEnd;
            literalStart = index + 1;
        }

        appendLiteral(result, template, literalStart, template.length());
        return result;
    }

    private static void appendLiteral(
            net.minecraft.network.chat.MutableComponent result,
            String template,
            int start,
            int end
    ) {
        if (end > start) {
            result.append(template.substring(start, end));
        }
    }

    private static void appendArgument(net.minecraft.network.chat.MutableComponent result, Object argument) {
        if (argument instanceof Component component) {
            result.append(component);
        } else {
            result.append(String.valueOf(argument));
        }
    }

    private static void broadcastSystemMessage(Entity entity, Component message) {
        MinecraftServer server = entity.level().getServer();
        if (SmartNpcConfig.TURN_ON_NPC_CHAT.get() && server != null) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private static boolean canChat(Entity entity) {
        return SmartNpcConfig.TURN_ON_NPC_CHAT.get() && entity.level().getServer() != null;
    }

}
