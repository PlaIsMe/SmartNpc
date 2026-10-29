package com.pla.smart_npc.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap;
import com.pla.smart_npc.util.ProgressionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.StringJoiner;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = SmartNpc.MODID)
public final class PlayerNpcCommandEvent {
    private PlayerNpcCommandEvent() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("smart_npc")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("spawn_player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> spawnPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("tp")
                        .requires(source -> SmartNpcConfig.isForceTickManageEnabled())
                        .then(Commands.argument("name", StringArgumentType.string())
                                .suggests((context, builder) -> PlayerNpcForceTickManager.suggestNpcNames(
                                        context.getSource().getServer(),
                                        builder,
                                        true
                                ))
                                .executes(context -> teleportToPlayerNpc(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))
                                .then(Commands.argument("destination", EntityArgument.entity())
                                        .executes(context -> teleportPlayerNpcToEntity(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "name"),
                                                EntityArgument.getEntity(context, "destination")
                                        )))))
                .then(Commands.literal("difficulty")
                        .then(Commands.literal("get")
                                .executes(context -> getDifficulty(context.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("difficulty", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"easy", "medium", "hard"}, builder))
                                        .executes(context -> setDifficulty(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "difficulty")
                                        )))))
                .then(Commands.literal("trace")
                        .then(Commands.literal("all")
                                .then(Commands.literal("on")
                                        .executes(context -> setTraceAll(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setTraceAll(context.getSource(), false)))
                                .then(Commands.literal("status")
                                        .executes(context -> getTraceAllStatus(context.getSource())))))
                .then(Commands.literal("resource")
                        .requires(source -> SmartNpcConfig.isForceTickManageEnabled())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((context, builder) -> PlayerNpcForceTickManager.suggestNpcNames(
                                        context.getSource().getServer(),
                                        builder
                                ))
                                .executes(context -> handoffAiResource(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("resources")
                        .executes(context -> getAiResources(context.getSource()))));
    }

    private static int spawnPlayer(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        PlayerNpcEntity entity = SmartNpcModEntities.PLAYER_NPC.get().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (entity == null) {
            source.sendFailure(Component.literal("Failed to create player NPC"));
            return 0;
        }

        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        entity.snapTo(position.x, position.y, position.z, rotation.y, rotation.x);
        entity.setUsername(name);
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(entity.blockPosition());
        entity.finalizeSpawn(level, difficulty, EntitySpawnReason.COMMAND, null);
        level.addFreshEntity(entity);
        source.sendSuccess(() -> Component.literal("Spawned player NPC " + entity.getName().getString()), true);
        return 1;
    }

    private static int teleportToPlayerNpc(CommandSourceStack source, String name) throws CommandSyntaxException {
        if (!SmartNpcConfig.isForceTickManageEnabled()) {
            source.sendFailure(Component.literal("Player NPC force-tick management is disabled"));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        PlayerNpcEntity npc = PlayerNpcForceTickManager.chooseRandomByName(source.getServer(), name).orElse(null);
        if (npc == null || !(npc.level() instanceof ServerLevel targetLevel)) {
            source.sendFailure(Component.literal("No tracked player NPC named " + name));
            return 0;
        }

        player.teleportTo(targetLevel, npc.getX(), npc.getY(), npc.getZ(), java.util.Set.of(), npc.getYRot(), npc.getXRot(), false);
        source.sendSuccess(() -> Component.literal("Teleported to player NPC " + npc.getName().getString()), true);
        return 1;
    }

    private static int teleportPlayerNpcToEntity(CommandSourceStack source, String name, Entity destination) {
        if (!SmartNpcConfig.isForceTickManageEnabled()) {
            source.sendFailure(Component.literal("Player NPC force-tick management is disabled"));
            return 0;
        }
        PlayerNpcEntity npc = PlayerNpcForceTickManager.chooseRandomByName(source.getServer(), name).orElse(null);
        if (npc == null) {
            source.sendFailure(Component.literal("No tracked player NPC named " + name));
            return 0;
        }
        if (!(destination.level() instanceof ServerLevel targetLevel) || destination.isRemoved()) {
            source.sendFailure(Component.literal("Teleport destination is no longer available"));
            return 0;
        }
        EntityTeleportEvent.TeleportCommand event = new EntityTeleportEvent.TeleportCommand(npc, targetLevel, destination.getX(), destination.getY(), destination.getZ());
        NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            source.sendFailure(Component.literal("Player NPC teleport was canceled"));
            return 0;
        }
        if (!Level.isInSpawnableBounds(BlockPos.containing(event.getTargetX(), event.getTargetY(), event.getTargetZ()))) {
            source.sendFailure(Component.literal("Teleport destination is outside world bounds"));
            return 0;
        }
        UUID npcId = npc.getUUID();
        String npcName = npc.getName().getString();
        npc.getNavigation().stop();
        npc.stopRiding();
        if (!npc.teleportTo(targetLevel, event.getTargetX(), event.getTargetY(), event.getTargetZ(), Set.of(), destination.getYRot(), destination.getXRot(), false)) {
            source.sendFailure(Component.literal("Failed to teleport player NPC " + npcName));
            return 0;
        }
        // Cross-dimension teleport replaces the entity; refresh the destination instance.
        if (targetLevel.getEntity(npcId) instanceof PlayerNpcEntity teleported) {
            teleported.getNavigation().stop();
            teleported.setDeltaMovement(Vec3.ZERO);
            teleported.fallDistance = 0.0F;
            PlayerNpcForceTickManager.track(teleported);
        }
        source.sendSuccess(() -> Component.literal("Teleported player NPC " + npcName + " to " + destination.getName().getString()), true);
        return 1;
    }

    private static int getDifficulty(CommandSourceStack source) {
        Difficulty difficulty = ProgressionUtil.getDifficulty(source.getServer());
        source.sendSuccess(() -> Component.literal("Current Annoying Villagers difficulty is " + difficulty.id()), false);
        return 1;
    }

    private static int setDifficulty(CommandSourceStack source, String name) {
        Difficulty difficulty = Difficulty.findByName(name);
        if (difficulty == null) {
            source.sendFailure(Component.literal("Unknown Annoying Villagers difficulty: " + name));
            return 0;
        }

        boolean changed = ProgressionUtil.setDifficulty(source.getServer(), difficulty);
        source.sendSuccess(() -> Component.literal("Annoying Villagers difficulty "
                + (changed ? "changed to " : "is already ")
                + difficulty.id()), true);
        return changed ? 1 : 0;
    }

    private static int setTraceAll(CommandSourceStack source, boolean enabled) {
        PlayerNpcGoalTraceLogger.setAllTraceEnabled(enabled, sourceName(source));
        int loadedCount = PlayerNpcGoalTraceLogger.countLoadedPlayerNpcs(source.getServer());
        int holderCount = PlayerNpcAiWorkBudget.resourceSnapshot(source.getServer()).holders().size();
        source.sendSuccess(() -> Component.literal("Player NPC scheduler-resource trace "
                + (enabled ? "enabled" : "disabled")
                + "; tracing "
                + holderCount
                + " resource holder(s) of "
                + loadedCount
                + " loaded NPC(s)"), true);
        return 1;
    }

    private static int getTraceAllStatus(CommandSourceStack source) {
        int loadedCount = PlayerNpcGoalTraceLogger.countLoadedPlayerNpcs(source.getServer());
        int holderCount = PlayerNpcAiWorkBudget.resourceSnapshot(source.getServer()).holders().size();
        source.sendSuccess(() -> Component.literal("Player NPC scheduler-resource trace is "
                + (PlayerNpcGoalTraceLogger.isAllTraceEnabled() ? "enabled" : "disabled")
                + "; current holders="
                + holderCount
                + ", loaded="
                + loadedCount
                + " NPC(s)"), false);
        return 1;
    }

    private static int handoffAiResource(CommandSourceStack source, String name) {
        if (!SmartNpcConfig.isForceTickManageEnabled()) {
            source.sendFailure(Component.literal("Player NPC force-tick management is disabled"));
            return 0;
        }

        PlayerNpcEntity receiver = PlayerNpcForceTickManager.chooseRandomByName(source.getServer(), name).orElse(null);
        if (receiver == null) {
            source.sendFailure(Component.literal("No tracked player NPC named " + name));
            return 0;
        }

        PlayerNpcAiWorkBudget.WorkerHandoffResult result =
                PlayerNpcAiWorkBudget.handoffWorkerResource(source.getServer(), receiver);
        String receiverName = clean(receiver.getDisplayName().getString()) + "#" + receiver.getId();
        return switch (result.status()) {
            case TRANSFERRED -> {
                PlayerNpcEntity donor = result.donor();
                String donorName = donor == null
                        ? "another holder"
                        : clean(donor.getDisplayName().getString()) + "#" + donor.getId();
                source.sendSuccess(() -> Component.literal("Transferred Player NPC AI worker resource from "
                        + donorName
                        + " to "
                        + receiverName
                        + "; shiftRemainingTicks="
                        + result.shiftRemainingTicks()), true);
                yield 1;
            }
            case GRANTED -> {
                source.sendSuccess(() -> Component.literal("Granted an available Player NPC AI worker resource to "
                        + receiverName
                        + "; shiftRemainingTicks="
                        + result.shiftRemainingTicks()), true);
                yield 1;
            }
            case ALREADY_HOLDER -> {
                source.sendSuccess(() -> Component.literal(receiverName
                        + " already holds a Player NPC AI worker resource"), false);
                yield 0;
            }
            case DISABLED -> {
                source.sendFailure(Component.literal("Player NPC AI worker limit is 0; no resource can be assigned"));
                yield 0;
            }
            case UNAVAILABLE -> {
                source.sendFailure(Component.literal("Could not assign a Player NPC AI worker resource to "
                        + receiverName));
                yield 0;
            }
        };
    }

    private static int getAiResources(CommandSourceStack source) {
        PlayerNpcAiWorkBudget.ResourceSnapshot snapshot = PlayerNpcAiWorkBudget.resourceSnapshot(source.getServer());
        PlayerNpcForceTickManager.ForceTickSnapshot forceTicks = PlayerNpcForceTickManager.forceTickSnapshot(source.getServer());
        PlayerNpcNaturalSpawnCap.SpawnCapSnapshot spawnCap = PlayerNpcNaturalSpawnCap.snapshot(source.getServer());
        String mode = snapshot.automatic() ? "auto" : "configured";
        source.sendSuccess(() -> Component.literal("Player NPC AI resources: mode="
                + mode
                + ", workerLimit="
                + snapshot.effectiveWorkerLimit()
                + ", active="
                + snapshot.activeWorkerCount()
                + ", running="
                + snapshot.runningWorkerCount()
                + ", idle="
                + snapshot.idleWorkerCount()
                + ", probes="
                + snapshot.probeCount()
                + ", expensiveSlicesThisTick="
                + snapshot.expensiveCount()
                + ", waiting="
                + snapshot.waitingNpcCount()), false);
        String forceTickLine = "Player NPC force tickets: mode="
                + forceTicks.modeText()
                + ", used="
                + forceTicks.usedSlots()
                + "/"
                + forceTicks.effectiveSlots()
                + ", workerPriority="
                + forceTicks.workerTicketCount()
                + ", available="
                + forceTicks.eligibleNpcCount()
                + ", known="
                + forceTicks.knownNpcCount();
        if (forceTicks.automatic()) {
            forceTickLine += ", explorationCeiling="
                    + forceTicks.capabilityLimit()
                    + ", baselineMspt="
                    + String.format(java.util.Locale.ROOT, "%.1f", forceTicks.baselineMspt())
                    + ", handoffPrefetch="
                    + forceTicks.handoffProtectionActive()
                    + ", reason="
                    + forceTicks.reason();
        }
        String finalForceTickLine = forceTickLine;
        source.sendSuccess(() -> Component.literal(finalForceTickLine), false);
        String spawnCapLine = "Player NPC natural spawn cap: mode="
                + (spawnCap.automatic() ? "auto" : "configured")
                + ", effectiveMax="
                + spawnCap.effectiveLimit()
                + ", living="
                + spawnCap.livingCount()
                + ", loaded="
                + spawnCap.loadedCount()
                + ", pending="
                + spawnCap.pendingCount();
        if (spawnCap.automatic()) {
            spawnCapLine += ", explorationCeiling="
                    + spawnCap.explorationLimit()
                    + ", advisoryForecast="
                    + spawnCap.advisoryForecastLimit()
                    + ", probeWindowsAtOrBelow45Mspt="
                    + spawnCap.healthyEvaluationCount()
                    + "/"
                    + spawnCap.healthyEvaluationsRequired()
                    + ", probeMode="
                    + spawnCap.probeMode()
                    + ", learnedSafeMax="
                    + spawnCap.learnedSafeLimit()
                    + ", baselineMspt="
                    + String.format(java.util.Locale.ROOT, "%.1f", spawnCap.baselineMspt())
                    + ", avgMspt="
                    + String.format(java.util.Locale.ROOT, "%.1f", spawnCap.averageMspt())
                    + ", avgNpcMs="
                    + String.format(java.util.Locale.ROOT, "%.1f", spawnCap.averageNpcMs())
                    + ", reason="
                    + spawnCap.reason();
        }
        String finalSpawnCapLine = spawnCapLine;
        source.sendSuccess(() -> Component.literal(finalSpawnCapLine), false);

        if (snapshot.holders().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No Player NPC currently holds an AI scheduler resource"), false);
            return 1;
        }

        for (PlayerNpcAiWorkBudget.ResourceHolder holder : snapshot.holders()) {
            PlayerNpcEntity playerNpc = holder.playerNpc() != null
                    ? holder.playerNpc()
                    : findLoadedPlayerNpc(source.getServer(), holder.npcId());
            StringJoiner roles = new StringJoiner(",");
            if (holder.worker()) {
                roles.add("worker");
            }
            if (holder.probeTurn()) {
                roles.add("probe");
            }
            if (holder.expensiveSlice()) {
                roles.add("expensive-slice");
            }
            String identity = playerNpc == null
                    ? holder.npcId().toString()
                    : clean(playerNpc.getDisplayName().getString()) + "#" + playerNpc.getId();
            String location = playerNpc == null
                    ? "unloaded"
                    : playerNpc.level().dimension().identifier()
                    + "@"
                    + playerNpc.blockPosition().getX()
                    + ","
                    + playerNpc.blockPosition().getY()
                    + ","
                    + playerNpc.blockPosition().getZ();
            String state = playerNpc == null ? "unknown" : clean(playerNpc.getCurrentAiState());
            String detail = playerNpc == null ? "unknown" : clean(playerNpc.getCurrentAiDetail());
            String line = "- "
                    + identity
                    + " resource="
                    + roles
                    + " goals="
                    + holder.runningGoals()
                    + " heldTicks="
                    + holder.heldTicks()
                    + " shiftRemainingTicks="
                    + holder.shiftRemainingTicks()
                    + " location="
                    + location
                    + " state="
                    + state
                    + " detail=\""
                    + detail
                    + "\"";
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return snapshot.holders().size();
    }

    private static PlayerNpcEntity findLoadedPlayerNpc(MinecraftServer server, UUID npcId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(npcId) instanceof PlayerNpcEntity playerNpc
                    && playerNpc.isAlive()
                    && !playerNpc.isRemoved()) {
                return playerNpc;
            }
        }
        return null;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String sourceName(CommandSourceStack source) {
        return source.getDisplayName().getString();
    }
}
