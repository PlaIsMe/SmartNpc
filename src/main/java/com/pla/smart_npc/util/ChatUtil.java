package com.pla.smart_npc.util;

import com.pla.smart_npc.config.SmartNpcConfig;
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

public class ChatUtil {
    private static final int TEAM_UP_ACCEPTANCE_MESSAGES = 2;

    public static void joinGame(Entity entity) {
        joinGame(entity, entity.getDisplayName());
    }

    public static void joinGame(Entity entity, String name) {
        joinGame(entity, Component.literal(name));
    }

    public static void leaveGame(Entity entity) {
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
        broadcastNpcChat(speaker, Component.translatable(
                randomKey(speaker, "chat.player_npc.teamup_accept", TEAM_UP_ACCEPTANCE_MESSAGES)));
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
        if (!canChat(victim)
                || (requireExplicitTarget && !PlayerNpcChatTemplateLoader.hasExplicitTargetMatch(
                PlayerNpcChatTemplateLoader.DEATH_REACTION, victim, killer))) {
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

        PlayerNpcChatTemplateLoader.selectMessage(
                        event, speaker, target, speaker.getRandom(), requireExplicitTarget)
                .ifPresent(template -> broadcastNpcChat(speaker, formatTemplate(template, args)));
    }

    private static void broadcastNpcChat(Entity speaker, Component body) {
        if (!canChat(speaker)) {
            return;
        }

        net.minecraft.network.chat.MutableComponent message = withNpcPrefix(Component.empty());
        message.append("<")
                .append(speaker.getDisplayName())
                .append("> ")
                .append(body);
        broadcastSystemMessage(speaker, message);
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

    private static String randomKey(Entity entity, String prefix, int messageCount) {
        return prefix + "." + (entity.level().getRandom().nextInt(messageCount) + 1);
    }
}
