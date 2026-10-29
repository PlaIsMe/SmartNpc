package com.pla.smart_npc.entity;

import net.minecraft.core.component.DataComponents;

import net.minecraft.tags.ItemTags;

import com.pla.smart_npc.util.SmartNpcItemUtil;

import com.pla.smart_npc.util.SmartNpcNbt;


import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.compat.BetterCombatCompat;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.WaterTravelAi;
import com.pla.smart_npc.entity.ai.ItemApproachAi;
import com.pla.smart_npc.entity.ai.SocialNoticeAi;
import com.pla.smart_npc.entity.goal.PlayerNpcFloatGoal;
import com.pla.smart_npc.entity.goal.NoticeNearbyPlayerGoal;
import com.pla.smart_npc.entity.goal.AiBudgetWaitingStrollGoal;
import com.pla.smart_npc.entity.goal.BeingAtHomeGoal;
import com.pla.smart_npc.entity.goal.BurnNearbyItemGoal;
import com.pla.smart_npc.entity.goal.BuildHouseGoal;
import com.pla.smart_npc.entity.goal.BreakTargetObstructionGoal;
import com.pla.smart_npc.entity.goal.BoatStockpileGoal;
import com.pla.smart_npc.entity.goal.BoatTrapMonsterGoal;
import com.pla.smart_npc.entity.goal.CallForHelpGoal;
import com.pla.smart_npc.entity.goal.CautiousAvoidThreatGoal;
import com.pla.smart_npc.entity.goal.CheckHomeSuppliesGoal;
import com.pla.smart_npc.entity.goal.CombatFishingRodGoal;
import com.pla.smart_npc.entity.goal.CookFoodGoal;
import com.pla.smart_npc.entity.goal.CleanupTemporaryPillarGoal;
import com.pla.smart_npc.entity.goal.CraftBasicGearGoal;
import com.pla.smart_npc.entity.goal.CraftCropFoodGoal;
import com.pla.smart_npc.entity.goal.CraftIronGearGoal;
import com.pla.smart_npc.entity.goal.CraftShieldGoal;
import com.pla.smart_npc.entity.goal.DangerousReactionGoal;
import com.pla.smart_npc.entity.goal.DescendHighColumnGoal;
import com.pla.smart_npc.entity.goal.DigDownForStoneGoal;
import com.pla.smart_npc.entity.goal.EatHealingFoodGoal;
import com.pla.smart_npc.entity.goal.EscapeHoleWithBlockGoal;
import com.pla.smart_npc.entity.goal.EscapeWallGoal;
import com.pla.smart_npc.entity.goal.ExploreAroundGoal;
import com.pla.smart_npc.entity.goal.ExploreCaveOreGoal;
import com.pla.smart_npc.entity.goal.FillWaterBucketGoal;
import com.pla.smart_npc.entity.goal.FarmCropGoal;
import com.pla.smart_npc.entity.goal.FarmSetupGoal;
import com.pla.smart_npc.entity.goal.FarmStrollGoal;
import com.pla.smart_npc.entity.goal.GatherMissingBuildMaterialGoal;
import com.pla.smart_npc.entity.goal.GatherLogsGoal;
import com.pla.smart_npc.entity.goal.GatheringGoal;
import com.pla.smart_npc.entity.goal.GatherStoneGoal;
import com.pla.smart_npc.entity.goal.IronGolemTrollGoal;
import com.pla.smart_npc.entity.goal.InterestGatedGoal;
import com.pla.smart_npc.entity.goal.JukeboxDanceGoal;
import com.pla.smart_npc.entity.goal.LootNearbyChestGoal;
import com.pla.smart_npc.entity.goal.LowHealthFleeGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcMeleeAttackGoal;
import com.pla.smart_npc.entity.goal.ManageHomeBaseGoal;
import com.pla.smart_npc.entity.goal.MiningCaveStrollGoal;
import com.pla.smart_npc.entity.goal.MiningNightCampGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcFishingGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcProjectileBlockGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcRangedBowAttackGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcSmartTargetGoal;
import com.pla.smart_npc.entity.goal.PickupNearbyItemGoal;
import com.pla.smart_npc.entity.goal.RandomCombatJumpGoal;
import com.pla.smart_npc.entity.goal.RareSneakGoal;
import com.pla.smart_npc.entity.goal.RecoverWeaponInCombatGoal;
import com.pla.smart_npc.entity.goal.RespondToNpcAlertGoal;
import com.pla.smart_npc.entity.goal.ReturnHomeGoal;
import com.pla.smart_npc.entity.goal.ScaredHideGoal;
import com.pla.smart_npc.entity.goal.ShieldGuardGoal;
import com.pla.smart_npc.entity.goal.SleepAtHomeGoal;
import com.pla.smart_npc.entity.goal.StartupWorkGatedGoal;
import com.pla.smart_npc.entity.goal.PlantSaplingGoal;
import com.pla.smart_npc.entity.goal.RetargetCloserThreatGoal;
import com.pla.smart_npc.entity.goal.ThrowEnderPearlGoal;
import com.pla.smart_npc.entity.goal.ThrowTrashItemsGoal;
import com.pla.smart_npc.entity.goal.WaterFallGoal;
import com.pla.smart_npc.entity.goal.TeamUpGoal;
import com.pla.smart_npc.entity.goal.FollowTeamLeaderGoal;
import com.pla.smart_npc.entity.goal.TerraformBuildSiteGoal;
import com.pla.smart_npc.entity.goal.TrollHitGoal;
import com.pla.smart_npc.entity.goal.UtilityCraftingGoal;
import com.pla.smart_npc.entity.goal.UseFlintAndSteelGoal;
import com.pla.smart_npc.entity.goal.UseLavaBucketGoal;
import com.pla.smart_npc.entity.goal.UseWaterBucketGoal;
import com.pla.smart_npc.entity.goal.UseSpyglassGoal;
import com.pla.smart_npc.entity.goal.WaterEnderPearlEscapeGoal;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class PlayerNpcEntity extends FakePlayer implements RangedAttackMob {
    private static final EntityDataAccessor<Integer> MAIN_HAND_ATTACK_ANIMATION_TICKS = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> SOCIAL_NOTICE_PAUSES_WORK = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HEALING = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> BETTER_COMBAT_ATTACK_ANIMATION_TICKS = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BETTER_COMBAT_ATTACK_SEQUENCE = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> AI_STATE = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> AI_DETAIL = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DANCING = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> EPIC_FIGHT_DIGGING = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SNEAKING_AI_HIDES_DISPLAY_NAME = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAIN_HAND_ATTACK_ANIMATION_DURATION = 10;
    private static final int BETTER_COMBAT_ATTACK_ANIMATION_DURATION = 120;
    private static final int MAIN_HAND_USE_ANIMATION_DURATION = 6;
    private static final EquipmentSlot[] DEATH_LOOT_EQUIPMENT_SLOTS = {
            EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD,
            EquipmentSlot.CHEST,
            EquipmentSlot.LEGS,
            EquipmentSlot.FEET
    };
    private static final int PLACE_BLOCK_PARRY_COOLDOWN_TICKS = 60;
    private static final double PLAYER_LIKE_JUMP_Y = 0.42D;
    private static final int EXPLORATION_RETURN_ESCAPE_MIN_PILLAR_BLOCKS = 8;
    private static final int EXPLORATION_RETURN_ESCAPE_EXTRA_BLOCKS = 4;
    private static final int EXPLORATION_RETURN_ESCAPE_MAX_PILLAR_BLOCKS = 24;
    private static final int STARTUP_IDLE_WAKE_TICKS = 20 * 4;
    private static final int TASKLESS_IDLE_WAKE_TICKS = 20;
    private static final int TEMPORARY_PILLAR_SUPPORT_MEMORY_TICKS = 20 * 45;
    private static final int MAX_TRACKED_TEMPORARY_PILLAR_SUPPORTS = 128;
    private static final String TEMPORARY_PILLAR_SUPPORTS_TAG = "TemporaryPillarSupports";
    private static final String PENDING_SPAWN_INITIALIZATION_TAG = "PendingSpawnInitialization";
    private static final int EXPLORATION_CLIMB_STUCK_TICKS = 20 * 5;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_MIN_RADIUS = 4;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_MAX_RADIUS = 5;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_DOWN = 2;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_UP = 4;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_RANDOM_POOL = 8;
    private static final int EXPLORATION_CLIMB_SAFE_STAND_PATH_CHECKS = 1;
    private static final float EXPLORATION_CLIMB_SAFE_STAND_PATH_MULTIPLIER = 0.01F;
    private static final double EXPLORATION_CLIMB_SAFE_STAND_REACHED_SQR = 1.1D * 1.1D;
    private static final int EXPLORATION_CLIMB_CLEAR_TICKS = 24;
    private static final double EXPLORATION_CLIMB_CLEAR_DISTANCE_SQR = 5.0D * 5.0D;
    private static final int EXPLORATION_CLIMB_CLEAR_RANDOM_POOL = 8;
    private static final int IDLE_RESOURCE_STUCK_TICKS = 20 * 10;
    private static final int IDLE_RESOURCE_STUCK_RECHECK_TICKS = 20 * 4;
    private static final int IDLE_RESOURCE_SURFACE_ESCAPE_RADIUS = 6;
    private static final int IDLE_RESOURCE_SURFACE_ESCAPE_TICKS = 20 * 8;
    private static final int IDLE_RESOURCE_SURFACE_ESCAPE_MAX_BLOCKS = 10;
    private static final int IDLE_RESOURCE_CLEAR_TICKS = 24;
    private static final double IDLE_RESOURCE_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int IDLE_RESOURCE_CLEAR_RANDOM_POOL = 8;
    private static final int IDLE_RESOURCE_ELIGIBILITY_CHECK_INTERVAL_TICKS = 20;
    private static final int IDLE_RESOURCE_SURFACE_COLUMNS_PER_SLICE = 2;
    private static final int HIGH_IDLE_DESCENT_TRIGGER_TICKS = 20 * 8;
    private static final int HIGH_IDLE_DESCENT_RECHECK_TICKS = 20 * 2;
    private static final int HIGH_IDLE_DESCENT_RADIUS = 6;
    private static final int HIGH_IDLE_DESCENT_MIN_HEIGHT = 4;
    private static final int HIGH_IDLE_DESCENT_MAX_SAFE_DROP = 3;
    private static final int HIGH_IDLE_DESCENT_PATH_CHECKS = 8;
    private static final int HIGH_IDLE_DESCENT_COLUMNS_PER_SLICE = 2;
    private static final int HIGH_IDLE_DESCENT_PATH_CHECKS_PER_SLICE = 1;
    private static final float HIGH_IDLE_DESCENT_PATH_MULTIPLIER = 0.05F;
    private static final List<BlockPos> HIGH_IDLE_DESCENT_COLUMN_OFFSETS = createHighIdleDescentColumnOffsets();
    private static final List<BlockPos> HIGH_IDLE_DESCENT_EVIDENCE_OFFSETS = List.of(
            new BlockPos(3, 0, 0), new BlockPos(-3, 0, 0),
            new BlockPos(0, 0, 3), new BlockPos(0, 0, -3),
            new BlockPos(6, 0, 0), new BlockPos(-6, 0, 0),
            new BlockPos(0, 0, 6), new BlockPos(0, 0, -6)
    );
    private static final Vec3i ITEM_PICKUP_REACH = new Vec3i(1, 1, 1);
    private static final double EXPERIENCE_PICKUP_RADIUS = 3.0D;
    private static final long DAY_LENGTH_TICKS = 24000L;
    private static final long DAILY_JOB_ROLL_TIME = 1L;
    private static final long DAILY_JOB_FALLBACK_ROLL_END_TIME = 12000L;
    private static final int FISHING_STARTER_STRING_VERSION = 1;
    private static final int FISHING_STARTER_STRING_REQUIRED = 2;
    private static final int FISHING_STARTER_MIGRATION_INTERVAL_TICKS = 20 * 5;
    private static final List<PlayerNpcInterest> DAILY_JOB_INTERESTS = List.of(
            PlayerNpcInterest.BUILDING,
            PlayerNpcInterest.MINING,
            PlayerNpcInterest.FARMING,
            PlayerNpcInterest.FISHING,
            PlayerNpcInterest.EXPLORING
    );
    public static final String AI_IDLE = "ai.player_npc.idle";
    private static final List<ItemLike> REGULAR_FOODS = List.of(
            Items.COOKED_BEEF,
            Items.BREAD,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.BAKED_POTATO,
            Items.CARROT,
            Items.APPLE
    );
    private static final List<ItemLike> PLACEABLE_BLOCKS = List.of(
            Items.COBBLESTONE,
            Items.MOSSY_COBBLESTONE,
            Items.DIRT,
            Items.OAK_LOG,
            Items.BIRCH_LOG,
            Items.SPRUCE_LOG,
            Items.OAK_PLANKS,
            Items.DARK_OAK_PLANKS,
            Items.STONE,
            Items.COBBLED_DEEPSLATE,
            Items.DEEPSLATE,
            Items.GRAVEL,
            Items.SAND
    );
    private static final List<ItemLike> MUSIC_DISCS = List.of(
            Items.MUSIC_DISC_13,
            Items.MUSIC_DISC_CAT,
            Items.MUSIC_DISC_BLOCKS,
            Items.MUSIC_DISC_CHIRP,
            Items.MUSIC_DISC_FAR,
            Items.MUSIC_DISC_MALL,
            Items.MUSIC_DISC_MELLOHI,
            Items.MUSIC_DISC_STAL,
            Items.MUSIC_DISC_STRAD,
            Items.MUSIC_DISC_WARD,
            Items.MUSIC_DISC_11,
            Items.MUSIC_DISC_WAIT,
            Items.MUSIC_DISC_OTHERSIDE,
            Items.MUSIC_DISC_RELIC,
            Items.MUSIC_DISC_5,
            Items.MUSIC_DISC_PIGSTEP
    );

    private final SimpleContainer inventory = new SimpleContainer(27) {
        @Override
        public void setChanged() {
            super.setChanged();
            ResourceAi.invalidate(PlayerNpcEntity.this);
        }
    };
    private BlockPos lastSentBlockBreakProgressPos;
    private int lastSentBlockBreakProgressStage = -1;
    private int gapCooldown = 0;
    private int bucketCooldown = 0;
    private int flintAndSteelCooldown = 0;
    private int enderPearlCooldown = 0;
    private int swapToBowCooldown = 0;
    private int helpAlertCooldown = 0;
    private int holeEscapeCooldown = 0;
    private int rareSneakCooldown = 0;
    private int scaredHideCooldown = 0;
    private int buildHouseCooldown = 0;
    private int cookFoodCooldown = 0;
    private int craftGearCooldown = 0;
    private int farmCooldown = 0;
    private int gatherCooldown = 0;
    private int stoneAccessClearCooldown = 0;
    private int biomeExploreCooldown = 0;
    private int huntSheepCooldown = 0;
    private int ironGolemTrollCooldown = 0;
    private int lootChestCooldown = 0;
    private int manageHomeCooldown = 0;
    private int fishingCooldown = 0;
    private int returnHomeCooldown = 0;
    private int explorationReturnHomeRequestTicks = 0;
    private int sleepCooldown = 0;
    private int craftCooldown = 0;
    private int oreMiningCooldown = 0;
    private int ironGearCooldown = 0;
    private int spyglassCooldown = 0;
    private int saplingPlantCooldown = 0;
    private int boatStockCooldown = 0;
    private int boatTrapCooldown = 0;
    private int jukeboxDanceCooldown = 0;
    private int trollHitCooldown = 0;
    private int combatFishingCooldown = 0;
    private int shieldCraftCooldown = 0;
    private int shieldGuardCooldown = 0;
    private int itemPickupSuppressionTicks = 0;
    private boolean boatCollector = new Random().nextFloat() < 0.35F;
    private int desiredBoatCount = new Random().nextInt(2, 4);
    private int rawLogReserveTarget = 24;
    private int woodSupplyTarget = 64;
    private int cobblestoneSupplyTarget = 24;
    private int activeLogSupplyTarget;
    private int activeStoneSupplyTarget;
    private long lastSupplyGoalRerollDay = -1L;
    private int fishingStarterStringVersion;
    private int workGoalRegistrationIndex;
    @Nullable
    private PlayerNpcInterest selectedDailyJobInterest;
    private long selectedDailyJobDay = -1L;
    private ItemStack mainWeaponItem = ItemStack.EMPTY;
    private ItemStack offWeaponItem = ItemStack.EMPTY;
    private ItemStack temporaryBowPreviousMainHand = ItemStack.EMPTY;
    private boolean temporaryBowEquipped = false;
    private boolean suppressHeldItemCacheUpdate = false;
    private boolean useBow = true;
    @Nullable
    private BlockPos ownedChestPos;
    @Nullable
    private UUID teamId;
    private String teamName = "";
    @Nullable
    private UUID teamFounderUuid;
    @Nullable
    private UUID teamLeaderUuid;
    private boolean teamLeaderIsPlayer;
    private boolean teamLeaderRole;
    private boolean teamUpRequestPending;
    private boolean teamMembershipValidated;
    private boolean pendingSpawnInitialization;
    @Nullable
    private BlockPos upwardEscapeTarget;
    private int upwardEscapeRequestTicks = 0;
    private int upwardEscapeMaxPillarBlocks = 0;
    private boolean forcedUpwardEscape = false;
    private boolean explorationUpwardEscape = false;
    private boolean craftingUpwardEscape = false;
    private boolean terraformSupportUpwardEscape = false;
    private boolean teamFollowUpwardEscape = false;
    @Nullable
    private BlockPos explorationClimbWatchPos;
    @Nullable
    private BlockPos explorationClimbWatchTarget;
    @Nullable
    private BlockPos explorationClimbSafeStandTarget;
    private int explorationClimbStuckTicks = 0;
    private final WaterTravelAi waterTravelAi = new WaterTravelAi(this);
    private final ItemApproachAi itemApproachAi = new ItemApproachAi(this);
    private final SocialNoticeAi socialNoticeAi = new SocialNoticeAi(this);
    private final ToolAi explorationClimbToolAi = new ToolAi(this);
    private final BreakingBlockAi explorationClimbBreakingBlockAi = new BreakingBlockAi(this, this.explorationClimbToolAi);
    private final ClearBlockAi explorationClimbClearBlockAi = new ClearBlockAi(this, this.explorationClimbBreakingBlockAi);
    private final PathStuckFallbackAi idleResourcePathStuckFallbackAi = new PathStuckFallbackAi(this);
    private final ToolAi idleResourceFallbackToolAi = new ToolAi(this);
    private final BreakingBlockAi idleResourceFallbackBreakingBlockAi = new BreakingBlockAi(this, this.idleResourceFallbackToolAi);
    private final ClearBlockAi idleResourceFallbackClearBlockAi = new ClearBlockAi(this, this.idleResourceFallbackBreakingBlockAi);
    @Nullable
    private BlockPos idleResourceStuckWatchPos;
    @Nullable
    private BlockPos idleResourceStuckWatchTarget;
    private int idleResourceStuckTicks = 0;
    private int idleResourceStuckRecheckTicks = 0;
    @Nullable
    private BlockPos idleResourceSurfaceSearchOrigin;
    @Nullable
    private BlockPos cachedIdleResourceSurfaceEscapeTarget;
    private final List<BlockPos> idleResourceSurfaceCandidates = new ArrayList<>();
    private final List<BlockPos> idleResourceRelaxedSurfaceCandidates = new ArrayList<>();
    private int idleResourceSurfaceSearchCursor = 0;
    private boolean idleResourceSurfaceSearchPending = false;
    private int nextIdleResourceSurfaceSearchTick = 0;
    private int nextIdleResourceEligibilityCheckTick = 0;
    private boolean idleResourceEligibilityCached = false;
    private final PathNavigationAi highIdleDescentNavigationAi = new PathNavigationAi(this);
    private final PathStuckFallbackAi highIdleDescentFallbackAi = new PathStuckFallbackAi(this);
    @Nullable
    private BlockPos highIdleDescentNavigationTarget;
    @Nullable
    private BlockPos highIdleDescentSearchOrigin;
    private final List<BlockPos> highIdleDescentCandidates = new ArrayList<>();
    private int highIdleDescentTicks = 0;
    private int highIdleDescentColumnCursor = 0;
    private int highIdleDescentPathCursor = 0;
    private int nextHighIdleDescentAttemptTick = 0;
    private double placeBlockToParryChance;
    private int placeBlockParryCooldown = 0;
    private int stunEscapeCooldown = 0;
    private int playingIdleCooldown = new Random().nextInt(600, 1200);
    private int tasklessIdleTicks = 0;
    private String idleTraceDetail = "";
    private int idleTraceDetailTicks = 0;
    private int startupIdleWakeTicks = STARTUP_IDLE_WAKE_TICKS;
    private int staleTargetTicks = 0;
    private int staleTargetEntityId = -1;
    private int lastCombatProgressTick = 0;
    @Nullable
    private UUID chestProtectionTargetId;
    private int animalLootPriorityTicks = 0;
    @Nullable
    private BlockPos animalLootPriorityPos;
    private int storedExperience = 0;
    /**
     * Exact positions and block identities placed by this NPC for temporary vertical support.
     * This evidence is persisted and is the only authority used by automatic cleanup; terrain
     * that merely resembles a pillar is never inferred to be NPC-owned.
     */
    private final Map<BlockPos, Block> temporaryPillarSupports = new HashMap<>();
    /** Source proof for the subset placed by GatherLogs' dedicated PillarUpAi instance. */
    private final Set<BlockPos> gatherLogsTemporaryPillarSupports = new HashSet<>();

    public int getPlayingIdleCooldown() {
        return playingIdleCooldown;
    }

    public void setPlayingIdleCooldown(int playingIdleCooldown) {
        this.playingIdleCooldown = playingIdleCooldown;
    }

    public double getPlaceBlockToParryChance() {
        return placeBlockToParryChance;
    }

    public boolean hasPlaceBlockParryCooldown() {
        return this.placeBlockParryCooldown > 0;
    }

    public void setPlaceBlockParryCooldown() {
        this.placeBlockParryCooldown = PLACE_BLOCK_PARRY_COOLDOWN_TICKS;
    }

    public boolean isHealing() {
        return this.entityData.get(HEALING);
    }

    public void setHealing(boolean healing) {
        this.entityData.set(HEALING, healing);
        if (healing) {
            // Eating owns the main hand, including recovery frames from the last hit.
            this.swinging = false;
            this.swingTime = 0;
            this.attackAnim = 0.0F;
            this.oAttackAnim = 0.0F;
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, 0);
            this.entityData.set(BETTER_COMBAT_ATTACK_ANIMATION_TICKS, 0);
        }
    }

    @Override
    protected void completeUsingItem() {
        // EatHealingFoodGoal owns consumption and healing. Vanilla completion can
        // otherwise consume the held food first and apply apple effects twice.
        if (!this.isHealing()) {
            super.completeUsingItem();
        }
    }

    public int getGapCooldown() {
        return gapCooldown;
    }

    public int getBucketCooldown() {
        return bucketCooldown;
    }

    public int getFlintAndSteelCooldown() {
        return flintAndSteelCooldown;
    }

    public int getEnderPearlCooldown() {
        return enderPearlCooldown;
    }

    public int getSwapToBowCooldown() {
        return swapToBowCooldown;
    }

    public int getHelpAlertCooldown() {
        return helpAlertCooldown;
    }

    public int getHoleEscapeCooldown() {
        return holeEscapeCooldown;
    }

    public int getRareSneakCooldown() {
        return rareSneakCooldown;
    }

    public int getScaredHideCooldown() {
        return scaredHideCooldown;
    }

    public int getBuildHouseCooldown() {
        return buildHouseCooldown;
    }

    public int getCookFoodCooldown() {
        return cookFoodCooldown;
    }

    public int getCraftGearCooldown() {
        return craftGearCooldown;
    }

    public int getFarmCooldown() {
        return farmCooldown;
    }

    public int getGatherCooldown() {
        return gatherCooldown;
    }

    public boolean isStoneAccessClearing() {
        return this.stoneAccessClearCooldown > 0;
    }

    public int getStoneAccessClearCooldown() {
        return this.stoneAccessClearCooldown;
    }

    public int getBiomeExploreCooldown() {
        return biomeExploreCooldown;
    }

    public int getHuntSheepCooldown() {
        return huntSheepCooldown;
    }

    public int getIronGolemTrollCooldown() {
        return ironGolemTrollCooldown;
    }

    public int getLootChestCooldown() {
        return lootChestCooldown;
    }

    public int getManageHomeCooldown() {
        return manageHomeCooldown;
    }

    public int getFishingCooldown() {
        return fishingCooldown;
    }

    public int getReturnHomeCooldown() {
        return returnHomeCooldown;
    }

    public int getSleepCooldown() {
        return sleepCooldown;
    }

    public int getCraftCooldown() {
        return craftCooldown;
    }

    public int getOreMiningCooldown() {
        return oreMiningCooldown;
    }

    public int getIronGearCooldown() {
        return ironGearCooldown;
    }

    public int getSpyglassCooldown() {
        return spyglassCooldown;
    }

    public int getSaplingPlantCooldown() {
        return saplingPlantCooldown;
    }

    public int getBoatStockCooldown() {
        return boatStockCooldown;
    }

    public int getBoatTrapCooldown() {
        return boatTrapCooldown;
    }

    public int getJukeboxDanceCooldown() {
        return jukeboxDanceCooldown;
    }

    public int getTrollHitCooldown() {
        return trollHitCooldown;
    }

    public int getCombatFishingCooldown() {
        return combatFishingCooldown;
    }

    public int getShieldCraftCooldown() {
        return shieldCraftCooldown;
    }

    public int getShieldGuardCooldown() {
        return shieldGuardCooldown;
    }

    public boolean isItemPickupSuppressed() {
        return this.itemPickupSuppressionTicks > 0;
    }

    public void suppressItemPickupFor(int ticks) {
        this.itemPickupSuppressionTicks = Math.max(
                this.itemPickupSuppressionTicks,
                normalizeCooldown(ticks)
        );
    }

    public int getStunEscapeCooldown() {
        return stunEscapeCooldown;
    }

    public void setStunEscapeCooldown(int stunEscapeCooldown) {
        this.stunEscapeCooldown = stunEscapeCooldown;
    }


    @Nullable
    public BlockPos getUpwardEscapeTarget() {
        return this.upwardEscapeRequestTicks > 0 && this.upwardEscapeTarget != null
                ? this.upwardEscapeTarget
                : null;
    }

    public void requestUpwardEscapeTo(@Nullable BlockPos target, int ticks) {
        this.requestUpwardEscapeTo(target, ticks, 0);
    }

    public void requestUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        if (target == null || ticks <= 0) {
            return;
        }

        this.upwardEscapeTarget = target.immutable();
        this.upwardEscapeRequestTicks = Math.max(this.upwardEscapeRequestTicks, normalizeCooldown(ticks));
        this.upwardEscapeMaxPillarBlocks = Math.max(0, maxPillarBlocks);
        this.forcedUpwardEscape = false;
        this.explorationUpwardEscape = false;
        this.craftingUpwardEscape = false;
        this.terraformSupportUpwardEscape = false;
        this.teamFollowUpwardEscape = false;
        this.resetExplorationClimbFallback();
        this.holeEscapeCooldown = 0;
    }

    public void requestExplorationUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        if (target == null || ticks <= 0) {
            return;
        }

        this.requestUpwardEscapeTo(target, ticks, maxPillarBlocks);
        this.explorationUpwardEscape = true;
    }

    public void requestTeamFollowUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        this.requestExplorationUpwardEscapeTo(target, ticks, maxPillarBlocks);
        if (target != null && ticks > 0 && this.isTeamFollower()) {
            this.teamFollowUpwardEscape = true;
        }
    }

    public boolean isTeamFollowUpwardEscapeRequested() {
        return this.getUpwardEscapeTarget() != null && this.teamFollowUpwardEscape && this.isTeamFollower();
    }

    public void retainTeamFollowUpwardEscapeProvenance() {
        if (this.getUpwardEscapeTarget() != null && this.isTeamFollower()) {
            this.teamFollowUpwardEscape = true;
        }
    }

    public void clearTeamFollowUpwardEscapeTarget() {
        if (this.teamFollowUpwardEscape) {
            this.clearUpwardEscapeTarget();
        }
    }

    public boolean isExplorationUpwardEscapeRequested() {
        return this.getUpwardEscapeTarget() != null && this.explorationUpwardEscape;
    }

    public void requestForcedUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        this.requestUpwardEscapeTo(target, ticks, maxPillarBlocks);
        if (target != null && ticks > 0) {
            this.forcedUpwardEscape = true;
        }
    }

    public boolean isForcedUpwardEscape() {
        return this.getUpwardEscapeTarget() != null && this.forcedUpwardEscape;
    }

    public void requestCraftingUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        this.requestForcedUpwardEscapeTo(target, ticks, maxPillarBlocks);
        if (target != null && ticks > 0) {
            this.craftingUpwardEscape = true;
        }
    }

    public boolean isCraftingUpwardEscapeRequested() {
        return this.getUpwardEscapeTarget() != null && this.craftingUpwardEscape;
    }

    public void requestTerraformSupportUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        this.requestForcedUpwardEscapeTo(target, ticks, maxPillarBlocks);
        if (target != null && ticks > 0) {
            this.terraformSupportUpwardEscape = true;
        }
    }

    public boolean isTerraformSupportUpwardEscapeRequested() {
        return this.getUpwardEscapeTarget() != null && this.terraformSupportUpwardEscape;
    }

    public int getUpwardEscapeMaxPillarBlocks() {
        return this.getUpwardEscapeTarget() == null ? 0 : this.upwardEscapeMaxPillarBlocks;
    }

    public void clearUpwardEscapeTarget() {
        String detail = this.getCurrentAiDetail();
        if (detail != null && detail.startsWith("exploration climb request @ ")) {
            this.setCurrentAiDetail("");
        }
        this.upwardEscapeTarget = null;
        this.upwardEscapeRequestTicks = 0;
        this.upwardEscapeMaxPillarBlocks = 0;
        this.forcedUpwardEscape = false;
        this.explorationUpwardEscape = false;
        this.craftingUpwardEscape = false;
        this.terraformSupportUpwardEscape = false;
        this.teamFollowUpwardEscape = false;
        this.resetExplorationClimbFallback();
    }

    @Nullable
    public BlockPos getOwnedChestPos() {
        return this.ownedChestPos;
    }

    public void setOwnedChestPos(@Nullable BlockPos ownedChestPos) {
        this.ownedChestPos = ownedChestPos == null ? null : ownedChestPos.immutable();
    }

    public boolean isOwnedChest(BlockPos pos) {
        return this.ownedChestPos != null && this.ownedChestPos.equals(pos);
    }

    public boolean isBoatCollector() {
        return boatCollector;
    }

    public int getDesiredBoatCount() {
        return desiredBoatCount;
    }

    public List<PlayerNpcInterest> getInterests() {
        return this.getUsername().getInterests();
    }

    public boolean hasInterest(PlayerNpcInterest interest) {
        return interest != null && this.getUsername().hasInterest(interest);
    }

    public boolean hasAnyInterest(List<PlayerNpcInterest> interests) {
        if (interests == null || interests.isEmpty()) {
            return false;
        }
        for (PlayerNpcInterest interest : interests) {
            if (this.hasInterest(interest)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyInterest(PlayerNpcInterest... interests) {
        if (interests == null || interests.length == 0) {
            return false;
        }
        for (PlayerNpcInterest interest : interests) {
            if (this.hasInterest(interest)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInterestGateActive(List<PlayerNpcInterest> interests) {
        if (interests == null || interests.isEmpty()) {
            return false;
        }

        boolean matchedCharacteristic = false;
        boolean matchedSelectedJob = false;
        for (PlayerNpcInterest interest : interests) {
            if (!this.hasInterest(interest)) {
                continue;
            }
            if (interest.isJob()) {
                matchedSelectedJob = matchedSelectedJob || this.isDailyJobActive(interest);
            } else {
                matchedCharacteristic = true;
            }
        }
        return matchedSelectedJob || matchedCharacteristic;
    }

    public boolean isDailyJobActive(PlayerNpcInterest interest) {
        if (PlayerNpcTeamUpManager.shouldSuspendRoutineWork(this)
                || interest == null || !interest.isJob() || !this.hasInterest(interest)) {
            return false;
        }
        if (this.isBuildingBaseSelectionLocked()) {
            return interest == PlayerNpcInterest.BUILDING;
        }
        if (this.isFarmingBaseSelectionLocked()) {
            return interest == PlayerNpcInterest.FARMING;
        }
        if (this.level() instanceof ServerLevel serverLevel) {
            if (interest == PlayerNpcInterest.BUILDING && this.shouldRunBuildingHomeDuty(serverLevel)) {
                return true;
            }
            this.tickDailyJobSelection(serverLevel);
        }
        return this.selectedDailyJobInterest == interest;
    }

    public Optional<PlayerNpcInterest> getSelectedDailyJobInterest() {
        return Optional.ofNullable(this.selectedDailyJobInterest);
    }

    public long getSelectedDailyJobDay() {
        return this.selectedDailyJobDay;
    }

    public String getSelectedDailyJobDisplayText() {
        return this.selectedDailyJobInterest == null ? "none" : this.selectedDailyJobInterest.displayName();
    }

    public boolean isBuildingBaseSelectionLocked() {
        return this.hasInterest(PlayerNpcInterest.BUILDING)
                && PlayerNpcHomeUtil.getHomeLayoutId(this).isEmpty();
    }

    public boolean isFarmingBaseSelectionLocked() {
        return !this.hasInterest(PlayerNpcInterest.BUILDING)
                && this.hasInterest(PlayerNpcInterest.FARMING)
                && PlayerNpcFarmPlan.get(this).isEmpty();
    }

    private boolean shouldRunBuildingHomeDuty(ServerLevel serverLevel) {
        if (!this.hasInterest(PlayerNpcInterest.BUILDING)
                || PlayerNpcHomeUtil.getHome(this).isEmpty()
                || (!serverLevel.isDarkOutside() && !serverLevel.isThundering())) {
            return false;
        }
        return true;
    }

    public String getInterestsDisplayText() {
        return this.getUsername().getInterestDisplayText();
    }

    public boolean isTeamFollower() {
        return this.teamId != null && !this.teamLeaderRole;
    }

    public boolean isTeamMember() {
        return this.teamId != null;
    }

    @Nullable
    public UUID getTeamId() {
        return this.teamId;
    }

    public String getTeamName() {
        return this.teamName;
    }

    @Nullable
    public UUID getTeamFounderUuid() {
        return this.teamFounderUuid;
    }

    @Nullable
    public UUID getTeamLeaderUuid() {
        return this.teamLeaderUuid;
    }

    public boolean isTeamLeaderPlayer() {
        return this.isTeamFollower() && this.teamLeaderUuid != null && this.teamLeaderIsPlayer;
    }

    public boolean isTeamLeader() {
        return this.teamId != null && this.teamLeaderRole;
    }

    public boolean isTeamUpRequestPending() {
        return this.teamUpRequestPending;
    }

    public void setTeamUpRequestPending(boolean pending) {
        this.teamUpRequestPending = pending;
    }

    public void setTeamMembership(UUID nextTeamId, String nextTeamName, UUID founderUuid, boolean asLeader,
                                  @Nullable UUID followLeaderUuid, boolean followLeaderIsPlayer) {
        if (nextTeamId == null || founderUuid == null || !asLeader && followLeaderUuid == null) {
            return;
        }
        if (!asLeader) {
            this.interruptRoutineWork();
            this.clearUpwardEscapeTarget();
        }
        this.teamId = nextTeamId;
        this.teamName = nextTeamName == null || nextTeamName.isBlank() ? "Unnamed team" : nextTeamName;
        this.teamFounderUuid = founderUuid;
        this.teamLeaderRole = asLeader;
        this.teamLeaderUuid = asLeader ? null : followLeaderUuid;
        this.teamLeaderIsPlayer = !asLeader && followLeaderIsPlayer;
        this.teamUpRequestPending = false;
        this.teamMembershipValidated = true;
        this.setTarget(null);
        this.getNavigation().stop();
        this.setCurrentAiState(AI_IDLE);
        this.setCurrentAiDetail(asLeader ? "leader of " + this.teamName : "member of " + this.teamName);
        this.wakeUpIdleWork();
    }

    public void clearTeamMembership() {
        this.clearTeamFollowUpwardEscapeTarget();
        this.teamId = null;
        this.teamName = "";
        this.teamFounderUuid = null;
        this.teamLeaderUuid = null;
        this.teamLeaderIsPlayer = false;
        this.teamLeaderRole = false;
        this.teamUpRequestPending = false;
        this.teamMembershipValidated = true;
        this.getNavigation().stop();
        this.setTarget(null);
        this.setCurrentAiState(AI_IDLE);
        this.setCurrentAiDetail("");
        this.wakeUpIdleWork();
    }

    /** Stops only scheduler-owned routine work, preserving combat and other non-job goals. */
    public void interruptRoutineWork() {
        List<WrappedGoal> runningGoals = this.goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning).toList();
        for (WrappedGoal runningGoal : runningGoals) {
            if (runningGoal.getGoal() instanceof StartupWorkGatedGoal) {
                runningGoal.stop();
            }
        }
        this.getNavigation().stop();
    }

    public boolean isTeamAlliedWith(@Nullable Entity entity) {
        if (entity == null || entity == this) {
            return entity == this;
        }
        if (this.teamId == null) {
            return false;
        }
        UUID otherUuid = entity.getUUID();
        if (this.isTeamLeaderPlayer() && this.teamLeaderUuid.equals(otherUuid)) {
            return true;
        }
        if (!(entity instanceof PlayerNpcEntity otherNpc)) {
            return false;
        }
        return this.teamId.equals(otherNpc.getTeamId());
    }

    public void setGapCooldown() {
        this.gapCooldown = random.nextInt(100, 300);
    }

    public void resetGapCooldown() {this.gapCooldown = 0; }

    public void setBucketCooldown() {
        this.bucketCooldown = random.nextInt(120, 240);
    }

    public void resetBucketCooldown() {this.bucketCooldown = 0; }

    public void setFlintAndSteelCooldown() {
        this.flintAndSteelCooldown = 20 * 60 + this.random.nextInt(20 * 120 + 1);
    }

    public void setEnderPearlCooldown() {
        this.enderPearlCooldown = random.nextInt(100, 300);
    }

    public void setSwapToBowCooldown() {
        this.swapToBowCooldown = random.nextInt(100, 300);
    }

    public void setHelpAlertCooldown(int ticks) {
        this.helpAlertCooldown = normalizeCooldown(ticks);
    }

    public void setHoleEscapeCooldown(int ticks) {
        this.holeEscapeCooldown = normalizeCooldown(ticks);
    }

    public void setRareSneakCooldown(int ticks) {
        this.rareSneakCooldown = normalizeCooldown(ticks);
    }

    public void setScaredHideCooldown(int ticks) {
        this.scaredHideCooldown = normalizeCooldown(ticks);
    }

    public void setBuildHouseCooldown(int ticks) {
        this.buildHouseCooldown = normalizeCooldown(ticks);
    }

    public void setCookFoodCooldown(int ticks) {
        this.cookFoodCooldown = normalizeCooldown(ticks);
    }

    public void setCraftGearCooldown(int ticks) {
        this.craftGearCooldown = normalizeCooldown(ticks);
    }

    public void setFarmCooldown(int ticks) {
        this.farmCooldown = normalizeCooldown(ticks);
    }

    public void setGatherCooldown(int ticks) {
        this.gatherCooldown = normalizeCooldown(ticks);
    }

    public void markStoneAccessClearing(int ticks) {
        this.stoneAccessClearCooldown = Math.max(this.stoneAccessClearCooldown, normalizeCooldown(ticks));
    }

    public void markTemporaryPillarSupport(@Nullable BlockPos pos) {
        this.markTemporaryPillarSupport(pos, TEMPORARY_PILLAR_SUPPORT_MEMORY_TICKS);
    }

    public void markTemporaryPillarSupport(@Nullable BlockPos pos, int ticks) {
        if (pos == null || ticks <= 0) {
            return;
        }
        BlockPos key = pos.immutable();
        Block placedBlock = this.level().getBlockState(key).getBlock();
        if (placedBlock == Blocks.AIR) {
            return;
        }
        this.temporaryPillarSupports.put(key, placedBlock);
        // Generic callers (escape, exploration, pickup and terraform) are not eligible for the
        // scheduled GatherLogs cleanup even when they happen to place the same material/shape.
        this.gatherLogsTemporaryPillarSupports.remove(key);
        while (this.temporaryPillarSupports.size() > MAX_TRACKED_TEMPORARY_PILLAR_SUPPORTS) {
            Iterator<BlockPos> iterator = this.temporaryPillarSupports.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            BlockPos evicted = iterator.next();
            iterator.remove();
            this.gatherLogsTemporaryPillarSupports.remove(evicted);
        }
    }

    public void markGatherLogsTemporaryPillarSupport(@Nullable BlockPos pos) {
        this.markTemporaryPillarSupport(pos, TEMPORARY_PILLAR_SUPPORT_MEMORY_TICKS);
        if (pos != null && this.temporaryPillarSupports.containsKey(pos)) {
            this.gatherLogsTemporaryPillarSupports.add(pos.immutable());
        }
    }

    public boolean isTemporaryPillarSupport(@Nullable BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockPos key = pos.immutable();
        Block expectedBlock = this.temporaryPillarSupports.get(key);
        if (expectedBlock == null) {
            return false;
        }
        if (this.level() instanceof ServerLevel serverLevel && serverLevel.hasChunkAt(key)) {
            BlockState state = serverLevel.getBlockState(key);
            if (state.getBlock() != expectedBlock
                    || state.isAir()
                    || state.getCollisionShape(serverLevel, key).isEmpty()) {
                // A removed/replaced support invalidates ownership immediately. Goals must not
                // act on a stale ledger entry during the ticks before the periodic cleanup pass.
                this.temporaryPillarSupports.remove(key);
                this.gatherLogsTemporaryPillarSupports.remove(key);
                return false;
            }
        }
        return true;
    }

    /**
     * Source-aware ownership proof for the visible GatherLogs pillar-cleanup goal. Missing source
     * metadata is deliberately treated as ineligible, even if generic ownership still exists.
     */
    public boolean isGatherLogsTemporaryPillarSupport(@Nullable BlockPos pos) {
        if (pos == null || !this.gatherLogsTemporaryPillarSupports.contains(pos)) {
            return false;
        }
        if (!this.isTemporaryPillarSupport(pos)) {
            this.gatherLogsTemporaryPillarSupports.remove(pos.immutable());
            return false;
        }
        return true;
    }

    /**
     * Bounded candidate snapshot; callers must revalidate source and exact identity immediately
     * before breaking because a snapshot itself is never permission to alter the world.
     */
    public List<BlockPos> getGatherLogsTemporaryPillarSupportsSnapshot() {
        if (this.gatherLogsTemporaryPillarSupports.isEmpty()) {
            return List.of();
        }
        return this.gatherLogsTemporaryPillarSupports.stream()
                .limit(MAX_TRACKED_TEMPORARY_PILLAR_SUPPORTS)
                .map(BlockPos::immutable)
                .toList();
    }

    public void setBiomeExploreCooldown(int ticks) {
        this.biomeExploreCooldown = normalizeCooldown(ticks);
    }

    public void setHuntSheepCooldown(int ticks) {
        this.huntSheepCooldown = normalizeCooldown(ticks);
    }

    public void setIronGolemTrollCooldown(int ticks) {
        this.ironGolemTrollCooldown = normalizeCooldown(ticks);
    }

    public void setLootChestCooldown(int ticks) {
        this.lootChestCooldown = normalizeCooldown(ticks);
    }

    public void setManageHomeCooldown(int ticks) {
        this.manageHomeCooldown = normalizeCooldown(ticks);
    }

    public void setFishingCooldown(int ticks) {
        this.fishingCooldown = Math.min(normalizeCooldown(ticks), PlayerNpcFishingGoal.MAX_SAVED_FISHING_COOLDOWN_TICKS);
    }

    public void setReturnHomeCooldown(int ticks) {
        this.returnHomeCooldown = normalizeCooldown(ticks);
    }

    public boolean requestReturnHomeAfterExplorationFailure(String reason, int ticks) {
        if (!(this.level() instanceof ServerLevel) || PlayerNpcHomeUtil.getHome(this).isEmpty()) {
            return false;
        }

        this.explorationReturnHomeRequestTicks = normalizeCooldown(ticks);
        this.returnHomeCooldown = 0;
        this.requestExplorationReturnEscape(ticks);
        this.setCurrentAiDetail(reason == null || reason.isBlank() ? "exploration failed; returning home" : reason);
        return true;
    }

    private void requestExplorationReturnEscape(int ticks) {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this);
        if (home.isEmpty()) {
            return;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        int climbBlocks = homeCenter.getY() - this.blockPosition().getY();
        if (climbBlocks <= 1 && serverLevel.canSeeSky(this.blockPosition().above())) {
            return;
        }

        int maxPillarBlocks = Math.max(
                EXPLORATION_RETURN_ESCAPE_MIN_PILLAR_BLOCKS,
                Math.min(
                        EXPLORATION_RETURN_ESCAPE_MAX_PILLAR_BLOCKS,
                        climbBlocks + EXPLORATION_RETURN_ESCAPE_EXTRA_BLOCKS
                )
        );
        this.requestUpwardEscapeTo(homeCenter, Math.max(normalizeCooldown(ticks), 20 * 8), maxPillarBlocks);
    }

    public boolean hasExplorationReturnHomeRequest() {
        return this.explorationReturnHomeRequestTicks > 0;
    }

    public void clearExplorationReturnHomeRequest() {
        this.explorationReturnHomeRequestTicks = 0;
    }

    public void setSleepCooldown(int ticks) {
        this.sleepCooldown = normalizeCooldown(ticks);
    }

    public void setCraftCooldown(int ticks) {
        this.craftCooldown = normalizeCooldown(ticks);
    }

    public void setOreMiningCooldown(int ticks) {
        this.oreMiningCooldown = normalizeCooldown(ticks);
    }

    public void setIronGearCooldown(int ticks) {
        this.ironGearCooldown = normalizeCooldown(ticks);
    }

    public void setSpyglassCooldown(int ticks) {
        this.spyglassCooldown = normalizeCooldown(ticks);
    }

    public void setSaplingPlantCooldown(int ticks) {
        this.saplingPlantCooldown = normalizeCooldown(ticks);
    }

    public void setBoatStockCooldown(int ticks) {
        this.boatStockCooldown = normalizeCooldown(ticks);
    }

    public void setBoatTrapCooldown(int ticks) {
        this.boatTrapCooldown = normalizeCooldown(ticks);
    }

    public void setJukeboxDanceCooldown(int ticks) {
        this.jukeboxDanceCooldown = normalizeCooldown(ticks);
    }

    public void setTrollHitCooldown(int ticks) {
        this.trollHitCooldown = normalizeCooldown(ticks);
    }

    public void setCombatFishingCooldown(int ticks) {
        this.combatFishingCooldown = normalizeCooldown(ticks);
    }

    public void setShieldCraftCooldown(int ticks) {
        this.shieldCraftCooldown = normalizeCooldown(ticks);
    }

    public void setShieldGuardCooldown(int ticks) {
        this.shieldGuardCooldown = normalizeCooldown(ticks);
    }

    public void wakeUpIdleWork() {
        this.buildHouseCooldown = 0;
        this.craftGearCooldown = 0;
        this.manageHomeCooldown = 0;
        this.returnHomeCooldown = 0;
        this.craftCooldown = 0;
        this.farmCooldown = 0;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.oreMiningCooldown = 0;
        this.saplingPlantCooldown = 0;
        this.playingIdleCooldown = 0;
    }

    public boolean hasAnimalLootPriority() {
        return this.animalLootPriorityTicks > 0 && this.animalLootPriorityPos != null;
    }

    @Nullable
    public BlockPos getAnimalLootPriorityPos() {
        return this.animalLootPriorityPos;
    }

    public void clearAnimalLootPriority() {
        this.animalLootPriorityTicks = 0;
        this.animalLootPriorityPos = null;
    }

    public boolean hasCollectableSupplyDropNearby(double radius) {
        if (this.level().isClientSide() || radius <= 0.0D || this.isItemPickupSuppressed()) {
            return false;
        }

        AABB searchBox = this.getBoundingBox().inflate(radius, Math.min(6.0D, radius), radius);
        return !this.level().getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                item -> item.isAlive()
                        && !item.isRemoved()
                        && !item.getItem().isEmpty()
                        && !PlayerNpcTrashUtil.isDiscarded(item.getItem())
                        && InventoryUtils.isInventoryBackedSupplyDrop(item.getItem())
                        && this.canAcceptInventoryStack(item.getItem())
        ).isEmpty();
    }

    public boolean canAcceptInventoryStack(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return false;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);
            if (slotStack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameComponents(slotStack, incoming)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public int getRawLogReserveTarget() {
        return this.rawLogReserveTarget;
    }

    public int getLogSupplyGoal() {
        return Math.max(this.rawLogReserveTarget, this.activeLogSupplyTarget);
    }

    public int getWoodSupplyTarget() {
        return this.woodSupplyTarget;
    }

    public int getCobblestoneSupplyTarget() {
        return this.cobblestoneSupplyTarget;
    }

    public int getStoneSupplyGoal() {
        return Math.max(this.cobblestoneSupplyTarget, this.activeStoneSupplyTarget);
    }

    public void beginLogSupplyGatheringEpisode() {
        this.activeLogSupplyTarget = ResourceAi.countLogs(this) < this.rawLogReserveTarget
                ? this.rawLogReserveTarget + ResourceAi.randomAdditionalSupplyAmount(this.getRandom())
                : 0;
    }

    public void endLogSupplyGatheringEpisode() {
        this.activeLogSupplyTarget = 0;
    }

    public void beginStoneSupplyGatheringEpisode() {
        this.activeStoneSupplyTarget = ResourceAi.countStone(this) < this.cobblestoneSupplyTarget
                ? this.cobblestoneSupplyTarget + ResourceAi.randomAdditionalSupplyAmount(this.getRandom())
                : 0;
    }

    public void endStoneSupplyGatheringEpisode() {
        this.activeStoneSupplyTarget = 0;
    }

    public boolean shouldPrioritizeLogGathering() {
        return ResourceAi.countLogs(this) < this.getLogSupplyGoal();
    }

    public boolean shouldPrioritizeCobblestoneGathering() {
        return ResourceAi.countStone(this) < this.getStoneSupplyGoal();
    }

    public boolean hasMetBuildSupplyGoals() {
        return !this.shouldPrioritizeLogGathering()
                && !this.shouldPrioritizeCobblestoneGathering();
    }

    private boolean hasHeldOrInventoryTool(Object toolClass) {
        if (SmartNpcItemUtil.matches(toolClass, this.getMainHandItem().getItem())
                || SmartNpcItemUtil.matches(toolClass, this.getOffhandItem().getItem())
                || SmartNpcItemUtil.matches(toolClass, this.mainWeaponItem.getItem())
                || SmartNpcItemUtil.matches(toolClass, this.offWeaponItem.getItem())) {
            return true;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && SmartNpcItemUtil.matches(toolClass, stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private int countHeldAndInventoryItems(Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = this.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void prioritizeAnimalLoot(BlockPos pos) {
        this.animalLootPriorityTicks = 20 * 8;
        this.animalLootPriorityPos = pos == null ? this.blockPosition() : pos.immutable();
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.fishingCooldown = 0;
        this.playingIdleCooldown = 0;
        this.setCurrentAiState("ai.player_npc.collecting_item");
        this.setCurrentAiDetail("animal drops @ "
                + this.animalLootPriorityPos.getX() + " "
                + this.animalLootPriorityPos.getY() + " "
                + this.animalLootPriorityPos.getZ());
    }

    private static int normalizeCooldown(int ticks) {
        return Math.max(0, ticks);
    }

    private boolean mainWeaponDisarmed = false;

    public boolean isMainWeaponDisarmed() {
        return mainWeaponDisarmed;
    }

    public void setMainWeaponDisarmed(boolean mainWeaponDisarmed) {
        this.mainWeaponDisarmed = mainWeaponDisarmed;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public boolean hasInventoryItem(Predicate<ItemStack> matcher) {
        return InventoryUtils.hasItem(this.inventory, matcher);
    }

    public boolean hasInventoryItem(ItemLike itemLike) {
        return InventoryUtils.hasItem(this.inventory, itemLike);
    }

    public Optional<ItemStack> consumeInventoryItem(Predicate<ItemStack> matcher, int count) {
        return InventoryUtils.consumeItem(this.inventory, matcher, count);
    }

    public Optional<ItemStack> consumeInventoryItem(ItemLike itemLike, int count) {
        return InventoryUtils.consumeItem(this.inventory, itemLike, count);
    }

    public ItemStack getMainWeaponItem() {
        return mainWeaponItem;
    }

    public void setMainWeaponItem(ItemStack mainWeaponItem) {
        this.replaceMainWeaponItem(mainWeaponItem, true, ItemStack.EMPTY);
    }

    public void cacheMainWeaponItemForAi(ItemStack mainWeaponItem) {
        this.replaceMainWeaponItem(mainWeaponItem, false, ItemStack.EMPTY);
    }

    public ItemStack takeMainWeaponItem(Predicate<ItemStack> matcher) {
        if (this.mainWeaponItem.isEmpty() || matcher == null || !matcher.test(this.mainWeaponItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack weapon = this.mainWeaponItem.copy();
        this.mainWeaponItem = ItemStack.EMPTY;
        return weapon;
    }

    public ItemStack takeOffWeaponItem(Predicate<ItemStack> matcher) {
        if (this.offWeaponItem.isEmpty() || matcher == null || !matcher.test(this.offWeaponItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack weapon = this.offWeaponItem.copy();
        this.offWeaponItem = ItemStack.EMPTY;
        return weapon;
    }

    public boolean hasCarriedTool(Object toolClass) {
        return this.hasHeldOrInventoryTool(toolClass);
    }

    public boolean promoteMainWeaponItem(ItemStack stack) {
        if (!this.isCombatMainHandGear(stack)
                || this.gearScore(stack) <= this.cachedCombatWeaponScore() + 0.05D) {
            return false;
        }
        this.setMainWeaponItem(stack);
        return true;
    }

    private boolean promoteMainWeaponItemFromEquip(ItemStack newItem, ItemStack oldItem) {
        if (!this.isCombatMainHandGear(newItem)
                || this.gearScore(newItem) <= this.cachedCombatWeaponScore() + 0.05D) {
            return false;
        }
        this.replaceMainWeaponItem(newItem, true, oldItem);
        return true;
    }

    private double cachedCombatWeaponScore() {
        return this.isCombatMainHandGear(this.mainWeaponItem)
                ? this.gearScore(this.mainWeaponItem)
                : 0.0D;
    }

    public void setMainHandItemForAi(ItemStack stack) {
        this.suppressHeldItemCacheUpdate = true;
        try {
            this.setItemInHand(InteractionHand.MAIN_HAND, stack == null ? ItemStack.EMPTY : stack.copy());
        } finally {
            this.suppressHeldItemCacheUpdate = false;
        }
    }

    public boolean equipTemporaryBowFromInventory() {
        if (this.temporaryBowEquipped || this.getMainHandItem().getItem() instanceof BowItem) {
            return true;
        }

        ItemStack bow = this.consumeInventoryItem(stack -> stack.getItem() instanceof BowItem, 1)
                .orElse(ItemStack.EMPTY);
        if (bow.isEmpty()) {
            return false;
        }

        this.temporaryBowPreviousMainHand = this.getMainHandItem().copy();
        this.temporaryBowEquipped = true;
        this.setMainHandItemForAi(bow);
        return true;
    }

    public void restoreMainHandAfterTemporaryBow() {
        if (!this.temporaryBowEquipped) {
            return;
        }

        ItemStack temporaryItem = this.getMainHandItem().copy();
        ItemStack previousMainHand = this.temporaryBowPreviousMainHand.copy();
        this.temporaryBowPreviousMainHand = ItemStack.EMPTY;
        this.temporaryBowEquipped = false;

        this.setMainHandItemForAi(previousMainHand);
        if (!temporaryItem.isEmpty()
                && !ItemStack.isSameItemSameComponents(temporaryItem, previousMainHand)) {
            this.addOrDropInventoryItem(temporaryItem);
        }
        this.setSwapToBowCooldown();
    }

    private void replaceMainWeaponItem(ItemStack stack, boolean moveOldToInventory, ItemStack oldEquippedItem) {
        ItemStack next = stack == null ? ItemStack.EMPTY : stack.copy();
        if (!next.isEmpty()) {
            next.setCount(1);
        }

        if (ItemStack.isSameItemSameComponents(this.mainWeaponItem, next)) {
            this.mainWeaponItem = next;
            if (!this.mainWeaponItem.isEmpty()) {
                this.mainWeaponDisarmed = false;
            }
            return;
        }

        ItemStack previous = this.mainWeaponItem.copy();
        this.mainWeaponItem = next;

        if (!this.mainWeaponItem.isEmpty()) {
            this.mainWeaponDisarmed = false;
        }

        if (!moveOldToInventory
                || previous.isEmpty()
                || (!oldEquippedItem.isEmpty() && ItemStack.isSameItemSameComponents(previous, oldEquippedItem))
                || this.isCurrentlyHeld(previous)
                || ItemStack.isSameItemSameComponents(previous, this.mainWeaponItem)) {
            return;
        }

        this.addOrDropInventoryItem(previous);
    }

    private boolean isCurrentlyHeld(ItemStack stack) {
        return !stack.isEmpty()
                && (ItemStack.isSameItemSameComponents(stack, this.getMainHandItem())
                || ItemStack.isSameItemSameComponents(stack, this.getOffhandItem()));
    }

    private void addOrDropInventoryItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copy();
        if (!InventoryUtils.addItem(this.inventory, copy)) {
            this.spawnAtLocation(copy);
        }
    }

    private void repairLegacyRangedMainHandAfterLoad() {
        ItemStack rangedWeapon = this.getMainHandItem().copy();
        if (!this.isRangedMainHandGear(rangedWeapon)) {
            if (this.isCombatMainHandGear(this.getMainHandItem())
                    && this.isRangedMainHandGear(this.mainWeaponItem)) {
                this.mainWeaponItem = this.getMainHandItem().copy();
                this.mainWeaponItem.setCount(1);
                this.mainWeaponDisarmed = false;
            }
            return;
        }

        if (!this.mainWeaponItem.isEmpty()
                && !this.isRangedMainHandGear(this.mainWeaponItem)
                && this.isMainHandGear(this.mainWeaponItem)) {
            this.setMainHandItemForAi(this.mainWeaponItem);
            this.addOrDropInventoryItem(rangedWeapon);
            this.setSwapToBowCooldown();
            return;
        }

        if (this.isRangedMainHandGear(this.mainWeaponItem)) {
            this.mainWeaponItem = ItemStack.EMPTY;
        }
        if (this.equipBestMainHandFromInventory()) {
            this.inventory.setChanged();
            this.setSwapToBowCooldown();
        }
    }

    private void materializeCachedMainWeaponAfterLoad() {
        if (this.mainWeaponItem.isEmpty() || !this.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack weapon = this.mainWeaponItem.copy();
        weapon.setCount(1);
        if (InventoryUtils.addItem(this.inventory, weapon)) {
            this.inventory.setChanged();
            this.mainWeaponItem = ItemStack.EMPTY;
            this.mainWeaponDisarmed = false;
            return;
        }

        this.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
        this.mainWeaponItem = weapon.copy();
        this.mainWeaponDisarmed = false;
    }

    public ItemStack getOffWeaponItem() { return offWeaponItem; }

    public void setOffWeaponItem(ItemStack offWeaponItem) {
        this.offWeaponItem = offWeaponItem == null ? ItemStack.EMPTY : offWeaponItem.copy();
    }

    public void setUseBow(boolean useBow) {
        this.useBow = useBow;
    }

    public boolean isUseBow() {
        return useBow;
    }

    public PlayerNpcEntity(EntityType<? extends PlayerNpcEntity> entitytype, Level level) {
        super(entitytype, level);
        Objects.requireNonNull(this.getAttribute(Attributes.STEP_HEIGHT)).setBaseValue(1.0D);
        this.xpReward = 50;
        this.setNoAi(false);
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
        this.placeBlockToParryChance = new Random().nextDouble(0.20, 0.40);
        this.rawLogReserveTarget = ResourceAi.randomLogSupplyGoal(this.getRandom());
        this.woodSupplyTarget = this.rawLogReserveTarget;
        this.cobblestoneSupplyTarget = ResourceAi.randomStoneSupplyGoal(this.getRandom());
        this.setCanPickUpLoot(true);
    }

    public int getStoredExperience() {
        return this.storedExperience;
    }

    public void awardStoredExperience(int amount) {
        if (amount <= 0 || this.level().isClientSide()) {
            return;
        }

        long updated = (long) this.storedExperience + amount;
        this.storedExperience = updated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) updated;
    }

    @Override
    protected int getBaseExperienceReward(ServerLevel level) {
        long reward = (long) super.getBaseExperienceReward(level) + this.storedExperience;
        return reward > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, reward);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MAIN_HAND_ATTACK_ANIMATION_TICKS, 0);
        builder.define(SOCIAL_NOTICE_PAUSES_WORK, false);
        builder.define(HEALING, false);
        builder.define(BETTER_COMBAT_ATTACK_ANIMATION_TICKS, 0);
        builder.define(BETTER_COMBAT_ATTACK_SEQUENCE, 0);
        builder.define(AI_STATE, AI_IDLE);
        builder.define(AI_DETAIL, "");
        builder.define(DANCING, false);
        builder.define(EPIC_FIGHT_DIGGING, false);
        builder.define(SNEAKING_AI_HIDES_DISPLAY_NAME, false);
    }

    @Override
    protected void addAdditionalSaveData(@NotNull ValueOutput tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean(PENDING_SPAWN_INITIALIZATION_TAG, this.pendingSpawnInitialization);
        this.inventory.storeAsItemList(tag.list("Inventory", ItemStack.CODEC));
        tag.putInt("GapCooldown", this.gapCooldown);
        tag.putInt("BucketCooldown", this.bucketCooldown);
        tag.putInt("FlintAndSteelCooldown", this.flintAndSteelCooldown);
        tag.putInt("EnderPearlCooldown", this.enderPearlCooldown);
        tag.putInt("SwapToBowCooldown", this.swapToBowCooldown);
        tag.putInt("HelpAlertCooldown", this.helpAlertCooldown);
        tag.putInt("HoleEscapeCooldown", this.holeEscapeCooldown);
        tag.putInt("RareSneakCooldown", this.rareSneakCooldown);
        tag.putInt("ScaredHideCooldown", this.scaredHideCooldown);
        tag.putInt("BuildHouseCooldown", this.buildHouseCooldown);
        tag.putInt("CookFoodCooldown", this.cookFoodCooldown);
        tag.putInt("CraftGearCooldown", this.craftGearCooldown);
        tag.putInt("FarmCooldown", this.farmCooldown);
        tag.putInt("GatherCooldown", this.gatherCooldown);
        tag.putInt("BiomeExploreCooldown", this.biomeExploreCooldown);
        tag.putInt("HuntSheepCooldown", this.huntSheepCooldown);
        tag.putInt("IronGolemTrollCooldown", this.ironGolemTrollCooldown);
        tag.putInt("LootChestCooldown", this.lootChestCooldown);
        tag.putInt("ManageHomeCooldown", this.manageHomeCooldown);
        tag.putInt("FishingCooldown", this.fishingCooldown);
        tag.putInt("ReturnHomeCooldown", this.returnHomeCooldown);
        tag.putInt("ExplorationReturnHomeRequestTicks", this.explorationReturnHomeRequestTicks);
        tag.putInt("SleepCooldown", this.sleepCooldown);
        tag.putInt("CraftCooldown", this.craftCooldown);
        tag.putInt("OreMiningCooldown", this.oreMiningCooldown);
        tag.putInt("IronGearCooldown", this.ironGearCooldown);
        tag.putInt("SpyglassCooldown", this.spyglassCooldown);
        tag.putInt("SaplingPlantCooldown", this.saplingPlantCooldown);
        tag.putInt("BoatStockCooldown", this.boatStockCooldown);
        tag.putInt("BoatTrapCooldown", this.boatTrapCooldown);
        tag.putInt("JukeboxDanceCooldown", this.jukeboxDanceCooldown);
        tag.putInt("TrollHitCooldown", this.trollHitCooldown);
        tag.putInt("CombatFishingCooldown", this.combatFishingCooldown);
        tag.putInt("ShieldCraftCooldown", this.shieldCraftCooldown);
        tag.putInt("ShieldGuardCooldown", this.shieldGuardCooldown);
        tag.putInt("StoredExperience", this.storedExperience);
        tag.putBoolean("BoatCollector", this.boatCollector);
        tag.putInt("DesiredBoatCount", this.desiredBoatCount);
        tag.putInt("RawLogReserveTarget", this.rawLogReserveTarget);
        tag.putInt("WoodSupplyTarget", this.woodSupplyTarget);
        tag.putInt("CobblestoneSupplyTarget", this.cobblestoneSupplyTarget);
        tag.putLong("LastSupplyGoalRerollDay", this.lastSupplyGoalRerollDay);
        tag.putInt("FishingStarterStringVersion", this.fishingStarterStringVersion);
        tag.putLong("SelectedDailyJobDay", this.selectedDailyJobDay);
        if (this.selectedDailyJobInterest != null) {
            tag.putString("SelectedDailyJobInterest", this.selectedDailyJobInterest.name());
        }
        tag.putBoolean("UseBow", this.useBow);
        tag.putDouble("BlockProjectileChance", this.placeBlockToParryChance);
        tag.putInt("BlockParryCooldown", this.placeBlockParryCooldown);
        if (!this.mainWeaponItem.isEmpty()) {
            tag.store("MainHandItem", ItemStack.CODEC, this.mainWeaponItem);
        }
        if (!this.offWeaponItem.isEmpty()) {
            tag.store("OffHandItem", ItemStack.CODEC, this.offWeaponItem);
        }
        if (this.temporaryBowEquipped) {
            tag.putBoolean("TemporaryBowEquipped", true);
            if (!this.temporaryBowPreviousMainHand.isEmpty()) {
                tag.store("TemporaryBowPreviousMainHand", ItemStack.CODEC, this.temporaryBowPreviousMainHand);
            }
        }
        if (this.ownedChestPos != null) {
            tag.putInt("OwnedChestX", this.ownedChestPos.getX());
            tag.putInt("OwnedChestY", this.ownedChestPos.getY());
            tag.putInt("OwnedChestZ", this.ownedChestPos.getZ());
        }
        tag.discard("TeamId");
        tag.discard("TeamName");
        tag.discard("TeamFounder");
        tag.discard("TeamLeader");
        tag.discard("TeamLeaderIsPlayer");
        tag.discard("TeamLeaderRole");
        if (this.teamId != null) {
            SmartNpcNbt.putUuid(tag, "TeamId", this.teamId);
            tag.putString("TeamName", this.teamName);
            if (this.teamFounderUuid != null) {
                SmartNpcNbt.putUuid(tag, "TeamFounder", this.teamFounderUuid);
            }
            tag.putBoolean("TeamLeaderRole", this.teamLeaderRole);
        }
        if (this.isTeamFollower() && this.teamLeaderUuid != null) {
            SmartNpcNbt.putUuid(tag, "TeamLeader", this.teamLeaderUuid);
            tag.putBoolean("TeamLeaderIsPlayer", this.teamLeaderIsPlayer);
        }
        ValueOutput.ValueOutputList temporarySupports = tag.childrenList(TEMPORARY_PILLAR_SUPPORTS_TAG);
        for (Map.Entry<BlockPos, Block> entry : this.temporaryPillarSupports.entrySet()) {
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(entry.getValue());
            if (blockId == null || entry.getValue() == Blocks.AIR) {
                continue;
            }
            ValueOutput supportTag = temporarySupports.addChild();
            supportTag.putLong("Pos", entry.getKey().asLong());
            supportTag.putString("Block", blockId.toString());
            if (this.gatherLogsTemporaryPillarSupports.contains(entry.getKey())) {
                supportTag.putString("Source", "GATHER_LOGS");
            }
        }
        PlayerNpcHomeUtil.saveHome(this, tag);
        tag.putBoolean("MainWeaponDisarmed", this.mainWeaponDisarmed);
    }

    @Override
    protected void readAdditionalSaveData(@NotNull ValueInput tag) {
        super.readAdditionalSaveData(tag);
        this.pendingSpawnInitialization = tag.getBooleanOr(PENDING_SPAWN_INITIALIZATION_TAG, false);
        this.inventory.fromItemList(tag.listOrEmpty("Inventory", ItemStack.CODEC));
        this.gapCooldown = tag.getIntOr("GapCooldown", 0);
        this.bucketCooldown = tag.getIntOr("BucketCooldown", 0);
        this.flintAndSteelCooldown = tag.getIntOr("FlintAndSteelCooldown", 0);
        this.enderPearlCooldown = tag.getIntOr("EnderPearlCooldown", 0);
        this.swapToBowCooldown = tag.getIntOr("SwapToBowCooldown", 0);
        this.helpAlertCooldown = tag.getIntOr("HelpAlertCooldown", 0);
        this.holeEscapeCooldown = tag.getIntOr("HoleEscapeCooldown", 0);
        this.rareSneakCooldown = tag.getIntOr("RareSneakCooldown", 0);
        this.scaredHideCooldown = tag.getIntOr("ScaredHideCooldown", 0);
        this.buildHouseCooldown = tag.getIntOr("BuildHouseCooldown", 0);
        this.cookFoodCooldown = tag.getIntOr("CookFoodCooldown", 0);
        this.craftGearCooldown = tag.getIntOr("CraftGearCooldown", 0);
        this.farmCooldown = tag.getIntOr("FarmCooldown", 0);
        this.gatherCooldown = tag.getIntOr("GatherCooldown", 0);
        this.biomeExploreCooldown = tag.getIntOr("BiomeExploreCooldown", 0);
        this.huntSheepCooldown = tag.getIntOr("HuntSheepCooldown", 0);
        this.ironGolemTrollCooldown = tag.getIntOr("IronGolemTrollCooldown", 0);
        this.lootChestCooldown = tag.getIntOr("LootChestCooldown", 0);
        this.manageHomeCooldown = tag.getIntOr("ManageHomeCooldown", 0);
        this.setFishingCooldown(tag.getIntOr("FishingCooldown", 0));
        this.returnHomeCooldown = tag.getIntOr("ReturnHomeCooldown", 0);
        this.explorationReturnHomeRequestTicks = tag.getIntOr("ExplorationReturnHomeRequestTicks", 0);
        this.sleepCooldown = tag.getIntOr("SleepCooldown", 0);
        this.craftCooldown = tag.getIntOr("CraftCooldown", 0);
        this.oreMiningCooldown = tag.getIntOr("OreMiningCooldown", 0);
        this.ironGearCooldown = tag.getIntOr("IronGearCooldown", 0);
        this.spyglassCooldown = tag.getIntOr("SpyglassCooldown", 0);
        this.saplingPlantCooldown = tag.getIntOr("SaplingPlantCooldown", 0);
        this.boatStockCooldown = tag.getIntOr("BoatStockCooldown", 0);
        this.boatTrapCooldown = tag.getIntOr("BoatTrapCooldown", 0);
        this.jukeboxDanceCooldown = tag.getIntOr("JukeboxDanceCooldown", 0);
        this.trollHitCooldown = tag.getIntOr("TrollHitCooldown", 0);
        this.combatFishingCooldown = tag.getIntOr("CombatFishingCooldown", 0);
        this.shieldCraftCooldown = tag.getIntOr("ShieldCraftCooldown", 0);
        this.shieldGuardCooldown = tag.getIntOr("ShieldGuardCooldown", 0);
        if (tag.keySet().contains("StoredExperience")) {
            this.storedExperience = Math.max(0, tag.getIntOr("StoredExperience", 0));
        }
        if (tag.keySet().contains("BoatCollector")) {
            this.boatCollector = tag.getBooleanOr("BoatCollector", false);
        }
        if (tag.keySet().contains("DesiredBoatCount")) {
            this.desiredBoatCount = Math.max(2, Math.min(3, tag.getIntOr("DesiredBoatCount", 0)));
        }
        if (tag.keySet().contains("RawLogReserveTarget")) {
            this.rawLogReserveTarget = Math.max(0, tag.getIntOr("RawLogReserveTarget", 0));
        }
        if (tag.keySet().contains("WoodSupplyTarget")) {
            this.woodSupplyTarget = Math.max(0, tag.getIntOr("WoodSupplyTarget", 0));
        }
        if (tag.keySet().contains("CobblestoneSupplyTarget")) {
            this.cobblestoneSupplyTarget = Math.max(0, tag.getIntOr("CobblestoneSupplyTarget", 0));
        }
        if (tag.keySet().contains("LastSupplyGoalRerollDay")) {
            this.lastSupplyGoalRerollDay = tag.getLongOr("LastSupplyGoalRerollDay", 0L);
        }
        this.fishingStarterStringVersion = tag.getIntOr("FishingStarterStringVersion", 0);
        if (tag.keySet().contains("SelectedDailyJobDay")) {
            this.selectedDailyJobDay = tag.getLongOr("SelectedDailyJobDay", 0L);
        }
        this.selectedDailyJobInterest = parseSavedDailyJobInterest(tag.getStringOr("SelectedDailyJobInterest", "")).orElse(null);
        this.teamLeaderUuid = SmartNpcNbt.hasUuid(tag, "TeamLeader") ? SmartNpcNbt.getUuid(tag, "TeamLeader") : null;
        this.teamLeaderIsPlayer = this.teamLeaderUuid != null && tag.getBooleanOr("TeamLeaderIsPlayer", false);
        this.teamLeaderRole = this.teamLeaderUuid == null && tag.getBooleanOr("TeamLeaderRole", false);
        this.teamId = SmartNpcNbt.hasUuid(tag, "TeamId")
                ? SmartNpcNbt.getUuid(tag, "TeamId")
                : this.teamLeaderUuid != null
                ? this.teamLeaderUuid
                : this.teamLeaderRole
                ? this.getUUID()
                : null;
        this.teamName = this.teamId == null
                ? ""
                : tag.keySet().contains("TeamName") && !tag.getStringOr("TeamName", "").isBlank()
                ? tag.getStringOr("TeamName", "")
                : this.getDisplayName().getString() + "'s team";
        this.teamFounderUuid = this.teamId == null
                ? null
                : SmartNpcNbt.hasUuid(tag, "TeamFounder")
                ? SmartNpcNbt.getUuid(tag, "TeamFounder")
                : this.teamLeaderIsPlayer && this.teamLeaderUuid != null
                ? this.teamLeaderUuid
                : this.teamLeaderRole
                ? this.getUUID()
                : this.teamLeaderUuid;
        this.teamUpRequestPending = false;
        this.teamMembershipValidated = false;
        this.temporaryPillarSupports.clear();
        this.gatherLogsTemporaryPillarSupports.clear();
        if (tag.keySet().contains(TEMPORARY_PILLAR_SUPPORTS_TAG)) {
            int supportCount = 0;
            for (ValueInput supportTag : tag.childrenListOrEmpty(TEMPORARY_PILLAR_SUPPORTS_TAG)) {
                if (supportCount++ >= MAX_TRACKED_TEMPORARY_PILLAR_SUPPORTS) {
                    break;
                }
                Identifier blockId = Identifier.tryParse(supportTag.getStringOr("Block", ""));
                Block block = blockId == null ? null : BuiltInRegistries.BLOCK.get(blockId).map(net.minecraft.core.Holder::value).orElse(null);
                if (block != null && block != Blocks.AIR) {
                    BlockPos supportPos = BlockPos.of(supportTag.getLongOr("Pos", 0L)).immutable();
                    this.temporaryPillarSupports.put(supportPos, block);
                    if ("GATHER_LOGS".equals(supportTag.getStringOr("Source", ""))) {
                        this.gatherLogsTemporaryPillarSupports.add(supportPos);
                    }
                }
            }
        }
        this.useBow = tag.getBooleanOr("UseBow", false);
        if (tag.keySet().contains("BlockProjectileChance")) {
            this.placeBlockToParryChance = tag.getDoubleOr("BlockProjectileChance", 0.0D);
        }
        this.placeBlockParryCooldown = tag.getIntOr("BlockParryCooldown", 0);
        this.mainWeaponItem = tag.read("MainHandItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.offWeaponItem = tag.read("OffHandItem", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        this.temporaryBowEquipped = tag.getBooleanOr("TemporaryBowEquipped", false);
        if (this.temporaryBowEquipped && tag.keySet().contains("TemporaryBowPreviousMainHand")) {
            this.temporaryBowPreviousMainHand = tag.read("TemporaryBowPreviousMainHand", ItemStack.CODEC)
                    .orElse(ItemStack.EMPTY);
        } else {
            this.temporaryBowPreviousMainHand = ItemStack.EMPTY;
        }
        if (tag.keySet().contains("OwnedChestX")
                && tag.keySet().contains("OwnedChestY")
                && tag.keySet().contains("OwnedChestZ")) {
            this.ownedChestPos = new BlockPos(
                    tag.getIntOr("OwnedChestX", 0),
                    tag.getIntOr("OwnedChestY", 0),
                    tag.getIntOr("OwnedChestZ", 0)
            );
        } else {
            this.ownedChestPos = null;
        }
        PlayerNpcHomeUtil.readHome(this, tag);
        this.mainWeaponDisarmed = tag.getBooleanOr("MainWeaponDisarmed", false);
        this.restoreMainHandAfterTemporaryBow();
        this.repairLegacyRangedMainHandAfterLoad();
        this.materializeCachedMainWeaponAfterLoad();
    }

    @Override
    protected void dropCustomDeathLoot(@NotNull ServerLevel level, @NotNull DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);

        if (!this.getPersistentData().getBooleanOr("die_by_possess", false)) {
            this.dropRandomlyDamagedEquipment();
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
            }
        }
    }

    private void dropRandomlyDamagedEquipment() {
        for (EquipmentSlot slot : DEATH_LOOT_EQUIPMENT_SLOTS) {
            ItemStack equipped = this.getItemBySlot(slot);
            if (equipped.isEmpty()) {
                continue;
            }

            ItemStack dropped = equipped.copy();
            dropped.setCount(1);
            this.applyRandomDeathLootDamage(dropped);

            this.setItemSlot(slot, ItemStack.EMPTY);
            this.spawnAtLocation(dropped);
        }
    }

    private void applyRandomDeathLootDamage(ItemStack stack) {
        if (!stack.isDamageableItem()) {
            return;
        }

        int maximumDamage = stack.getMaxDamage();
        int minimumDamage = Math.max(
                stack.getDamageValue(),
                Math.max(1, maximumDamage * 25 / 100)
        );
        int maximumSurvivingDamage = maximumDamage - 1;
        if (minimumDamage <= maximumSurvivingDamage) {
            stack.setDamageValue(Mth.nextInt(this.getRandom(), minimumDamage, maximumSurvivingDamage));
        }
    }

    private boolean shouldCustomInventoryPickup(ItemStack stack) {
        if (this.isItemPickupSuppressed() || stack.isEmpty() || PlayerNpcTrashUtil.isDiscarded(stack)) {
            return false;
        }

        EquipmentSlot slot = this.getEquipmentSlotForItem(stack);

        if (slot.isArmor()) {
            return !(this.level() instanceof ServerLevel serverLevel) || !this.wantsToPickUp(serverLevel, stack);
        }

        return !isRecoverableWeapon(stack)
                || this.getTarget() == null
                || !this.getMainHandItem().isEmpty();
    }

    private boolean isRecoverableWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();

        return item.builtInRegistryHolder().is(ItemTags.SWORDS)
                || item.components().has(DataComponents.TOOL)
                || item instanceof TridentItem;
    }

    @Override
    public boolean wantsToPickUp(@NotNull ServerLevel serverLevel, @NotNull ItemStack stack) {
        if (this.isItemPickupSuppressed() || stack.isEmpty() || PlayerNpcTrashUtil.isDiscarded(stack)) {
            return false;
        }

        EquipmentSlot slot = this.getEquipmentSlotForItem(stack);
        if (!slot.isArmor()) {
            return false;
        }
        return super.wantsToPickUp(serverLevel, stack);
    }

    public boolean isSmartNpcCompatPlayerLikeTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatMonsterTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatVillagerTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatAnimalTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatHighDangerThreat(LivingEntity target) {
        return false;
    }

    public boolean shouldSmartNpcAvoidTrollHitTarget(LivingEntity target) {
        return target != null && this.isSmartNpcCompatHighDangerThreat(target);
    }

    public float getSmartNpcTargetAttackChance(LivingEntity target, float baseChance) {
        float chance = Math.max(0.0F, Math.min(1.0F, baseChance));
        if (target != null && this.isSmartNpcCompatHighDangerThreat(target)) {
            chance = Math.min(chance, 0.15F);
        }
        return chance;
    }

    public float getSmartNpcFleeHealthRatio(LivingEntity threat, float baseHealthRatio) {
        if (!this.hasInterest(PlayerNpcInterest.COWARD)) {
            return 0.0F;
        }
        float ratio = Math.max(0.0F, Math.min(1.0F, baseHealthRatio));
        if (threat != null && this.isSmartNpcCompatHighDangerThreat(threat)) {
            ratio = Math.max(ratio, 0.85F);
        }
        return ratio;
    }

    public boolean shouldSmartNpcAttackTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (this.shouldSmartNpcFleeFromTarget(target)) {
            return false;
        }
        return this.getRandom().nextFloat() <= this.getSmartNpcTargetAttackChance(target, 1.0F);
    }

    public boolean shouldSmartNpcFleeFromTarget(LivingEntity threat) {
        return this.hasInterest(PlayerNpcInterest.COWARD) && threat != null
                && threat.isAlive()
                && this.getHealth() / this.getMaxHealth() <= this.getSmartNpcFleeHealthRatio(threat, 0.0F);
    }

    protected void registerGoals() {
        this.workGoalRegistrationIndex = 0;
        GatherLogsGoal gatherLogsGoal = new GatherLogsGoal(this, 1.0D);
        GatherMissingBuildMaterialGoal gatherMissingBuildMaterialGoal = new GatherMissingBuildMaterialGoal(this, 1.0D);
        TerraformBuildSiteGoal terraformBuildSiteGoal = new TerraformBuildSiteGoal(this, 1.0D);
        DigDownForStoneGoal digDownForStoneGoal = new DigDownForStoneGoal(this, 1.0D);
        // Cooperative buoyancy leaves MOVE/JUMP with the active work or combat goal.
        // Destination-aware water recovery is ticked by the work goal instead of globally
        // redirecting the NPC to an unrelated nearby bank.
        this.goalSelector.addGoal(0, new PlayerNpcFloatGoal(this));
        this.goalSelector.addGoal(0, new NoticeNearbyPlayerGoal(this));
        this.goalSelector.addGoal(0, new WaterFallGoal(this));
        this.goalSelector.addGoal(0, new EscapeWallGoal(this));
        this.registerVanillaCombatReplacementGoals();
        this.goalSelector.addGoal(1, new EscapeHoleWithBlockGoal(this, digDownForStoneGoal));
        this.goalSelector.addGoal(1, new DescendHighColumnGoal(this, terraformBuildSiteGoal, digDownForStoneGoal));
        this.goalSelector.addGoal(1, new CallForHelpGoal(this));
        this.goalSelector.addGoal(1, this.gated(new CautiousAvoidThreatGoal(this), PlayerNpcInterest.CAUTIOUS));
        this.addWorkGoal(2, this.gated(new SleepAtHomeGoal(this), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(2, this.gated(new ScaredHideGoal(this), PlayerNpcInterest.CAUTIOUS));
        // Personal maintenance must yield to priority-2 emergency bucket/projectile utilities.
        this.goalSelector.addGoal(3, new ThrowTrashItemsGoal(this));
        this.addWorkGoal(3, new PickupNearbyItemGoal(this, 1.0D));
        // Must outrank Epic Fight's priority-1 chasing goal so a disarmed NPC can
        // break pursuit long enough to recover and equip a nearby weapon.
        this.goalSelector.addGoal(0, new RecoverWeaponInCombatGoal(this, 1.2D, 10.0D));
        this.goalSelector.addGoal(3, this.gated(new RareSneakGoal(this), PlayerNpcInterest.CAUTIOUS));
        this.addWorkGoal(4, this.gated(new ReturnHomeGoal(this, 1.0D), PlayerNpcInterest.BUILDING));
        this.addWorkGoal(5, this.gated(terraformBuildSiteGoal, PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(6, new PlayerNpcMeleeAttackGoal(this));
        this.addWorkGoal(5, this.gated(new BuildHouseGoal(this), PlayerNpcInterest.BUILDING));
        this.addWorkGoal(4, new ManageHomeBaseGoal(this, true));
        this.addWorkGoal(4, new MiningNightCampGoal(this, 1.0D));
        this.addWorkGoal(5, new BurnNearbyItemGoal(this, 1.0D, 10.0D));
        this.addWorkGoal(5, new CookFoodGoal(this));
        this.addWorkGoal(5, this.gated(new FarmSetupGoal(this), PlayerNpcInterest.FARMING));
        this.addWorkGoal(5, this.gated(new FarmCropGoal(this), PlayerNpcInterest.FARMING));
        this.addWorkGoal(5, this.gated(new CraftCropFoodGoal(this), PlayerNpcInterest.FARMING));
        this.addWorkGoal(5, this.gated(new PlayerNpcFishingGoal(this), PlayerNpcInterest.FISHING));
        // Characteristics are opportunistic personality behavior, not daily/routine worker jobs.
        // Keep the same priority and delegate flags, but do not make their availability depend on
        // a StartupWorkGatedGoal resource turn.
        this.goalSelector.addGoal(5, this.gated(new LootNearbyChestGoal(this, 1.0D), PlayerNpcInterest.LOOTING));
        this.goalSelector.addGoal(5, this.gated(new JukeboxDanceGoal(this, 1.0D), PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(5, this.gated(new TrollHitGoal(this), PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(5, this.gated(new IronGolemTrollGoal(this), PlayerNpcInterest.TROLL_HIT));
        this.addWorkGoal(5, new ManageHomeBaseGoal(this));
        this.addWorkGoal(5, new CheckHomeSuppliesGoal(this));
        this.addWorkGoal(5, new CraftBasicGearGoal(this));
        this.goalSelector.addGoal(5, new BreakTargetObstructionGoal(this));
        this.addWorkGoal(5, this.gated(new CraftIronGearGoal(this), PlayerNpcInterest.MINING, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.addWorkGoal(5, this.gated(new CraftShieldGoal(this), PlayerNpcInterest.CAUTIOUS, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.addWorkGoal(5, this.gated(new UtilityCraftingGoal(this), PlayerNpcInterest.EXPLORING, PlayerNpcInterest.FISHING, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.addWorkGoal(5, this.gated(new BoatStockpileGoal(this), PlayerNpcInterest.FISHING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(5, this.gated(new PlantSaplingGoal(this), PlayerNpcInterest.FARMING));
        this.addWorkGoal(5, this.gated(new UseSpyglassGoal(this), PlayerNpcInterest.EXPLORING, PlayerNpcInterest.CAUTIOUS));
        this.addWorkGoal(6, this.gated(gatherLogsGoal, PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(6, this.gated(new GatherStoneGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(6, this.gated(new ExploreCaveOreGoal(this, 1.0D), PlayerNpcInterest.MINING));
        this.addWorkGoal(6, this.gated(digDownForStoneGoal, PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(6, gatherMissingBuildMaterialGoal);
        this.addWorkGoal(7, this.gated(new MiningCaveStrollGoal(this, 1.0D), PlayerNpcInterest.MINING));
        this.addWorkGoal(7, this.gated(new FarmStrollGoal(this, 1.0D), PlayerNpcInterest.FARMING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for logs",
                level -> GatherLogsGoal.hasLogSupplyDemand(this, level)
                        && !TerraformBuildSiteGoal.hasActionablePrepWork(this, level)
                        && !GatherStoneGoal.isStoneSupplyPhaseActive(this, level)
                        && !BuildHouseGoal.shouldYieldSupplyWorkForBuild(this, level)
                        && !FarmCropGoal.shouldExploreForFarmSupplies(this, level)
                        && this.canExploreForLogSupply(level)
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                // GatherLogs has higher priority and remains the authority for local tree work.
                // This signal reads only its retained selected-target state; it does
                // not run the old independent broad proxy that could cancel exploration without a
                // successor. Pending slices leave exploration available until a log is selected.
                level -> BuildHouseGoal.shouldYieldSupplyWorkForBuild(this, level)
                        || gatherLogsGoal.hasNearbyUsableLogTarget(level),
                true,
                true,
                true
        ), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for stone",
                level -> GatherStoneGoal.isStoneSupplyPhaseActive(this, level)
                        && this.getGatherCooldown() <= 0
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> GatherStoneGoal.hasNearbyStoneTarget(this, level),
                false
        ), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING, PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING, PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for a farm area",
                level -> FarmSetupGoal.shouldExploreForFarmArea(this, level),
                level -> PlayerNpcFarmPlan.get(this).isPresent(),
                true,
                false
        ), PlayerNpcInterest.FARMING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for seeds and crops",
                level -> FarmCropGoal.shouldExploreForFarmSupplies(this, level),
                // FarmCropGoal has higher priority and already gets the first opportunity to
                // claim any local grass/crop it can actually reach.  Do not make exploration
                // yield to a separate proximity probe: the probe and the action selector use
                // independent bounded path windows, so they can disagree forever ("nearby"
                // says yes while FarmCropGoal cannot select that candidate), leaving an admitted
                // farmer idle.  Let this lower-priority route start; FarmCropGoal will pre-empt it
                // as soon as roaming brings a forage target into its actionable window.
                level -> false,
                true,
                false
        ), PlayerNpcInterest.FARMING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for water",
                level -> PlayerNpcFishingGoal.shouldExploreForFishingWater(this, level)
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> PlayerNpcFishingGoal.hasNearbyFishingSpot(this, level),
                true,
                true
        ), PlayerNpcInterest.FISHING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "strolling around",
                level -> PlayerNpcFishingGoal.shouldStrollForMissingFishingString(this, level)
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> false,
                false,
                true
        ), PlayerNpcInterest.FISHING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for build materials",
                level -> GatherMissingBuildMaterialGoal.needsMissingBuildMaterial(this, level)
                        && this.getGatherCooldown() <= 0
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                gatherMissingBuildMaterialGoal::hasNearbyActionableGatherTarget
        ), PlayerNpcInterest.BUILDING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "strolling after finishing home",
                level -> this.isDailyJobActive(PlayerNpcInterest.BUILDING)
                        && BuildHouseGoal.isHomeLayoutFinished(this, level)
                        && PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(level, this).isEmpty()
                        && !this.shouldPrioritizeLogGathering()
                        && !this.shouldPrioritizeCobblestoneGathering()
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> false,
                true,
                true
        ), PlayerNpcInterest.BUILDING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring",
                level -> this.isDailyJobActive(PlayerNpcInterest.EXPLORING)
                        && !this.shouldPrioritizeLogGathering()
                        && !this.shouldPrioritizeCobblestoneGathering()
                        && !this.shouldStayHomeForWeather(level),
                level -> false
        ), PlayerNpcInterest.EXPLORING));
        this.addWorkGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "patrolling home for monsters",
                level -> this.hasInterest(PlayerNpcInterest.BUILDING)
                        && this.hasInterest(PlayerNpcInterest.HUNT_MONSTERS)
                        && PlayerNpcHomeUtil.getHome(this).isPresent()
                        && (level.isDarkOutside() || level.isThundering()),
                level -> this.getTarget() != null,
                true,
                false
        ), PlayerNpcInterest.HUNT_MONSTERS));
        // Once visible cleanup has selected an abandoned GatherLogs column, it must finish before
        // priority-6 log gathering can start the next tree route. Higher-priority 1-4 safety,
        // item, and return work still pre-empts it; equal-priority 5 work is not displaced.
        this.addWorkGoal(5, new CleanupTemporaryPillarGoal(this, 1.0D));
        this.goalSelector.addGoal(9, new AiBudgetWaitingStrollGoal(this, 0.55D));
        this.addWorkGoal(4, this.gated(new BeingAtHomeGoal(this, 1.0D), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        ((GroundPathNavigation) this.getNavigation()).setCanFloat(true);
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new RetargetCloserThreatGoal(this));
        // Alert avoidance owns MOVE/LOOK, so it belongs in the movement selector. Registering it
        // in targetSelector let the interrupted ExploreAround goal keep ticking and repeatedly
        // replace the escape route. Besides defeating the alert response, those competing
        // synchronous paths were charged only to Mob.super.tick() and produced the 291 ms
        // avoiding_alert spike seen in the TPS trace.
        this.goalSelector.addGoal(1, new RespondToNpcAlertGoal(this));
        this.goalSelector.addGoal(4, new FollowTeamLeaderGoal(this, 1.05D));
        this.goalSelector.addGoal(8, new TeamUpGoal(this));
        this.targetSelector.addGoal(4, this.gated(new PlayerNpcSmartTargetGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
    }

    private Goal gated(Goal goal, PlayerNpcInterest... interests) {
        return new InterestGatedGoal(this, goal, interests);
    }

    private void addWorkGoal(int priority, Goal goal) {
        this.goalSelector.addGoal(priority, new StartupWorkGatedGoal(this, goal, this.workGoalRegistrationIndex++));
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this).isPresent()
                && (serverLevel.isDarkOutside() || serverLevel.isThundering());
    }

    private boolean canExploreForLogSupply(ServerLevel serverLevel) {
        if (!this.shouldPrioritizeLogGathering() || serverLevel.canSeeSky(this.blockPosition().above())) {
            return true;
        }
        if (this.isDailyJobActive(PlayerNpcInterest.MINING) && !this.hasInterest(PlayerNpcInterest.BUILDING)) {
            return true;
        }
        if (this.isDailyJobActive(PlayerNpcInterest.EXPLORING)) {
            return true;
        }
        // A fisher without a rod can already have the starter string but still needs wood for
        // sticks (and, when no table is available, the crafting table).  Treat that bootstrap
        // supply route like the mining/exploring routes above.  Otherwise any roof or cave mouth
        // makes this predicate false after GatherLogs finishes its bounded local pass, leaving
        // the admitted fisher idle forever instead of roaming to a tree. Farmers need the same
        // bootstrap route for their required wood/tool supply. The priority-1 hole
        // escape goal still pre-empts this lower-priority exploration when the NPC is trapped.
        if (this.isDailyJobActive(PlayerNpcInterest.FISHING)
                || this.isDailyJobActive(PlayerNpcInterest.FARMING)) {
            return true;
        }
        if (this.hasInterest(PlayerNpcInterest.BUILDING) && this.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return true;
        }
        return this.hasNearbyTreeCover(serverLevel);
    }

    private boolean hasNearbyTreeCover(ServerLevel serverLevel) {
        BlockPos feet = this.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-1, -1, -1), feet.offset(1, 3, 1))) {
            if (!serverLevel.isInWorldBounds(pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) {
                return true;
            }
        }
        return false;
    }

    private void registerVanillaCombatReplacementGoals() {
        this.goalSelector.addGoal(0, new DangerousReactionGoal(this));
        this.goalSelector.addGoal(1, new LowHealthFleeGoal(this));
        this.goalSelector.addGoal(1, new EatHealingFoodGoal(this));
        this.goalSelector.addGoal(2, new ShieldGuardGoal(this));
        this.goalSelector.addGoal(2, new UseWaterBucketGoal(this));
        this.goalSelector.addGoal(2, new PlayerNpcProjectileBlockGoal(this));
        this.goalSelector.addGoal(2, new WaterEnderPearlEscapeGoal(this));
        // Epic Fight compatibility is disabled.
        this.goalSelector.addGoal(2, new PlayerNpcRangedBowAttackGoal(this, 1.0D, 20, 18.0F));
        this.goalSelector.addGoal(3, this.gated(new CombatFishingRodGoal(this), PlayerNpcInterest.FISHING));
        this.goalSelector.addGoal(3, new ThrowEnderPearlGoal(this));
        this.goalSelector.addGoal(4, this.gated(new BoatTrapMonsterGoal(this), PlayerNpcInterest.HUNT_MONSTERS));
        this.goalSelector.addGoal(4, this.gated(new UseFlintAndSteelGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS, PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(4, this.gated(new UseLavaBucketGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS, PlayerNpcInterest.TROLL_HIT));
        this.addWorkGoal(8, new FillWaterBucketGoal(this, 1.0D));
    }

    public boolean removeWhenFarAway(double d0) {
        return false;
    }

    public double getMyRidingOffset() {
        return -0.35D;
    }

    public @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSource) {
        return BuiltInRegistries.SOUND_EVENT.getValue(Identifier.fromNamespaceAndPath("minecraft", "entity.generic.hurt"));
    }

    public @NotNull SoundEvent getDeathSound() {
        return BuiltInRegistries.SOUND_EVENT.getValue(Identifier.fromNamespaceAndPath("minecraft", "entity.generic.death"));
    }

    public void jump() {
        this.jumpFromGround();
        Vec3 motion = this.getDeltaMovement();
        Vec3 forward = this.getForward();
        double strength = new Random().nextDouble(0.28, 0.48);
        this.setDeltaMovement(
                motion.x + forward.x * strength,
                Math.max(motion.y, PLAYER_LIKE_JUMP_Y),
                motion.z + forward.z * strength
        );
        this.hurtMarked = true;
    }

    public void shortPillarJump() {
        // Owning goals/helpers enforce their own work or emergency admission. A physical
        // jump must also work for an admitted escape that has no routine worker lease.
        if (this.level().isClientSide() || !this.isAlive() || this.isNoAi()
                || this.isPassenger() || !this.onGround()) return;
        Vec3 v = this.getDeltaMovement();
        double keepH = 0.02D;
        this.setDeltaMovement(v.x * keepH, PLAYER_LIKE_JUMP_Y, v.z * keepH);
        this.hurtMarked = true;
    }

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        // Cautious avoidance owns threats; target goals must not turn them into retaliation.
        super.setTarget(this.hasInterest(PlayerNpcInterest.CAUTIOUS) ? null : target);
    }

    public boolean hurtServer(@NotNull ServerLevel serverLevel, @NotNull DamageSource damageSource, float f) {
        if (this.isTeamAlliedWith(damageSource.getEntity())) {
            return false;
        }

        boolean hurt = super.hurtServer(serverLevel, damageSource, f);
        if (hurt && !this.hasInterest(PlayerNpcInterest.CAUTIOUS)
                && !this.level().isClientSide() && damageSource.getEntity() instanceof LivingEntity attacker
                && attacker.isAlive()
                && attacker != this
                && !this.isAlliedTo(attacker)
                && !attacker.isAlliedTo(this)) {
            this.setTarget(attacker);
            this.lastCombatProgressTick = this.tickCount;
            this.staleTargetTicks = 0;
            this.setCurrentAiState("ai.player_npc.retaliating");
        }
        return hurt;
    }

    @Override
    public boolean isBlocking() {
        return this.shieldGuardCooldown <= 0 && super.isBlocking();
    }

    @Override
    public float applyItemBlocking(ServerLevel level, DamageSource source, float damage) {
        ItemStack blockingWith = this.getItemBlockingWith();
        InteractionHand blockingHand = this.getUsedItemHand();
        float blocked = super.applyItemBlocking(level, source, damage);
        // 26.1.2 only damages blocking items for Player; NPCs need the same component rule.
        if (blocked > 0.0F && blockingWith != null) {
            var blocksAttacks = blockingWith.get(DataComponents.BLOCKS_ATTACKS);
            if (blocksAttacks != null) {
                int itemDamage = blocksAttacks.itemDamage().apply(blocked);
                if (itemDamage > 0) {
                    boolean cachedOffhand = blockingHand == InteractionHand.OFF_HAND
                            && ItemStack.isSameItemSameComponents(this.offWeaponItem, blockingWith);
                    blockingWith.hurtAndBreak(itemDamage, this, blockingHand);
                    if (cachedOffhand) {
                        this.setOffWeaponItem(blockingWith);
                    }
                    if (blockingWith.isEmpty()) {
                        this.setItemInHand(blockingHand, ItemStack.EMPTY);
                        this.stopUsingItem();
                        this.playSound(SoundEvents.SHIELD_BREAK.value(), 0.8F,
                                0.8F + this.getRandom().nextFloat() * 0.4F);
                    }
                }
            }
        }
        return blocked;
    }

    @Override
    protected void blockUsingItem(ServerLevel level, LivingEntity attacker) {
        super.blockUsingItem(level, attacker);
        ItemStack blockingWith = this.getItemBlockingWith();
        var blocksAttacks = blockingWith != null ? blockingWith.get(DataComponents.BLOCKS_ATTACKS) : null;
        float seconds = attacker.getSecondsToDisableBlocking();
        if (blocksAttacks != null && seconds > 0.0F && blocksAttacks.disableCooldownScale() > 0.0F) {
            this.setShieldGuardCooldown(Math.max(this.getShieldGuardCooldown(),
                    Math.round(seconds * blocksAttacks.disableCooldownScale() * 20.0F)));
            blocksAttacks.disable(level, this, seconds, blockingWith);
            this.level().broadcastEntityEvent(this, (byte) 30);
        }
    }

    @Override
    public boolean doHurtTarget(@NotNull ServerLevel serverLevel, @NotNull Entity target) {
        if (this.isHealing() || this.hasInterest(PlayerNpcInterest.CAUTIOUS)) {
            return false;
        }
        if (this.isTeamAlliedWith(target)) {
            this.setTarget(null);
            return false;
        }
        this.setCurrentAiState("ai.player_npc.melee_attacking");
        this.triggerMainHandAttackAnimation();
        this.triggerBetterCombatAttackAnimation();
        boolean hurtTarget = super.doHurtTarget(serverLevel, target);
        if (hurtTarget) {
            this.lastCombatProgressTick = this.tickCount;
            this.staleTargetTicks = 0;
            this.hurtMainHandItem(1);
            if (target instanceof Animal animal && (animal.isDeadOrDying() || !animal.isAlive())) {
                this.prioritizeAnimalLoot(animal.blockPosition());
                this.setTarget(null);
                this.getNavigation().stop();
            }
        }
        return hurtTarget;
    }

    public boolean doHurtTarget(@NotNull Entity target) {
        return this.level() instanceof ServerLevel serverLevel && this.doHurtTarget(serverLevel, target);
    }

    public void hurtMainHandItem(int amount) {
        this.hurtItemInHand(InteractionHand.MAIN_HAND, amount);
    }

    public void hurtItemInHand(InteractionHand hand, int amount) {
        if (amount <= 0) {
            return;
        }

        ItemStack stack = this.getItemInHand(hand);
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }

        stack.hurtAndBreak(amount, this, hand == InteractionHand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
    }

    public boolean hurtHeldOrInventoryItem(Predicate<ItemStack> matcher, int amount) {
        if (amount <= 0) {
            return false;
        }

        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            this.hurtMainHandItem(amount);
            return true;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack) || !stack.isDamageableItem()) {
                continue;
            }

            if (this.level() instanceof ServerLevel serverLevel) {
                stack.hurtAndBreak(amount, serverLevel, this, item -> {});
            }
            if (stack.isEmpty()) {
                this.inventory.setItem(i, ItemStack.EMPTY);
            }
            this.inventory.setChanged();
            return true;
        }

        return false;
    }

    public void showBlockBreakProgress(BlockPos pos, int breakTicks, int requiredBreakTicks) {
        if (!(this.level() instanceof ServerLevel serverLevel) || pos == null) {
            return;
        }

        int progress = requiredBreakTicks <= 1
                ? 9
                : (int) ((breakTicks * 10.0F) / requiredBreakTicks);
        progress = Math.max(0, Math.min(9, progress));
        if (progress == this.lastSentBlockBreakProgressStage
                && pos.equals(this.lastSentBlockBreakProgressPos)) {
            return;
        }
        serverLevel.destroyBlockProgress(this.getId(), pos, progress);
        this.lastSentBlockBreakProgressPos = pos.immutable();
        this.lastSentBlockBreakProgressStage = progress;
    }

    public void clearBlockBreakProgress(BlockPos pos) {
        if (this.level() instanceof ServerLevel serverLevel && pos != null) {
            serverLevel.destroyBlockProgress(this.getId(), pos, -1);
            // Minecraft indexes crack progress by breaker entity id, so any -1 removes the
            // current entry even if a recovery branch supplied an older position.
            this.lastSentBlockBreakProgressPos = null;
            this.lastSentBlockBreakProgressStage = -1;
        }
    }

    public void markCombatProgress() {
        this.lastCombatProgressTick = this.tickCount;
        this.staleTargetTicks = 0;
    }

    public void equipBetterGearFromInventory() {
        boolean changed = false;
        changed |= this.equipBestArmorFromInventory();
        if (!this.isMainHandReservedForAi()) {
            changed |= this.equipBestMainHandFromInventory();
        }
        if (changed) {
            this.inventory.setChanged();
        }
    }

    public boolean isClearingCombatObstruction() {
        return this.goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
                .anyMatch(wrapped -> wrapped.getGoal() instanceof BreakTargetObstructionGoal);
    }

    /**
     * True while a scheduled resource-gathering job owns this NPC. These jobs deliberately enter
     * holes, mines, tree canopies, and build-material sites, so generic trap recovery must yield
     * until their normal stop/cleanup path releases movement.
     */
    public boolean isGatheringJobRunning() {
        return this.goalSelector.getAvailableGoals().stream()
                .filter(WrappedGoal::isRunning)
                .map(WrappedGoal::getGoal)
                .map(PlayerNpcEntity::unwrapGoal)
                .anyMatch(GatheringGoal.class::isInstance);
    }

    private static Goal unwrapGoal(Goal goal) {
        if (goal instanceof StartupWorkGatedGoal startupWorkGatedGoal) {
            return unwrapGoal(startupWorkGatedGoal.getDelegateGoal());
        }
        if (goal instanceof InterestGatedGoal interestGatedGoal) {
            return unwrapGoal(interestGatedGoal.getDelegateGoal());
        }
        return goal;
    }

    public SocialNoticeAi getSocialNoticeAi() {
        return this.socialNoticeAi;
    }

    public boolean isSocialNoticePausingWork() {
        return this.entityData.get(SOCIAL_NOTICE_PAUSES_WORK);
    }

    public void setSocialNoticePausesWork(boolean paused) {
        this.entityData.set(SOCIAL_NOTICE_PAUSES_WORK, paused);
        if (paused) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, 0);
            this.entityData.set(BETTER_COMBAT_ATTACK_ANIMATION_TICKS, 0);
        }
    }

    public boolean canSociallyNotice() {
        if (!this.isAlive() || this.isNoAi() || this.getTarget() != null || this.isAggressive()
                || this.isHealing() || this.isUsingItem() || this.isSleeping() || this.isPassenger()
                || this.isTeamUpRequestPending() || this.hurtTime > 0 || !this.onGround()
                || this.getUpwardEscapeTarget() != null || this.getHoleEscapeCooldown() > 0
                || this.isInWater() || this.isInLava()) {
            return false;
        }
        for (WrappedGoal wrapped : this.goalSelector.getAvailableGoals()) {
            if (!wrapped.isRunning()) {
                continue;
            }
            Goal outer = wrapped.getGoal();
            Goal goal = unwrapGoal(outer);
            if (!(outer instanceof StartupWorkGatedGoal)) {
                // Combat, invitations, fleeing, pillar safety and other personality actions win.
                if (!(goal instanceof OpenDoorGoal) && !(goal instanceof NoticeNearbyPlayerGoal)
                        && !(goal instanceof AiBudgetWaitingStrollGoal)) {
                    return false;
                }
            } else if (goal instanceof SleepAtHomeGoal || goal instanceof CleanupTemporaryPillarGoal) {
                return false;
            }
        }
        return true;
    }

    public boolean isMainHandReservedForAi() {
        String state = this.getCurrentAiState();
        return this.temporaryBowEquipped
                || this.isHealing()
                || this.goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning).anyMatch(wrapped -> {
                    Goal goal = wrapped.getGoal();
                    if (goal instanceof InterestGatedGoal gatedGoal) {
                        goal = gatedGoal.getDelegateGoal();
                    }
                    return goal instanceof EscapeHoleWithBlockGoal
                            || goal instanceof BreakTargetObstructionGoal
                            || goal instanceof UseFlintAndSteelGoal
                            || goal instanceof LowHealthFleeGoal;
                })
                || "ai.player_npc.gathering_materials".equals(state)
                || "ai.player_npc.gathering_logs".equals(state)
                || "ai.player_npc.gathering_stone".equals(state)
                || "ai.player_npc.prospecting_ore".equals(state)
                || "ai.player_npc.digging_down_for_stone".equals(state)
                || "ai.player_npc.exploring_cave".equals(state)
                || "ai.player_npc.escaping_hole".equals(state)
                || "ai.player_npc.descending_column".equals(state)
                || "ai.player_npc.pillaring_up".equals(state)
                || "ai.player_npc.breaking_target_obstruction".equals(state)
                || "ai.player_npc.managing_home".equals(state)
                || "ai.player_npc.checking_home_supplies".equals(state)
                || "ai.player_npc.cooking".equals(state)
                || "ai.player_npc.building_house".equals(state)
                || "ai.player_npc.terraforming_build_site".equals(state)
                || "ai.player_npc.farming".equals(state)
                || "ai.player_npc.fishing".equals(state)
                || "ai.player_npc.planting_sapling".equals(state);
    }

    private boolean equipBestArmorFromInventory() {
        boolean changed = false;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            EquipmentSlot slot = this.getEquipmentSlotForItem(stack);
            if (!slot.isArmor()) {
                continue;
            }

            ItemStack equipped = this.getItemBySlot(slot);
            if (this.gearScore(stack) <= this.gearScore(equipped) + 0.05D) {
                continue;
            }

            this.equipOneFromInventorySlot(i, slot);
            changed = true;
        }
        return changed;
    }

    private boolean equipBestMainHandFromInventory() {
        int bestSlot = -1;
        double bestScore = this.isMainHandGear(this.getMainHandItem())
                ? this.gearScore(this.getMainHandItem())
                : 0.0D;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!this.isMainHandGear(stack)) {
                continue;
            }

            double score = this.gearScore(stack);
            if (score > bestScore + 0.05D) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) {
            return false;
        }

        this.equipOneFromInventorySlot(bestSlot, EquipmentSlot.MAINHAND);
        return true;
    }

    private void equipOneFromInventorySlot(int inventorySlot, EquipmentSlot equipmentSlot) {
        ItemStack source = this.inventory.getItem(inventorySlot);
        if (source.isEmpty()) {
            return;
        }

        ItemStack replacement = source.copy();
        replacement.setCount(1);
        source.shrink(1);
        if (source.isEmpty()) {
            this.inventory.setItem(inventorySlot, ItemStack.EMPTY);
        }

        ItemStack previous = this.getItemBySlot(equipmentSlot).copy();
        this.setItemSlot(equipmentSlot, replacement);
        if (!previous.isEmpty() && !InventoryUtils.addItem(this.inventory, previous)) {
            this.spawnAtLocation(previous);
        }
    }

    private boolean isMainHandGear(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.SWORDS)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.has(DataComponents.TOOL));
    }

    private boolean isCombatMainHandGear(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.SWORDS)
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem);
    }

    private boolean isRangedMainHandGear(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof ProjectileWeaponItem);
    }

    private double gearScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }

        double score = 0.0D;
        if (SmartNpcItemUtil.isArmor(stack)) {
            score += SmartNpcItemUtil.armorScore(stack);
        } else if (stack.is(ItemTags.SWORDS) || stack.getItem() instanceof AxeItem) {
            score += SmartNpcItemUtil.attackDamage(stack);
        } else if (stack.getItem() instanceof TridentItem) {
            score += 9.0D;
        } else if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof ProjectileWeaponItem) {
            score += 6.0D;
        } else if (stack.has(DataComponents.TOOL)) {
            score += 3.0D + stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) * 0.1D;
        }

        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        if (stack.isDamageableItem()) {
            score += ((double) stack.getMaxDamage() - stack.getDamageValue()) / Math.max(1, stack.getMaxDamage());
        }
        return score;
    }

    public void triggerMainHandAttackAnimation() {
        this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, MAIN_HAND_ATTACK_ANIMATION_DURATION);
    }

    private void triggerBetterCombatAttackAnimation() {
        if (!BetterCombatCompat.isLoaded()) {
            return;
        }

        int sequence = this.entityData.get(BETTER_COMBAT_ATTACK_SEQUENCE);
        this.entityData.set(BETTER_COMBAT_ATTACK_SEQUENCE, sequence == Integer.MAX_VALUE ? 1 : sequence + 1);
        this.entityData.set(BETTER_COMBAT_ATTACK_ANIMATION_TICKS, BETTER_COMBAT_ATTACK_ANIMATION_DURATION);
    }

    public void triggerMainHandUseAnimation() {
        if (this.entityData.get(MAIN_HAND_ATTACK_ANIMATION_TICKS) <= 0) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, MAIN_HAND_USE_ANIMATION_DURATION);
        }
        this.swing(InteractionHand.MAIN_HAND, true);
    }

    public int getMainHandAttackAnimationTicks() {
        return this.entityData.get(MAIN_HAND_ATTACK_ANIMATION_TICKS);
    }

    public int getMainHandAttackAnimationDuration() {
        return MAIN_HAND_ATTACK_ANIMATION_DURATION;
    }

    public int getBetterCombatAttackAnimationTicks() {
        return this.entityData.get(BETTER_COMBAT_ATTACK_ANIMATION_TICKS);
    }

    public int getBetterCombatAttackAnimationDuration() {
        return BETTER_COMBAT_ATTACK_ANIMATION_DURATION;
    }

    public int getBetterCombatAttackSequence() {
        return this.entityData.get(BETTER_COMBAT_ATTACK_SEQUENCE);
    }

    public String getCurrentAiState() {
        return this.entityData.get(AI_STATE);
    }

    public void setCurrentAiState(String state) {
        String normalizedState = state == null || state.isBlank() ? AI_IDLE : state;
        this.entityData.set(AI_STATE, normalizedState);
        if (AI_IDLE.equals(normalizedState)) {
            this.setCurrentAiDetail("");
        } else {
            this.clearIdleTraceDetail();
        }
    }

    public String getCurrentAiDetail() {
        return this.entityData.get(AI_DETAIL);
    }

    public void setCurrentAiDetail(String detail) {
        this.entityData.set(AI_DETAIL, detail == null ? "" : detail);
    }

    public void setIdleTraceDetail(String detail, int ticks) {
        if (detail == null || detail.isBlank() || ticks <= 0) {
            return;
        }
        this.idleTraceDetail = detail;
        this.idleTraceDetailTicks = normalizeCooldown(ticks);
    }

    public String getIdleTraceDetail() {
        return this.idleTraceDetailTicks > 0 ? this.idleTraceDetail : "";
    }

    public void clearIdleTraceDetail() {
        this.idleTraceDetail = "";
        this.idleTraceDetailTicks = 0;
    }

    public boolean isDancing() {
        return this.entityData.get(DANCING);
    }

    public void setDancing(boolean dancing) {
        this.entityData.set(DANCING, dancing);
    }

    public boolean isEpicFightDigging() {
        return this.entityData.get(EPIC_FIGHT_DIGGING);
    }

    public void setEpicFightDigging(boolean digging) {
        this.entityData.set(EPIC_FIGHT_DIGGING, digging);
    }

    public boolean isDisplayNameHiddenBySneakingAi() {
        return this.entityData.get(SNEAKING_AI_HIDES_DISPLAY_NAME);
    }

    public void setDisplayNameHiddenBySneakingAi(boolean hidden) {
        this.entityData.set(SNEAKING_AI_HIDES_DISPLAY_NAME, hidden);
    }

    public boolean canFireProjectileWeapon(@NotNull ProjectileWeaponItem item) {
        return item instanceof BowItem;
    }

    public boolean canFireProjectileWeapon(@NotNull Item item) {
        if (item instanceof ProjectileWeaponItem weaponItem) {
            return this.canFireProjectileWeapon(weaponItem);
        }
        return false;
    }

    @Override
    public void performRangedAttack(@NotNull LivingEntity pTarget, float pVelocity) {
        if (this.isHealing()) {
            return;
        }
        if (this.isTeamAlliedWith(pTarget)) {
            this.setTarget(null);
            return;
        }
        if (!BowFunction.hasClearShot(this, pTarget)) {
            return;
        }

        InteractionHand weaponHand = ProjectileUtil.getWeaponHoldingHand(this, this::canFireProjectileWeapon);
        ItemStack weaponStack = this.getItemInHand(weaponHand);
        ItemStack itemstack = InventoryUtils.consumeArrowAmmo(this).orElse(ItemStack.EMPTY);
        if (itemstack.isEmpty()) {
            return;
        }

        AbstractArrow mobArrow = ProjectileUtil.getMobArrow(this, itemstack, pVelocity, weaponStack);
        if (weaponStack.getItem() instanceof BowItem bowItem) {
            mobArrow = bowItem.customArrow(mobArrow, itemstack, weaponStack);
        }

        double x = pTarget.getX() - this.getX();
        double y = pTarget.getY(0.3333333333333333) - mobArrow.getY();
        double z = pTarget.getZ() - this.getZ();
        double d3 = Math.sqrt(x * x + z * z);
        mobArrow.setOwner(this);
        mobArrow.shoot(x, y + d3 * (double)0.2F, z, 1.6F, (float)(14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(mobArrow);
        this.hurtItemInHand(weaponHand, 1);
    }

    @Override
    public void die(@NotNull DamageSource damageSource) {
        Component deathMessage = this.getCombatTracker().getDeathMessage();
        super.die(damageSource);
        PlayerNpcTeamUpManager.onNpcDeath(this);
        this.handlePlayerNpcDeathChat(damageSource, deathMessage);

        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.getPersistentData().getBooleanOr("die_by_possess", false)) {
                this.remove(Entity.RemovalReason.KILLED);
            }
        }
    }

    private void handlePlayerNpcDeathChat(DamageSource damageSource, Component deathMessage) {
        Entity killer = damageSource.getEntity();
        if (!ChatUtil.shouldReportPlayerNpcDeath(this)) {
            return;
        }

        ChatUtil.reportDeath(this, deathMessage, killer);

        if (ChatUtil.isPlayerLikeThreat(killer) && killer instanceof LivingEntity livingKiller) {
            PlayerNpcAlertManager.raiseDeathAlert(this, livingKiller);
        }
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private void pickupNearbyItems() {
        if (!isAlive() || isRemoved() || this.isDeadOrDying() || this.isItemPickupSuppressed()) return;

        AABB box = this.getBoundingBox().inflate(
                ITEM_PICKUP_REACH.getX(),
                ITEM_PICKUP_REACH.getY(),
                ITEM_PICKUP_REACH.getZ());
        List<ItemEntity> items = level().getEntitiesOfClass(
                ItemEntity.class,
                box,
                e -> !e.isRemoved()
                        && !e.hasPickUpDelay()
                        && shouldCustomInventoryPickup(e.getItem())
        );

        boolean pickedUpAny = false;
        for (ItemEntity itemEntity : items) {
            pickedUpAny |= this.tryPickupItemEntity(itemEntity, false);
        }
        if (pickedUpAny) {
            this.equipBetterGearFromInventory();
        }
    }

    private void pickupNearbyExperienceOrbs() {
        if (!isAlive() || isRemoved() || this.isDeadOrDying()) {
            return;
        }

        AABB box = this.getBoundingBox().inflate(EXPERIENCE_PICKUP_RADIUS);
        List<ExperienceOrb> orbs = this.level().getEntitiesOfClass(
                ExperienceOrb.class,
                box,
                orb -> orb.isAlive() && !orb.isRemoved() && orb.getValue() > 0
        );
        if (orbs.isEmpty()) {
            return;
        }

        long pickedUp = 0L;
        for (ExperienceOrb orb : orbs) {
            pickedUp += orb.getValue();
            orb.discard();
        }
        this.awardStoredExperience(pickedUp > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) pickedUp);
        this.playExperiencePickupSound();
    }

    public boolean tryPickupItemEntity(ItemEntity itemEntity) {
        return this.tryPickupItemEntity(itemEntity, true);
    }

    private boolean tryPickupItemEntity(ItemEntity itemEntity, boolean equipAfterPickup) {
        if (this.level().isClientSide()
                || this.isItemPickupSuppressed()
                || itemEntity == null
                || !itemEntity.isAlive()
                || itemEntity.isRemoved()
                || itemEntity.hasPickUpDelay()
                || itemEntity.getItem().isEmpty()
                || !this.isWithinPlayerLikeItemPickupReach(itemEntity)
                || !shouldCustomInventoryPickup(itemEntity.getItem())) {
            return false;
        }

        boolean pickedUp = tryPickup(itemEntity);
        if (pickedUp && equipAfterPickup) {
            this.equipBetterGearFromInventory();
        }
        return pickedUp;
    }

    public boolean isWithinPlayerLikeItemPickupReach(@Nullable ItemEntity itemEntity) {
        return itemEntity != null
                && itemEntity.level() == this.level()
                && this.getBoundingBox()
                .inflate(ITEM_PICKUP_REACH.getX(), ITEM_PICKUP_REACH.getY(), ITEM_PICKUP_REACH.getZ())
                .intersects(itemEntity.getBoundingBox());
    }

    private boolean tryPickup(ItemEntity itemEntity) {
        ItemStack remaining = itemEntity.getItem().copy();
        int originalCount = remaining.getCount();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);

            if (!slotStack.isEmpty()
                    && ItemStack.isSameItemSameComponents(slotStack, remaining) &&
                    slotStack.getCount() < slotStack.getMaxStackSize()) {
                int transferable = Math.min(
                        remaining.getCount(),
                        slotStack.getMaxStackSize() - slotStack.getCount()
                );
                slotStack.grow(transferable);
                remaining.shrink(transferable);
            }
        }

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);
            if (!slotStack.isEmpty()) {
                continue;
            }

            ItemStack inserted = remaining.copy();
            inserted.setCount(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            this.inventory.setItem(i, inserted);
            remaining.shrink(inserted.getCount());
        }

        if (remaining.getCount() == originalCount) {
            return false;
        }

        this.inventory.setChanged();
        int pickedUpCount = originalCount - remaining.getCount();
        this.onItemPickup(itemEntity);
        this.take(itemEntity, pickedUpCount);

        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }
        return true;
    }

    public void playExperiencePickupSound() {
        if (this.level().isClientSide()) {
            return;
        }

        this.level().playSound(
                null,
                this.blockPosition(),
                SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                0.5F * ((this.getRandom().nextFloat() - this.getRandom().nextFloat()) * 0.7F + 1.8F)
        );
    }

    public void playInventoryPickupSound() {
        if (this.level().isClientSide()) {
            return;
        }
        this.level().playSound(
                null,
                this.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                1.0F
        );
    }

    @Override
    public void tick() {
        if (this.level() instanceof ServerLevel && this.pendingSpawnInitialization) {
            this.completeSpawnInitialization();
            if (this.isRemoved()) {
                return;
            }
            // EntityJoinLevelEvent may observe a worker-created NPC before it has an identity.
            // Refresh force-ticket tracking after server-thread initialization completes.
            PlayerNpcForceTickManager.track(this);
        }
        if (this.level() instanceof ServerLevel && !this.teamMembershipValidated) {
            PlayerNpcTeamUpManager.validateLoadedMembership(this);
            this.teamMembershipValidated = true;
        }
        boolean measurePerformance = this.level() instanceof ServerLevel
                && PlayerNpcPerformanceMonitor.shouldMeasureNpcEntityTick();
        long performanceStartNanos = measurePerformance ? System.nanoTime() : 0L;
        if (this.level() instanceof ServerLevel) {
            this.socialNoticeAi.validateActive();
        }
        super.tick();
        long performanceAfterSuperNanos = measurePerformance ? System.nanoTime() : 0L;

        int mainHandAttackAnimationTicks = this.getMainHandAttackAnimationTicks();
        if (mainHandAttackAnimationTicks > 0) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, mainHandAttackAnimationTicks - 1);
        }

        int betterCombatAttackAnimationTicks = this.getBetterCombatAttackAnimationTicks();
        if (betterCombatAttackAnimationTicks > 0) {
            this.entityData.set(BETTER_COMBAT_ATTACK_ANIMATION_TICKS, betterCombatAttackAnimationTicks - 1);
        }

        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        this.waterTravelAi.tick(serverLevel);
        this.tickDailyJobSelection(serverLevel);
        this.tickDailySupplyGoalReroll(serverLevel);
        this.tickFishingStarterStringMigration();
        this.tickAiCooldowns();
        this.clearStaleHealingState();
        this.tickStartupIdleWake();
        this.cleanupStaleCombatState();
        this.tickTasklessActivityWatchdog();
        if (!this.socialNoticeAi.isActive()) {
            this.tickExplorationClimbFallback(serverLevel);
            this.tickHighIdleDescentRecovery(serverLevel);
            this.tickIdleResourceStuckFallback(serverLevel);
        }
        this.socialNoticeAi.afterAiTick();

        // Contact pickup must not depend on routine-worker ownership or a global admission slot:
        // NPCs with the same cadence phase could otherwise starve behind the same earlier entity
        // forever. The small local AABB scan remains staggered per NPC.
        if (Math.floorMod(this.tickCount + this.getId(), 10) == 0) {
            this.pickupNearbyExperienceOrbs();
        }
        if (Math.floorMod(this.tickCount + this.getId(), 4) == 0 && !this.isInventoryFull()) {
            this.pickupNearbyItems();
        }
        if (measurePerformance) {
            PlayerNpcPerformanceMonitor.recordNpcEntityTick(
                    this,
                    Math.max(0L, System.nanoTime() - performanceStartNanos),
                    Math.max(0L, performanceAfterSuperNanos - performanceStartNanos)
            );
        }
    }


    @Override
    protected Vec3i getPickupReach() {
        // Include each adjacent block horizontally and vertically, forming the
        // requested 3x3x3 pickup neighborhood around the NPC.
        return ITEM_PICKUP_REACH;
    }

    public WaterTravelAi getWaterTravelAi() {
        return this.waterTravelAi;
    }

    public ItemApproachAi getItemApproachAi() {
        return this.itemApproachAi;
    }

    @Override
    public void updateSwimming() {
        // Goals control swimming independently of sprinting; clients receive the shared flag.
        if (!this.level().isClientSide() && this.waterTravelAi != null) {
            this.setSwimming(this.waterTravelAi.isSwimming() && this.isInWater() && !this.isPassenger());
        }
    }

    @Override
    protected EntityDimensions getDefaultDimensions(Pose pose) {
        return pose == Pose.SWIMMING ? EntityDimensions.scalable(0.6F, 0.6F).withEyeHeight(0.4F) : super.getDefaultDimensions(pose);
    }

    private void tickAiCooldowns() {
        this.gapCooldown = tickCooldown(this.gapCooldown);
        this.bucketCooldown = tickCooldown(this.bucketCooldown);
        this.flintAndSteelCooldown = tickCooldown(this.flintAndSteelCooldown);
        this.enderPearlCooldown = tickCooldown(this.enderPearlCooldown);
        this.swapToBowCooldown = tickCooldown(this.swapToBowCooldown);
        this.helpAlertCooldown = tickCooldown(this.helpAlertCooldown);
        this.holeEscapeCooldown = tickCooldown(this.holeEscapeCooldown);
        this.rareSneakCooldown = tickCooldown(this.rareSneakCooldown);
        this.scaredHideCooldown = tickCooldown(this.scaredHideCooldown);
        this.buildHouseCooldown = tickCooldown(this.buildHouseCooldown);
        this.cookFoodCooldown = tickCooldown(this.cookFoodCooldown);
        this.craftGearCooldown = tickCooldown(this.craftGearCooldown);
        this.farmCooldown = tickCooldown(this.farmCooldown);
        this.gatherCooldown = tickCooldown(this.gatherCooldown);
        this.stoneAccessClearCooldown = tickCooldown(this.stoneAccessClearCooldown);
        this.biomeExploreCooldown = tickCooldown(this.biomeExploreCooldown);
        this.huntSheepCooldown = tickCooldown(this.huntSheepCooldown);
        this.ironGolemTrollCooldown = tickCooldown(this.ironGolemTrollCooldown);
        this.lootChestCooldown = tickCooldown(this.lootChestCooldown);
        this.manageHomeCooldown = tickCooldown(this.manageHomeCooldown);
        this.fishingCooldown = tickCooldown(this.fishingCooldown);
        this.returnHomeCooldown = tickCooldown(this.returnHomeCooldown);
        this.explorationReturnHomeRequestTicks = tickCooldown(this.explorationReturnHomeRequestTicks);
        this.sleepCooldown = tickCooldown(this.sleepCooldown);
        this.craftCooldown = tickCooldown(this.craftCooldown);
        this.oreMiningCooldown = tickCooldown(this.oreMiningCooldown);
        this.ironGearCooldown = tickCooldown(this.ironGearCooldown);
        this.spyglassCooldown = tickCooldown(this.spyglassCooldown);
        this.saplingPlantCooldown = tickCooldown(this.saplingPlantCooldown);
        this.animalLootPriorityTicks = tickCooldown(this.animalLootPriorityTicks);
        if (this.animalLootPriorityTicks <= 0) {
            this.animalLootPriorityPos = null;
        }
        this.boatStockCooldown = tickCooldown(this.boatStockCooldown);
        this.boatTrapCooldown = tickCooldown(this.boatTrapCooldown);
        this.jukeboxDanceCooldown = tickCooldown(this.jukeboxDanceCooldown);
        this.trollHitCooldown = tickCooldown(this.trollHitCooldown);
        this.combatFishingCooldown = tickCooldown(this.combatFishingCooldown);
        this.shieldCraftCooldown = tickCooldown(this.shieldCraftCooldown);
        this.shieldGuardCooldown = tickCooldown(this.shieldGuardCooldown);
        this.itemPickupSuppressionTicks = tickCooldown(this.itemPickupSuppressionTicks);
        this.placeBlockParryCooldown = tickCooldown(this.placeBlockParryCooldown);
        this.stunEscapeCooldown = tickCooldown(this.stunEscapeCooldown);
        this.playingIdleCooldown = tickCooldown(this.playingIdleCooldown);
        this.idleTraceDetailTicks = tickCooldown(this.idleTraceDetailTicks);
        if (this.idleTraceDetailTicks <= 0) {
            this.idleTraceDetail = "";
        }
        if (this.upwardEscapeRequestTicks > 0
                && (this.upwardEscapeTarget == null
                || PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this)
                || this.teamFollowUpwardEscape)) {
            this.upwardEscapeRequestTicks = tickCooldown(this.upwardEscapeRequestTicks);
        }
        BlockPos feet = this.blockPosition();
        boolean wetForLandEscape = this.isInWater()
                || this.level().getFluidState(feet).is(FluidTags.WATER)
                || this.level().getFluidState(feet.above()).is(FluidTags.WATER)
                || !this.onGround() && this.level().getFluidState(feet.below()).is(FluidTags.WATER);
        if (wetForLandEscape && this.getUpwardEscapeTarget() != null) {
            // A land pillar request is invalid once its owner has fallen into water. Keeping it
            // lets EscapeHoleWithBlockGoal preempt destination-aware swimming after FloatGoal
            // starts, so discard it and let the owning travel goal cross the water instead.
            this.clearUpwardEscapeTarget();
        }
        if (this.upwardEscapeRequestTicks <= 0) {
            boolean hasExplorationClimbFallbackDetail = this.hasExplorationClimbFallbackDetail();
            if (this.upwardEscapeTarget != null
                    && AI_IDLE.equals(this.getCurrentAiState())
                    && this.getCurrentAiDetail().startsWith("exploration climb request")) {
                this.setCurrentAiDetail("");
            }
            this.upwardEscapeTarget = null;
            this.upwardEscapeMaxPillarBlocks = 0;
            this.forcedUpwardEscape = false;
            this.explorationUpwardEscape = false;
            this.craftingUpwardEscape = false;
            this.terraformSupportUpwardEscape = false;
            this.teamFollowUpwardEscape = false;
            if (!hasExplorationClimbFallbackDetail) {
                this.resetExplorationClimbFallback();
            }
        }
    }

    private void tickExplorationClimbFallback(ServerLevel serverLevel) {
        if (this.explorationClimbClearBlockAi.isRunning()) {
            if (!this.canRunExplorationClimbFallbackAction()) {
                this.resetExplorationClimbFallback();
                return;
            }
            this.tickExplorationClimbClearBlock(serverLevel);
            return;
        }

        BlockPos activeRequestTarget = this.getUpwardEscapeTarget();
        BlockPos requestedTarget = activeRequestTarget != null && this.explorationUpwardEscape
                ? activeRequestTarget
                : this.parseExplorationClimbRequestDetailTarget(this.getCurrentAiDetail());
        if (requestedTarget == null && this.hasExplorationClimbFallbackDetail()) {
            requestedTarget = this.explorationClimbWatchTarget;
        }
        if (requestedTarget == null) {
            this.resetExplorationClimbFallback();
            return;
        }

        if (!this.canRunExplorationClimbFallbackAction()) {
            this.resetExplorationClimbFallback();
            return;
        }

        String state = this.getCurrentAiState();
        boolean idleForFallback = AI_IDLE.equals(state) || "ai.player_npc.looking_for_work".equals(state);
        if (!idleForFallback || this.hasRunningAiGoals()) {
            this.explorationClimbStuckTicks = 0;
            this.explorationClimbSafeStandTarget = null;
            this.stopExplorationClimbClearBlock();
            return;
        }

        if (this.explorationClimbSafeStandTarget != null) {
            if (this.moveToExplorationClimbSafeStand(serverLevel)) {
                return;
            }
            this.explorationClimbSafeStandTarget = null;
            this.explorationClimbStuckTicks = EXPLORATION_CLIMB_STUCK_TICKS;
        }

        BlockPos feet = this.blockPosition();
        if (this.explorationClimbWatchPos == null
                || !this.explorationClimbWatchPos.equals(feet)
                || this.explorationClimbWatchTarget == null
                || !this.explorationClimbWatchTarget.equals(requestedTarget)) {
            this.explorationClimbWatchPos = feet.immutable();
            this.explorationClimbWatchTarget = requestedTarget.immutable();
            this.explorationClimbStuckTicks = 0;
            return;
        }

        if (!this.getNavigation().isDone() && !this.getNavigation().isStuck()) {
            return;
        }

        if (++this.explorationClimbStuckTicks < EXPLORATION_CLIMB_STUCK_TICKS) {
            return;
        }

        Optional<BlockPos> safeStand = this.findExplorationClimbSafeStand(serverLevel, requestedTarget);
        if (safeStand.isEmpty()) {
            if (this.startExplorationClimbClearBlock(serverLevel, requestedTarget)) {
                this.explorationClimbStuckTicks = 0;
                return;
            }
            if (activeRequestTarget != null) {
                this.clearUpwardEscapeTarget();
            }
            this.setCurrentAiDetail("exploration climb blocked; retrying");
            this.wakeUpIdleWork();
            return;
        }

        this.explorationClimbSafeStandTarget = safeStand.get();
        this.getNavigation().stop();
        this.moveToExplorationClimbSafeStand(serverLevel);
    }

    @Nullable
    private BlockPos parseExplorationClimbRequestDetailTarget(String detail) {
        String prefix = "exploration climb request @ ";
        if (detail == null || !detail.startsWith(prefix)) {
            return null;
        }

        String[] parts = detail.substring(prefix.length()).trim().split("\\s+");
        if (parts.length < 3) {
            return null;
        }

        try {
            return new BlockPos(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2])
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean moveToExplorationClimbSafeStand(ServerLevel serverLevel) {
        BlockPos safeStand = this.explorationClimbSafeStandTarget;
        if (safeStand == null) {
            return false;
        }
        if (!PathNavigationAi.canStandAt(serverLevel, safeStand)) {
            return false;
        }
        if (this.distanceToSqr(
                safeStand.getX() + 0.5D,
                safeStand.getY(),
                safeStand.getZ() + 0.5D
        ) <= EXPLORATION_CLIMB_SAFE_STAND_REACHED_SQR) {
            this.clearUpwardEscapeTarget();
            this.setCurrentAiDetail("");
            this.wakeUpIdleWork();
            return true;
        }

        PathNavigationAi navigationAi = new PathNavigationAi(this);
        if (!navigationAi.moveTo(
                serverLevel,
                safeStand,
                1.0D,
                EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_DOWN,
                EXPLORATION_CLIMB_SAFE_STAND_PATH_MULTIPLIER
        )) {
            return false;
        }

        this.setCurrentAiState(AI_IDLE);
        this.setCurrentAiDetail("moving to safe stand after climb stuck @ "
                + safeStand.getX() + " "
                + safeStand.getY() + " "
                + safeStand.getZ());
        return true;
    }

    private Optional<BlockPos> findExplorationClimbSafeStand(ServerLevel serverLevel, BlockPos requestedTarget) {
        BlockPos feet = this.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int minRadiusSqr = EXPLORATION_CLIMB_SAFE_STAND_MIN_RADIUS * EXPLORATION_CLIMB_SAFE_STAND_MIN_RADIUS;
        int maxRadius = EXPLORATION_CLIMB_SAFE_STAND_MAX_RADIUS;
        int maxRadiusSqr = maxRadius * maxRadius;
        for (int dx = -maxRadius; dx <= maxRadius; dx++) {
            for (int dz = -maxRadius; dz <= maxRadius; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr < minRadiusSqr || distanceSqr > maxRadiusSqr) {
                    continue;
                }
                for (int dy = -EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_DOWN;
                     dy <= EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_UP;
                     dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (PathNavigationAi.canStandAt(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> requestedTarget == null ? 0.0D : pos.distSqr(requestedTarget))
                .thenComparingDouble(pos -> pos.distSqr(feet)));
        int preferredCount = Math.min(
                Math.max(1, EXPLORATION_CLIMB_SAFE_STAND_RANDOM_POOL),
                candidates.size()
        );
        if (preferredCount > 1) {
            Collections.rotate(
                    candidates.subList(0, preferredCount),
                    this.getRandom().nextInt(preferredCount)
            );
        }

        PathNavigationAi navigationAi = new PathNavigationAi(this);
        return navigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                EXPLORATION_CLIMB_SAFE_STAND_RANDOM_POOL,
                EXPLORATION_CLIMB_SAFE_STAND_PATH_CHECKS,
                EXPLORATION_CLIMB_SAFE_STAND_VERTICAL_DOWN,
                EXPLORATION_CLIMB_SAFE_STAND_PATH_MULTIPLIER
        );
    }

    private boolean startExplorationClimbClearBlock(ServerLevel serverLevel, BlockPos requestedTarget) {
        List<BlockPos> candidates = this.explorationClimbClearCandidates(requestedTarget);
        if (candidates.isEmpty()) {
            return false;
        }

        int preferredCount = Math.min(EXPLORATION_CLIMB_CLEAR_RANDOM_POOL, candidates.size());
        if (preferredCount > 1) {
            Collections.rotate(candidates.subList(0, preferredCount), this.getRandom().nextInt(preferredCount));
        }

        Predicate<BlockState> targetPredicate = state -> state != null && !state.isAir();
        for (BlockPos candidate : candidates) {
            if (this.explorationClimbClearBlockAi.start(
                    serverLevel,
                    candidate,
                    targetPredicate,
                    "clearing exploration climb fallback",
                    EXPLORATION_CLIMB_CLEAR_TICKS,
                    EXPLORATION_CLIMB_CLEAR_DISTANCE_SQR
            )) {
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> explorationClimbClearCandidates(BlockPos requestedTarget) {
        BlockPos feet = this.blockPosition();
        List<BlockPos> candidates = new ArrayList<>(
                ClearBlockAi.gatherObstructionCandidates(feet, requestedTarget, requestedTarget)
        );
        candidates.add(feet.above());
        candidates.add(feet.above(2));

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            candidates.add(side);
            candidates.add(side.above());
            candidates.add(side.above(2));
            candidates.add(side.below());
        }

        candidates.removeIf(pos -> pos == null || PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos));
        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(feet)));
        return candidates.stream().map(BlockPos::immutable).distinct().collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void tickExplorationClimbClearBlock(ServerLevel serverLevel) {
        ClearBlockAi.TickResult result = this.explorationClimbClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            this.setCurrentAiState(AI_IDLE);
            if (this.getCurrentAiDetail().isBlank()) {
                this.setCurrentAiDetail(this.explorationClimbClearBlockAi.detail());
            }
            return;
        }

        this.stopExplorationClimbClearBlock();
        if (result == ClearBlockAi.TickResult.DONE) {
            this.explorationClimbStuckTicks = 0;
            this.setCurrentAiDetail("exploration climb clear done; retrying");
        } else {
            this.setCurrentAiDetail("exploration climb clear failed; retrying");
        }
        this.clearUpwardEscapeTarget();
        this.wakeUpIdleWork();
    }

    private boolean canRunExplorationClimbFallbackAction() {
        return this.isAlive()
                && !this.isNoAi()
                && !this.isPassenger()
                && !this.isHealing()
                && this.getTarget() == null
                && !this.isSleeping();
    }

    private void stopExplorationClimbClearBlock() {
        this.explorationClimbClearBlockAi.stop();
        this.explorationClimbToolAi.restoreMainHand();
    }

    private void resetExplorationClimbFallback() {
        this.stopExplorationClimbClearBlock();
        this.explorationClimbWatchPos = null;
        this.explorationClimbWatchTarget = null;
        this.explorationClimbSafeStandTarget = null;
        this.explorationClimbStuckTicks = 0;
    }

    /**
     * Gives a genuinely taskless NPC a conservative way off a canopy, roof, or other high
     * perch. Complete navigation routes are preferred. The direct step-off fallback is limited
     * to a three-block proven landing and never accepts a wet or one-cell dead-end stand.
     */
    private void tickHighIdleDescentRecovery(ServerLevel serverLevel) {
        if (!this.canRunHighIdleDescentRecovery()) {
            this.resetHighIdleDescentRecovery();
            return;
        }

        if (this.highIdleDescentNavigationTarget != null) {
            if (!this.getNavigation().isDone() && !this.getNavigation().isStuck()) {
                this.setIdleTraceDetail("idle high-ground descent walking @ "
                        + posText(this.highIdleDescentNavigationTarget), 20 * 3);
                return;
            }
            this.highIdleDescentNavigationTarget = null;
            this.highIdleDescentTicks = HIGH_IDLE_DESCENT_TRIGGER_TICKS;
            this.nextHighIdleDescentAttemptTick = this.tickCount + HIGH_IDLE_DESCENT_RECHECK_TICKS;
        }

        boolean fallbackWasRunning = this.highIdleDescentFallbackAi.isRunning();
        if (this.highIdleDescentFallbackAi.tick(serverLevel, "idle high-ground descent")) {
            this.setIdleTraceDetail(
                    this.highIdleDescentFallbackAi.detail("idle high-ground descent"),
                    20 * 3
            );
            return;
        }
        if (fallbackWasRunning) {
            this.highIdleDescentTicks = HIGH_IDLE_DESCENT_TRIGGER_TICKS;
            this.nextHighIdleDescentAttemptTick = this.tickCount + HIGH_IDLE_DESCENT_RECHECK_TICKS;
        }

        if (this.tickCount < this.nextHighIdleDescentAttemptTick
                || ++this.highIdleDescentTicks < HIGH_IDLE_DESCENT_TRIGGER_TICKS) {
            return;
        }

        BlockPos feet = this.blockPosition();
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this)) {
            this.nextHighIdleDescentAttemptTick = this.tickCount + 1;
            return;
        }

        if (this.highIdleDescentSearchOrigin == null
                && !this.hasHighIdleDescentEvidence(serverLevel, feet)) {
            this.finishHighIdleDescentSearchCooldown();
            return;
        }

        this.ensureHighIdleDescentSearch(feet);
        if (!this.scanHighIdleDescentColumns(serverLevel)) {
            this.nextHighIdleDescentAttemptTick = this.tickCount + 1;
            return;
        }
        if (this.highIdleDescentCandidates.isEmpty()) {
            this.finishHighIdleDescentSearchCooldown();
            return;
        }

        int totalPathChecks = Math.min(
                HIGH_IDLE_DESCENT_PATH_CHECKS,
                this.highIdleDescentCandidates.size()
        );
        int checked = 0;
        while (this.highIdleDescentPathCursor < totalPathChecks
                && checked++ < HIGH_IDLE_DESCENT_PATH_CHECKS_PER_SLICE) {
            BlockPos target = this.highIdleDescentCandidates.get(this.highIdleDescentPathCursor++);
            Path path = PathNavigationAi.createBoundedPath(
                    this,
                    target,
                    HIGH_IDLE_DESCENT_PATH_MULTIPLIER
            );
            if (!this.highIdleDescentNavigationAi.isValidPathTo(target, path)
                    || !this.isSafeHighIdleDescentPath(serverLevel, path, target)) {
                continue;
            }
            if (this.getNavigation().moveTo(path, 0.85D)) {
                this.highIdleDescentNavigationTarget = target.immutable();
                this.highIdleDescentTicks = 0;
                this.clearHighIdleDescentSearch();
                this.setIdleTraceDetail("idle high-ground descent route @ " + posText(target), 20 * 3);
                return;
            }
        }
        if (this.highIdleDescentPathCursor < totalPathChecks) {
            this.nextHighIdleDescentAttemptTick = this.tickCount + 1;
            return;
        }

        BlockPos directionTarget = this.highIdleDescentCandidates.get(0);
        if (this.highIdleDescentFallbackAi.startValidatedNearby(
                serverLevel,
                directionTarget,
                "idle high-ground descent",
                pos -> pos.getY() >= feet.getY()
                        || !this.isSafeHighIdleDescentStand(serverLevel, pos),
                HIGH_IDLE_DESCENT_MAX_SAFE_DROP,
                true
        )) {
            this.highIdleDescentTicks = 0;
            this.clearHighIdleDescentSearch();
            this.setIdleTraceDetail(
                    this.highIdleDescentFallbackAi.detail("idle high-ground descent"),
                    20 * 3
            );
            return;
        }

        this.finishHighIdleDescentSearchCooldown();
        this.setIdleTraceDetail("idle high-ground descent blocked: no safe route @ "
                + posText(feet), 20 * 3);
    }

    private boolean canRunHighIdleDescentRecovery() {
        String state = this.getCurrentAiState();
        return (AI_IDLE.equals(state) || "ai.player_npc.looking_for_work".equals(state))
                && this.tasklessIdleTicks >= TASKLESS_IDLE_WAKE_TICKS
                && !this.hasRunningAiGoals()
                && this.isAlive()
                && !this.isNoAi()
                && !this.isPassenger()
                && !this.isHealing()
                && this.getTarget() == null
                && !this.isSleeping()
                && PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this)
                && this.getUpwardEscapeTarget() == null
                && this.getHoleEscapeCooldown() <= 0
                && !this.explorationClimbClearBlockAi.isRunning()
                && this.explorationClimbSafeStandTarget == null
                && !this.idleResourceFallbackClearBlockAi.isRunning()
                && !this.idleResourcePathStuckFallbackAi.isRunning()
                && this.onGround()
                && !this.isInWater();
    }

    /** Cheap proof that this is actually a raised perch, not ordinary idle ground. */
    private boolean hasHighIdleDescentEvidence(ServerLevel serverLevel, BlockPos feet) {
        BlockPos supportPos = feet.below();
        if (serverLevel.getBlockState(supportPos).is(BlockTags.LEAVES)) {
            return true;
        }
        for (BlockPos offset : HIGH_IDLE_DESCENT_EVIDENCE_OFFSETS) {
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            if (serverLevel.hasChunk(x >> 4, z >> 4)
                    && feet.getY() - serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    x,
                    z
            ) >= HIGH_IDLE_DESCENT_MIN_HEIGHT) {
                return true;
            }
        }
        return false;
    }

    private void ensureHighIdleDescentSearch(BlockPos feet) {
        if (this.highIdleDescentSearchOrigin != null
                && this.highIdleDescentSearchOrigin.equals(feet)) {
            return;
        }
        this.highIdleDescentSearchOrigin = feet.immutable();
        this.highIdleDescentCandidates.clear();
        this.highIdleDescentColumnCursor = 0;
        this.highIdleDescentPathCursor = 0;
    }

    /**
     * Advances only a small loaded-column slice. The old implementation inspected the complete
     * radius before requesting the shared work budget, making every waiting idle NPC pay the
     * heightmap cost and then allowing eight synchronous paths in the same tick.
     */
    private boolean scanHighIdleDescentColumns(ServerLevel serverLevel) {
        BlockPos feet = this.highIdleDescentSearchOrigin;
        if (feet == null) {
            return false;
        }
        int end = Math.min(
                HIGH_IDLE_DESCENT_COLUMN_OFFSETS.size(),
                this.highIdleDescentColumnCursor + HIGH_IDLE_DESCENT_COLUMNS_PER_SLICE
        );
        for (; this.highIdleDescentColumnCursor < end; this.highIdleDescentColumnCursor++) {
            BlockPos offset = HIGH_IDLE_DESCENT_COLUMN_OFFSETS.get(this.highIdleDescentColumnCursor);
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (feet.getY() - surfaceY < HIGH_IDLE_DESCENT_MIN_HEIGHT) {
                continue;
            }
            BlockPos candidate = new BlockPos(x, surfaceY, z);
            if (this.isSafeHighIdleDescentStand(serverLevel, candidate)) {
                this.highIdleDescentCandidates.add(candidate.immutable());
            }
        }
        if (this.highIdleDescentColumnCursor < HIGH_IDLE_DESCENT_COLUMN_OFFSETS.size()) {
            return false;
        }
        if (this.highIdleDescentPathCursor == 0) {
            this.highIdleDescentCandidates.sort(Comparator
                    .comparingInt((BlockPos pos) -> serverLevel.canSeeSky(pos.above()) ? 0 : 1)
                    .thenComparingInt(pos -> feet.getY() - pos.getY())
                    .thenComparingDouble(feet::distSqr)
                    .thenComparingInt(BlockPos::getX)
                    .thenComparingInt(BlockPos::getZ));
        }
        return true;
    }

    private static List<BlockPos> createHighIdleDescentColumnOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        int radiusSqr = HIGH_IDLE_DESCENT_RADIUS * HIGH_IDLE_DESCENT_RADIUS;
        for (int dx = -HIGH_IDLE_DESCENT_RADIUS; dx <= HIGH_IDLE_DESCENT_RADIUS; dx++) {
            for (int dz = -HIGH_IDLE_DESCENT_RADIUS; dz <= HIGH_IDLE_DESCENT_RADIUS; dz++) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                offsets.add(new BlockPos(dx, 0, dz));
            }
        }
        offsets.sort(Comparator
                .comparingInt((BlockPos pos) -> pos.getX() * pos.getX() + pos.getZ() * pos.getZ())
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return List.copyOf(offsets);
    }

    private void finishHighIdleDescentSearchCooldown() {
        this.highIdleDescentTicks = 0;
        this.nextHighIdleDescentAttemptTick = this.tickCount + HIGH_IDLE_DESCENT_RECHECK_TICKS;
        this.clearHighIdleDescentSearch();
    }

    private void clearHighIdleDescentSearch() {
        this.highIdleDescentSearchOrigin = null;
        this.highIdleDescentCandidates.clear();
        this.highIdleDescentColumnCursor = 0;
        this.highIdleDescentPathCursor = 0;
    }

    private boolean isSafeHighIdleDescentPath(ServerLevel serverLevel, Path path, BlockPos target) {
        if (path == null || path.getNodeCount() <= 0 || target.getY() >= this.blockPosition().getY()) {
            return false;
        }
        BlockPos previous = this.blockPosition();
        for (int index = 0; index < path.getNodeCount(); index++) {
            BlockPos node = path.getNode(index).asBlockPos();
            if (previous.getY() - node.getY() > HIGH_IDLE_DESCENT_MAX_SAFE_DROP
                    || !serverLevel.getFluidState(node).isEmpty()
                    || !serverLevel.getFluidState(node.above()).isEmpty()) {
                return false;
            }
            previous = node;
        }
        return this.isSafeHighIdleDescentStand(serverLevel, target);
    }

    private boolean isSafeHighIdleDescentStand(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canStandOnHighIdleSurface(serverLevel, pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos)
                || !serverLevel.getFluidState(pos).isEmpty()
                || !serverLevel.getFluidState(pos.above()).isEmpty()) {
            return false;
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos exit = pos.relative(direction).offset(0, dy, 0);
                if (!PlayerNpcHomeUtil.isInsideBuildFootprint(this, exit)
                        && this.canStandOnHighIdleSurface(serverLevel, exit)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canStandOnHighIdleSurface(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.hasChunkAt(pos)) {
            return false;
        }
        BlockState support = serverLevel.getBlockState(pos.below());
        return serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && (support.isSolidRender() || support.is(BlockTags.LEAVES))
                && !support.getCollisionShape(serverLevel, pos.below()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private void resetHighIdleDescentRecovery() {
        this.highIdleDescentFallbackAi.stop();
        this.highIdleDescentNavigationTarget = null;
        this.highIdleDescentTicks = 0;
        this.nextHighIdleDescentAttemptTick = 0;
        this.clearHighIdleDescentSearch();
    }

    private void tickIdleResourceStuckFallback(ServerLevel serverLevel) {
        // This custom fallback is advanced after Mob.super.tick(), so an ordinary goal may have
        // started since the idle search was queued. Cancel retained heightmap/clear work before
        // ticking it; otherwise it can keep scanning during crafting or another MOVE owner and
        // charge a large spike to the entity's custom-tick bucket.
        if (!this.canRunIdleResourceStuckFallback(serverLevel)) {
            this.resetIdleResourceStuckFallback();
            return;
        }

        if (this.idleResourceFallbackClearBlockAi.isRunning()) {
            this.tickIdleResourceFallbackClearBlock(serverLevel);
            return;
        }

        if (this.idleResourcePathStuckFallbackAi.tick(serverLevel, "idle resource fallback")) {
            this.setIdleTraceDetail(this.idleResourcePathStuckFallbackAi.detail("idle resource fallback"), 20 * 3);
            return;
        }

        BlockPos feet = this.blockPosition();
        // PathNavigation retains its last target after a route completes. Treat it as directional
        // evidence only for a genuinely stuck live route; otherwise a distant stale home/material
        // target makes this idle fallback push the NPC a fraction of a block every retry cycle.
        BlockPos navigationTarget = this.getNavigation().isStuck()
                ? this.getNavigation().getTargetPos()
                : null;
        BlockPos surfaceEscapeTarget = null;
        // A tree canopy also blocks canSeeSky. Only run the expensive radius surface search when
        // the NPC is materially below the terrain surface; a logger standing under leaves is not
        // trapped underground and must not pay for a heightmap sweep during its idle transition.
        int localSurfaceY = serverLevel.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                feet.getX(),
                feet.getZ()
        );
        boolean materiallyBelowSurface = localSurfaceY > feet.getY() + 2;
        if (!serverLevel.canSeeSky(feet.above()) && materiallyBelowSurface) {
            boolean movedBeyondCachedSearch = this.idleResourceSurfaceSearchOrigin == null
                    || this.idleResourceSurfaceSearchOrigin.distSqr(feet) > 4.0D * 4.0D;
            boolean searchDue = movedBeyondCachedSearch
                    || this.tickCount >= this.nextIdleResourceSurfaceSearchTick;
            if (searchDue && !this.idleResourceSurfaceSearchPending) {
                this.beginIdleResourceSurfaceSearch(feet);
            }
            if (this.idleResourceSurfaceSearchPending) {
                if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this)) {
                    this.nextIdleResourceSurfaceSearchTick = this.tickCount
                            + 1
                            + this.getRandom().nextInt(4);
                    return;
                }
                if (!this.advanceIdleResourceSurfaceSearch(serverLevel)) {
                    return;
                }
                this.finishIdleResourceSurfaceSearch();
                this.nextIdleResourceSurfaceSearchTick = this.tickCount
                        + IDLE_RESOURCE_STUCK_TICKS
                        + this.getRandom().nextInt(21);
            }
            surfaceEscapeTarget = this.cachedIdleResourceSurfaceEscapeTarget;
        } else {
            this.idleResourceSurfaceSearchOrigin = null;
            this.cachedIdleResourceSurfaceEscapeTarget = null;
            this.clearIdleResourceSurfaceSearch();
            this.nextIdleResourceSurfaceSearchTick = 0;
        }
        BlockPos routeTarget = navigationTarget == null
                ? surfaceEscapeTarget == null ? feet : surfaceEscapeTarget
                : navigationTarget;

        if (this.idleResourceStuckWatchPos == null
                || !this.idleResourceStuckWatchPos.equals(feet)
                || this.idleResourceStuckWatchTarget == null
                || !this.idleResourceStuckWatchTarget.equals(routeTarget)) {
            this.idleResourceStuckWatchPos = feet.immutable();
            this.idleResourceStuckWatchTarget = routeTarget.immutable();
            this.idleResourceStuckTicks = 0;
            return;
        }

        this.idleResourcePathStuckFallbackAi.watchAndStart(
                serverLevel,
                routeTarget,
                routeTarget,
                "idle resource fallback",
                pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos)
        );
        if (this.idleResourcePathStuckFallbackAi.isRunning()) {
            this.setIdleTraceDetail(this.idleResourcePathStuckFallbackAi.detail("idle resource fallback"), 20 * 3);
            return;
        }

        if (this.idleResourceStuckRecheckTicks > 0) {
            this.idleResourceStuckRecheckTicks--;
            return;
        }

        if (++this.idleResourceStuckTicks < IDLE_RESOURCE_STUCK_TICKS) {
            return;
        }

        if (surfaceEscapeTarget == null && this.startIdleResourceFallbackClearBlock(serverLevel, feet, routeTarget)) {
            this.idleResourceStuckRecheckTicks = IDLE_RESOURCE_STUCK_RECHECK_TICKS;
            this.idleResourceStuckTicks = 0;
            return;
        }

        if (surfaceEscapeTarget == null
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this, feet)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this, surfaceEscapeTarget)) {
            this.setIdleTraceDetail("idle resource fallback blocked: no surface escape or clear @ " + posText(feet), 20 * 3);
            this.idleResourceStuckRecheckTicks = IDLE_RESOURCE_STUCK_RECHECK_TICKS;
            this.idleResourceStuckTicks = 0;
            return;
        }

        this.getNavigation().stop();
        this.requestExplorationUpwardEscapeTo(
                surfaceEscapeTarget,
                IDLE_RESOURCE_SURFACE_ESCAPE_TICKS,
                IDLE_RESOURCE_SURFACE_ESCAPE_MAX_BLOCKS
        );
        this.setCurrentAiDetail("exploration climb request @ "
                + posText(surfaceEscapeTarget)
                + " max="
                + IDLE_RESOURCE_SURFACE_ESCAPE_MAX_BLOCKS);
        this.setIdleTraceDetail("idle resource fallback climb request @ " + posText(surfaceEscapeTarget), 20 * 3);
        this.idleResourceStuckRecheckTicks = IDLE_RESOURCE_STUCK_RECHECK_TICKS;
        this.idleResourceStuckTicks = 0;
        this.wakeUpIdleWork();
    }

    private void tickIdleResourceFallbackClearBlock(ServerLevel serverLevel) {
        if (!this.canRunIdleResourceStuckFallback(serverLevel)) {
            this.resetIdleResourceStuckFallback();
            return;
        }

        BlockPos target = this.idleResourceFallbackClearBlockAi.targetPos();
        ClearBlockAi.TickResult result = this.idleResourceFallbackClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            this.setCurrentAiState(AI_IDLE);
            this.setIdleTraceDetail(this.idleResourceFallbackClearBlockAi.detail(), 20 * 3);
            return;
        }

        this.idleResourceFallbackToolAi.restoreMainHand();
        this.idleResourceStuckTicks = 0;
        this.idleResourceStuckRecheckTicks = IDLE_RESOURCE_STUCK_RECHECK_TICKS;
        this.idleResourceStuckWatchPos = null;
        this.idleResourceStuckWatchTarget = null;
        if (result == ClearBlockAi.TickResult.DONE) {
            if (this.forceIdleResourceMoveThrough(serverLevel, target)) {
                this.setIdleTraceDetail("idle resource fallback clear done; forced move @ " + posTextOrNone(target), 20 * 3);
            } else {
                this.setIdleTraceDetail("idle resource fallback clear done; retrying @ " + posTextOrNone(target), 20 * 3);
            }
        } else {
            this.setIdleTraceDetail("idle resource fallback clear failed; retrying", 20 * 3);
        }
        this.wakeUpIdleWork();
    }

    private boolean startIdleResourceFallbackClearBlock(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        List<BlockPos> candidates = this.idleResourceFallbackClearCandidates(feet, routeTarget);
        if (candidates.isEmpty()) {
            return false;
        }

        int preferredCount = Math.min(IDLE_RESOURCE_CLEAR_RANDOM_POOL, candidates.size());
        if (preferredCount > 1) {
            Collections.rotate(candidates.subList(0, preferredCount), this.getRandom().nextInt(preferredCount));
        }

        for (BlockPos candidate : candidates) {
            if (this.idleResourceFallbackClearBlockAi.start(
                    serverLevel,
                    candidate,
                    state -> this.isIdleResourceFallbackClearable(serverLevel, candidate, state),
                    "clearing idle resource fallback",
                    IDLE_RESOURCE_CLEAR_TICKS,
                    IDLE_RESOURCE_CLEAR_DISTANCE_SQR,
                    true
            )) {
                this.setIdleTraceDetail("idle resource fallback clearing @ " + posText(candidate), 20 * 3);
                return true;
            }
        }
        return false;
    }

    private List<BlockPos> idleResourceFallbackClearCandidates(BlockPos feet, BlockPos routeTarget) {
        List<BlockPos> candidates = new ArrayList<>();
        this.addIdleResourceClearCandidate(candidates, feet);
        this.addIdleResourceClearCandidate(candidates, feet.above());
        this.addIdleResourceClearCandidate(candidates, feet.above(2));

        for (Direction direction : this.idleResourceDirectionsToward(feet, routeTarget)) {
            BlockPos side = feet.relative(direction);
            this.addIdleResourceClearCandidate(candidates, side);
            this.addIdleResourceClearCandidate(candidates, side.above());
            this.addIdleResourceClearCandidate(candidates, side.above(2));

            BlockPos next = side.relative(direction);
            this.addIdleResourceClearCandidate(candidates, next);
            this.addIdleResourceClearCandidate(candidates, next.above());
        }

        candidates.removeIf(pos -> pos == null
                || pos.equals(feet.below())
                || this.isTemporaryPillarSupport(pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos));
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(feet))
                .thenComparingDouble(pos -> routeTarget == null ? 0.0D : pos.distSqr(routeTarget)));
        return candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private void addIdleResourceClearCandidate(List<BlockPos> candidates, BlockPos pos) {
        if (pos != null) {
            candidates.add(pos.immutable());
        }
    }

    private List<Direction> idleResourceDirectionsToward(BlockPos feet, BlockPos routeTarget) {
        List<Direction> directions = new ArrayList<>();
        if (routeTarget != null) {
            int dx = routeTarget.getX() - feet.getX();
            int dz = routeTarget.getZ() - feet.getZ();
            Direction xDirection = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : null;
            Direction zDirection = dz > 0 ? Direction.SOUTH : dz < 0 ? Direction.NORTH : null;
            if (Math.abs(dx) >= Math.abs(dz)) {
                this.addIdleResourceDirection(directions, xDirection);
                this.addIdleResourceDirection(directions, zDirection);
            } else {
                this.addIdleResourceDirection(directions, zDirection);
                this.addIdleResourceDirection(directions, xDirection);
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            this.addIdleResourceDirection(directions, direction);
        }
        return directions;
    }

    private void addIdleResourceDirection(List<Direction> directions, @Nullable Direction direction) {
        if (direction != null && !directions.contains(direction)) {
            directions.add(direction);
        }
    }

    private boolean isIdleResourceFallbackClearable(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return pos != null
                && state != null
                && !state.isAir()
                && !pos.equals(this.blockPosition().below())
                && !this.isTemporaryPillarSupport(pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this, serverLevel, pos)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private boolean forceIdleResourceMoveThrough(ServerLevel serverLevel, @Nullable BlockPos clearedPos) {
        if (clearedPos == null) {
            return false;
        }

        BlockPos feet = this.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        this.addIdleResourceClearCandidate(candidates, clearedPos);
        this.addIdleResourceClearCandidate(candidates, clearedPos.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            this.addIdleResourceClearCandidate(candidates, clearedPos.relative(direction));
            this.addIdleResourceClearCandidate(candidates, clearedPos.below().relative(direction));
        }

        Optional<BlockPos> stand = candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> !PlayerNpcHomeUtil.isInsideBuildFootprint(this, pos))
                .filter(pos -> PathNavigationAi.canStandAt(serverLevel, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)));
        if (stand.isEmpty()) {
            return false;
        }

        double targetX = stand.get().getX() + 0.5D;
        double targetZ = stand.get().getZ() + 0.5D;
        double dx = targetX - this.getX();
        double dz = targetZ - this.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return false;
        }

        this.getNavigation().stop();
        this.getLookControl().setLookAt(targetX, this.getY(), targetZ, 30.0F, 30.0F);
        this.getMoveControl().setWantedPosition(targetX, this.getY(), targetZ, 1.0D);
        Vec3 motion = this.getDeltaMovement();
        this.setDeltaMovement(dx / length * 0.24D, motion.y, dz / length * 0.24D);
        this.hurtMarked = true;
        return true;
    }

    private boolean canRunIdleResourceStuckFallback(ServerLevel serverLevel) {
        String state = this.getCurrentAiState();
        if ((!AI_IDLE.equals(state) && !"ai.player_npc.looking_for_work".equals(state))
                || this.hasRunningAiGoals()
                || !this.isAlive()
                || this.isNoAi()
                || this.isPassenger()
                || this.isHealing()
                || this.getTarget() != null
                || this.isSleeping()
                || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this)
                || this.getUpwardEscapeTarget() != null
                || this.getHoleEscapeCooldown() > 0
                || this.highIdleDescentFallbackAi.isRunning()
                || this.highIdleDescentNavigationTarget != null
                || !this.onGround()
                || !this.getNavigation().isDone() && !this.getNavigation().isStuck()) {
            return false;
        }

        if (this.tickCount < this.nextIdleResourceEligibilityCheckTick) {
            return this.idleResourceEligibilityCached;
        }
        this.nextIdleResourceEligibilityCheckTick = this.tickCount
                + IDLE_RESOURCE_ELIGIBILITY_CHECK_INTERVAL_TICKS;

        // This fallback exists to free an otherwise unschedulable resource route. It must not run
        // while priority-4/5 construction handoff is already actionable or still resolving its
        // bounded blueprint scan. Current-build demand describes the complete remaining layout;
        // it can stay true while carried material is sufficient for another placement batch.
        // Starting an exploration climb in that state adds a hole cooldown, blocking ReturnHome,
        // Terraform and BuildHouse and creating the observed idle/half-step loop.
        if (TerraformBuildSiteGoal.hasActionablePrepWork(this, serverLevel)
                || BuildHouseGoal.shouldYieldSupplyWorkForBuild(this, serverLevel)
                || BuildHouseGoal.isHomeBuildWorkSearchPending(this, serverLevel)) {
            this.idleResourceEligibilityCached = false;
            return this.idleResourceEligibilityCached;
        }

        this.idleResourceEligibilityCached = this.hasInterest(PlayerNpcInterest.BUILDING)
                && this.isDailyJobActive(PlayerNpcInterest.BUILDING)
                && (this.shouldPrioritizeLogGathering()
                || this.shouldPrioritizeCobblestoneGathering()
                || PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, this)
                || PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this));
        return this.idleResourceEligibilityCached;
    }

    private void beginIdleResourceSurfaceSearch(BlockPos feet) {
        this.idleResourceSurfaceSearchOrigin = feet.immutable();
        this.cachedIdleResourceSurfaceEscapeTarget = null;
        this.idleResourceSurfaceCandidates.clear();
        this.idleResourceRelaxedSurfaceCandidates.clear();
        this.idleResourceSurfaceSearchCursor = 0;
        this.idleResourceSurfaceSearchPending = true;
    }

    private boolean advanceIdleResourceSurfaceSearch(ServerLevel serverLevel) {
        BlockPos feet = this.idleResourceSurfaceSearchOrigin;
        if (feet == null) {
            this.clearIdleResourceSurfaceSearch();
            return true;
        }
        int end = Math.min(
                HIGH_IDLE_DESCENT_COLUMN_OFFSETS.size(),
                this.idleResourceSurfaceSearchCursor + IDLE_RESOURCE_SURFACE_COLUMNS_PER_SLICE
        );
        for (; this.idleResourceSurfaceSearchCursor < end; this.idleResourceSurfaceSearchCursor++) {
            BlockPos offset = HIGH_IDLE_DESCENT_COLUMN_OFFSETS.get(this.idleResourceSurfaceSearchCursor);
            if (offset.getX() * offset.getX() + offset.getZ() * offset.getZ()
                    > IDLE_RESOURCE_SURFACE_ESCAPE_RADIUS * IDLE_RESOURCE_SURFACE_ESCAPE_RADIUS) {
                continue;
            }
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            int climb = y - feet.getY();
            if (climb <= 0 || climb > IDLE_RESOURCE_SURFACE_ESCAPE_MAX_BLOCKS) {
                continue;
            }
            BlockPos candidate = new BlockPos(x, y, z);
            if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                    || PlayerNpcHomeUtil.isInsideBuildFootprint(this, candidate)) {
                continue;
            }
            (serverLevel.canSeeSky(candidate.above())
                    ? this.idleResourceSurfaceCandidates
                    : this.idleResourceRelaxedSurfaceCandidates).add(candidate.immutable());
        }
        return this.idleResourceSurfaceSearchCursor >= HIGH_IDLE_DESCENT_COLUMN_OFFSETS.size();
    }

    private void finishIdleResourceSurfaceSearch() {
        BlockPos feet = this.idleResourceSurfaceSearchOrigin;
        if (feet == null) {
            this.clearIdleResourceSurfaceSearch();
            return;
        }
        BlockPos selected = this.selectIdleResourceSurfaceEscapeTarget(
                this.idleResourceSurfaceCandidates,
                feet
        );
        if (selected != null) {
            this.cachedIdleResourceSurfaceEscapeTarget = selected;
        } else {
            this.cachedIdleResourceSurfaceEscapeTarget = this.selectIdleResourceSurfaceEscapeTarget(
                    this.idleResourceRelaxedSurfaceCandidates,
                    feet
            );
        }
        this.idleResourceSurfaceSearchPending = false;
    }

    private void clearIdleResourceSurfaceSearch() {
        this.idleResourceSurfaceCandidates.clear();
        this.idleResourceRelaxedSurfaceCandidates.clear();
        this.idleResourceSurfaceSearchCursor = 0;
        this.idleResourceSurfaceSearchPending = false;
    }

    @Nullable
    private BlockPos selectIdleResourceSurfaceEscapeTarget(List<BlockPos> candidates, BlockPos feet) {
        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> pos.getY() - feet.getY())
                .thenComparingDouble(pos -> pos.distSqr(feet)));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private void resetIdleResourceStuckFallback() {
        this.idleResourcePathStuckFallbackAi.stop();
        this.idleResourceFallbackClearBlockAi.stop();
        this.idleResourceFallbackToolAi.restoreMainHand();
        this.idleResourceStuckWatchPos = null;
        this.idleResourceStuckWatchTarget = null;
        this.idleResourceStuckTicks = 0;
        this.idleResourceStuckRecheckTicks = 0;
        this.clearIdleResourceSurfaceSearch();
    }

    private static String posTextOrNone(@Nullable BlockPos pos) {
        return pos == null ? "none" : posText(pos);
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private boolean hasExplorationClimbFallbackDetail() {
        String detail = this.getCurrentAiDetail();
        return detail != null
                && (detail.startsWith("exploration climb request @ ")
                || detail.startsWith("moving to safe stand after climb stuck @ ")
                || detail.startsWith("clearing exploration climb fallback @ ")
                || detail.startsWith("exploration climb clear"));
    }

    /** Explicitly forget a support whose ownership was invalidated by another system. */
    public void forgetTemporaryPillarSupport(@Nullable BlockPos pos) {
        if (pos != null) {
            BlockPos key = pos.immutable();
            this.temporaryPillarSupports.remove(key);
            this.gatherLogsTemporaryPillarSupports.remove(key);
        }
    }

    private void tickDailySupplyGoalReroll(ServerLevel serverLevel) {
        long dayTime = serverLevel.getOverworldClockTime();
        long day = dayTime / DAY_LENGTH_TICKS;
        if (dayTime % DAY_LENGTH_TICKS != 0L || day == this.lastSupplyGoalRerollDay) {
            return;
        }

        this.rawLogReserveTarget = ResourceAi.randomLogSupplyGoal(this.getRandom());
        this.woodSupplyTarget = this.rawLogReserveTarget;
        this.cobblestoneSupplyTarget = ResourceAi.randomStoneSupplyGoal(this.getRandom());
        this.lastSupplyGoalRerollDay = day;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.setSchedulerTraceDetail("new daily supply goals logs="
                + this.rawLogReserveTarget
                + " stone="
                + this.cobblestoneSupplyTarget);
    }

    private void tickDailyJobSelection(ServerLevel serverLevel) {
        long dayTime = serverLevel.getOverworldClockTime();
        long day = dayTime / DAY_LENGTH_TICKS;
        long timeOfDay = dayTime % DAY_LENGTH_TICKS;

        if (this.isBuildingBaseSelectionLocked()) {
            this.selectDailyJob(day, PlayerNpcInterest.BUILDING, "building base selection locked");
            return;
        }
        if (this.isFarmingBaseSelectionLocked()) {
            this.selectDailyJob(day, PlayerNpcInterest.FARMING, "farming base selection locked");
            return;
        }

        if (this.selectedDailyJobDay == day
                && this.selectedDailyJobInterest != null
                && this.hasInterest(this.selectedDailyJobInterest)) {
            return;
        }
        if (this.selectedDailyJobDay == day
                && this.selectedDailyJobInterest == null
                && this.availableDailyJobs().isEmpty()) {
            return;
        }

        boolean exactRollTime = timeOfDay == DAILY_JOB_ROLL_TIME;
        boolean fallbackDayRoll = timeOfDay > DAILY_JOB_ROLL_TIME
                && timeOfDay < DAILY_JOB_FALLBACK_ROLL_END_TIME
                && this.selectedDailyJobDay != day;
        if (!exactRollTime && !fallbackDayRoll) {
            return;
        }

        List<PlayerNpcInterest> jobs = this.availableDailyJobs();
        if (jobs.isEmpty()) {
            this.selectDailyJob(day, null, "no job interests");
            return;
        }

        PlayerNpcInterest selected = jobs.get(this.getRandom().nextInt(jobs.size()));
        this.selectDailyJob(day, selected, "daily roll");
    }

    private List<PlayerNpcInterest> availableDailyJobs() {
        List<PlayerNpcInterest> jobs = new ArrayList<>();
        for (PlayerNpcInterest interest : DAILY_JOB_INTERESTS) {
            if (this.hasInterest(interest)) {
                jobs.add(interest);
            }
        }
        return jobs;
    }

    private void selectDailyJob(long day, @Nullable PlayerNpcInterest interest, String reason) {
        if (interest != null && (!interest.isJob() || !this.hasInterest(interest))) {
            interest = null;
        }
        if (this.selectedDailyJobDay == day && this.selectedDailyJobInterest == interest) {
            return;
        }

        this.selectedDailyJobDay = day;
        this.selectedDailyJobInterest = interest;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.setSchedulerTraceDetail("daily job="
                + (interest == null ? "none" : interest.displayName())
                + " reason="
                + reason);
    }

    private void setSchedulerTraceDetail(String detail) {
        this.setIdleTraceDetail(detail, 40);
        if (AI_IDLE.equals(this.getCurrentAiState())) {
            this.setCurrentAiDetail("");
        }
    }

    private static Optional<PlayerNpcInterest> parseSavedDailyJobInterest(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            PlayerNpcInterest interest = PlayerNpcInterest.valueOf(name);
            return interest.isJob() ? Optional.of(interest) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static int tickCooldown(int cooldown) {
        return cooldown > 0 ? cooldown - 1 : 0;
    }

    private void clearStaleHealingState() {
        if (!this.isHealing()) {
            return;
        }

        boolean healingGoalRunning = this.goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
                .map(WrappedGoal::getGoal)
                .anyMatch(EatHealingFoodGoal.class::isInstance);
        if (healingGoalRunning
                || ("ai.player_npc.eating".equals(this.getCurrentAiState())
                && this.isUsingItem()
                && this.getMainHandItem().has(net.minecraft.core.component.DataComponents.FOOD))) {
            return;
        }

        this.setHealing(false);
        if (AI_IDLE.equals(this.getCurrentAiState())) {
            this.wakeUpIdleWork();
        }
    }

    private void tickStartupIdleWake() {
        if (this.startupIdleWakeTicks <= 0) {
            return;
        }

        this.startupIdleWakeTicks--;
        if (!this.isAlive()
                || this.isNoAi()
                || this.isPassenger()
                || this.isHealing()
                || this.getTarget() != null
                || this.isSleeping()
                || this.hasRunningAiGoals()) {
            return;
        }

        String state = this.getCurrentAiState();
        if (!AI_IDLE.equals(state) && !"ai.player_npc.looking_for_work".equals(state)) {
            return;
        }

        this.wakeUpIdleWork();
        this.clearStaleIdleNavigation();
    }

    private void tickTasklessActivityWatchdog() {
        if (this.level().isClientSide()
                || !this.isAlive()
                || this.isNoAi()
                || this.isPassenger()
                || this.isHealing()
                || this.getTarget() != null
                || this.isSleeping()) {
            this.tasklessIdleTicks = 0;
            return;
        }

        String state = this.getCurrentAiState();
        if (!AI_IDLE.equals(state) && !"ai.player_npc.looking_for_work".equals(state)) {
            this.tasklessIdleTicks = 0;
            return;
        }

        if ("ai.player_npc.looking_for_work".equals(state) && !this.hasRunningAiGoals()) {
            this.setCurrentAiState(AI_IDLE);
        }

        this.tasklessIdleTicks++;
        if (this.tasklessIdleTicks >= TASKLESS_IDLE_WAKE_TICKS) {
            if (!this.hasActiveIdleWorkCooldown()) {
                this.wakeUpIdleWork();
            }
            if (!this.hasRunningAiGoals()) {
                this.clearStaleIdleNavigation();
            }
            if (this.tasklessIdleTicks > TASKLESS_IDLE_WAKE_TICKS * 4) {
                this.tasklessIdleTicks = TASKLESS_IDLE_WAKE_TICKS;
            }
        }
    }

    private boolean hasActiveIdleWorkCooldown() {
        return this.buildHouseCooldown > 0
                || this.craftGearCooldown > 0
                || this.manageHomeCooldown > 0
                || this.returnHomeCooldown > 0
                || this.craftCooldown > 0
                || this.farmCooldown > 0
                || this.gatherCooldown > 0
                || this.biomeExploreCooldown > 0
                || this.oreMiningCooldown > 0
                || this.saplingPlantCooldown > 0;
    }

    private boolean hasRunningAiGoals() {
        return this.goalSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning).findAny().isPresent()
                || this.targetSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning).findAny().isPresent();
    }

    private void clearStaleIdleNavigation() {
        if (this.getNavigation().isDone() || this.getNavigation().isStuck()) {
            this.getNavigation().stop();
        }
    }

    private void cleanupStaleCombatState() {
        LivingEntity currentTarget = this.getTarget();
        if (currentTarget != null && this.isTeamAlliedWith(currentTarget)) {
            this.setTarget(null);
            this.getNavigation().stop();
            currentTarget = null;
        }
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            if (currentTarget instanceof Animal) {
                this.prioritizeAnimalLoot(currentTarget.blockPosition());
            }
            this.setTarget(null);
            currentTarget = null;
        }

        if (currentTarget != null
                && !this.hasInterest(PlayerNpcInterest.HUNT_PLAYERS)
                && this.isPassiveBuildingBlockedByPlayerTarget(currentTarget)
                && !this.isRecentRetaliationTarget(currentTarget)
                && !this.isChestProtectionTarget(currentTarget)) {
            this.setTarget(null);
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            this.setCurrentAiState(AI_IDLE);
            this.setCurrentAiDetail("");
            this.wakeUpIdleWork();
            currentTarget = null;
        }

        if (currentTarget == null && this.isCombatAiState(this.getCurrentAiState())) {
            this.setCurrentAiState(AI_IDLE);
        }

        if (currentTarget == null) {
            this.chestProtectionTargetId = null;
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            return;
        }

        if (this.staleTargetEntityId != currentTarget.getId()) {
            this.staleTargetEntityId = currentTarget.getId();
            this.staleTargetTicks = 0;
            this.lastCombatProgressTick = this.tickCount;
        }

        double followDistance = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        double clearDistance = Math.max(48.0D, followDistance + 24.0D);
        double distanceSqr = this.distanceToSqr(currentTarget);
        boolean tooFar = distanceSqr > clearDistance * clearDistance;
        boolean blockedAndNotMoving = distanceSqr > 16.0D * 16.0D
                && !this.hasLineOfSight(currentTarget)
                && (this.getNavigation().isDone() || this.getNavigation().isStuck());
        boolean noCombatProgress = this.tickCount - this.lastCombatProgressTick > 20 * 8;

        if (noCombatProgress && (tooFar || blockedAndNotMoving)) {
            this.staleTargetTicks++;
        } else {
            this.staleTargetTicks = 0;
        }

        if (this.staleTargetTicks > 20 * 3) {
            this.setTarget(null);
            this.chestProtectionTargetId = null;
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            if (this.isCombatAiState(this.getCurrentAiState())) {
                this.setCurrentAiState(AI_IDLE);
            }
        }
    }

    private boolean isPassiveBuildingBlockedByPlayerTarget(LivingEntity target) {
        return target instanceof net.minecraft.world.entity.player.Player
                || target instanceof PlayerNpcEntity
                || this.isSmartNpcCompatPlayerLikeTarget(target);
    }

    private boolean isRecentRetaliationTarget(LivingEntity target) {
        if (target == null) {
            return false;
        }
        // Vanilla's recent-damage reference can expire before its retaliation goal finishes.
        // That running goal still owns a legitimate defensive fight, even for non-hunters.
        return target == this.getLastHurtByMob()
                || target == this.getTarget() && this.targetSelector.getAvailableGoals().stream().filter(WrappedGoal::isRunning)
                .anyMatch(wrapped -> wrapped.getGoal() instanceof HurtByTargetGoal);
    }

    public void setChestProtectionTarget(LivingEntity offender) {
        this.chestProtectionTargetId = offender.getUUID();
        this.setTarget(offender);
        this.lastCombatProgressTick = this.tickCount;
    }

    private boolean isChestProtectionTarget(LivingEntity target) {
        return this.chestProtectionTargetId != null
                && this.chestProtectionTargetId.equals(target.getUUID());
    }

    private boolean isCombatAiState(String state) {
        return "ai.player_npc.retaliating".equals(state)
                || "ai.player_npc.melee_attacking".equals(state)
                || "ai.player_npc.ranged_bow".equals(state)
                || "ai.player_npc.throwing_ender_pearl".equals(state)
                || "ai.player_npc.combat_fishing".equals(state)
                || "ai.player_npc.shield_guarding".equals(state)
                || "ai.player_npc.troll_hit".equals(state)
                || "ai.player_npc.using_lava_bucket".equals(state)
                || "ai.player_npc.blocking_projectile".equals(state)
                || "ai.player_npc.engaging".equals(state)
                || "ai.player_npc.engaging_player_like".equals(state)
                || "ai.player_npc.engaging_monster".equals(state)
                || "ai.player_npc.breaking_target_obstruction".equals(state)
                || "ai.player_npc.hunting_animal".equals(state)
                || "ai.player_npc.engaging_villager".equals(state)
                || "ai.player_npc.assisting_alert".equals(state)
                || "ai.player_npc.protecting_chest".equals(state);
    }

    public SpawnGroupData finalizeSpawn(@NotNull ServerLevelAccessor serverLevelAccessor, @NotNull DifficultyInstance difficultyInstance, @NotNull EntitySpawnReason mobSpawnType, @Nullable SpawnGroupData spawngroupdata) {
        SpawnGroupData returnSpawnGroupData = super.finalizeSpawn(serverLevelAccessor, difficultyInstance, mobSpawnType, spawngroupdata);

        if (this.isRemoved()) {
            return returnSpawnGroupData;
        }

        ServerLevel serverLevel = serverLevelAccessor.getLevel();

        if (PlayerNpcNaturalSpawnCap.isNaturalSpawnType(mobSpawnType)) {
            PlayerNpcNaturalSpawnCap.onNaturalSpawnFinalized(this);
        }

        this.setCurrentAiState(AI_IDLE);

        MinecraftServer server = serverLevel.getServer();
        if (server != null && !server.isSameThread()) {
            // Chunk-generation workers only construct and serialize the entity. Commands,
            // persistent progression data, chat, and scoreboard work run on its first server tick.
            this.pendingSpawnInitialization = true;
            return returnSpawnGroupData;
        }

        this.pendingSpawnInitialization = true;
        this.completeSpawnInitialization();
        return returnSpawnGroupData;
    }

    private void completeSpawnInitialization() {
        if (!this.pendingSpawnInitialization) {
            return;
        }
        this.pendingSpawnInitialization = false;
        if (!this.ensureConfiguredUsername() || this.isRemoved()) {
            return;
        }

        List<String> commands = EquipmentDataLoader.getEquipCommands(0.85f, this);
        for (String cmd : commands) {
            try {
                Objects.requireNonNull(this.getServer()).getCommands().getDispatcher().execute(
                        cmd,
                        this.createCommandSourceStack().withSuppressedOutput().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER)
                );
            } catch (CommandSyntaxException ignored) {
            }
        }

        this.mainWeaponItem = this.getMainHandItem().copy();
        this.offWeaponItem = this.getOffWeaponItem().copy();
        this.seedInventory();
        this.completeFishingStarterStringMigration();

        ChatUtil.joinGame(this);

        if (Math.random() <= 0.05D) {
            TeamUtil.addOrJoinTeam(this, "player");
        }
    }

    protected boolean seedInventory() {
        if (!InventoryUtils.isEmpty(this.inventory)) {
            return false;
        }

        Random random = new Random();
        boolean isHard = ProgressionUtil.isAtLeastDifficulty(Difficulty.HARD);
        boolean isMedium = ProgressionUtil.isAtLeastDifficulty(Difficulty.MEDIUM);

        if (this.hasInterest(PlayerNpcInterest.FISHING)) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.STRING, new Random().nextInt(3, 8)));
        }
        if (this.hasInterest(PlayerNpcInterest.FARMING)) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.WATER_BUCKET));
            if (random.nextFloat() < 0.72F) {
                ItemLike starterCrop = random.nextBoolean() ? Items.CARROT : Items.POTATO;
                InventoryUtils.addItem(this.inventory, new ItemStack(starterCrop, random.nextInt(2, 6)));
            } else if (random.nextFloat() < 0.65F) {
                InventoryUtils.addItem(this.inventory, new ItemStack(Items.WHEAT_SEEDS, random.nextInt(2, 6)));
            }
        }

        int goldenAppleCount = isHard ? random.nextInt(2, 4)
                : isMedium ? random.nextInt(1, 3)
                : 0;
        if (goldenAppleCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.GOLDEN_APPLE, goldenAppleCount));
        }
        if (isHard) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, random.nextInt(0, 4)));
        }

        List<ItemLike> foods = new ArrayList<>(REGULAR_FOODS);
        for (int i = 0; i < random.nextInt(isHard ? 2 : (isMedium ? 1 : 0), isHard ? 3 : (isMedium ? 2 : 1)) && !foods.isEmpty(); i++) {
            ItemLike food = foods.remove(random.nextInt(foods.size()));
            int foodCount = isHard ? random.nextInt(4, 12)
                    : isMedium ? random.nextInt(3, 6)
                    : random.nextInt(2, 4);
            InventoryUtils.addItem(this.inventory, new ItemStack(food, foodCount));
        }

        int arrowCount = isHard ? random.nextInt(6, 12)
                : isMedium ? random.nextInt(4, 8)
                : 0;
        if (arrowCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.BOW));
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ARROW, arrowCount));
        }

        int enderPearlCount = isHard ? random.nextInt(16, 33)
                : isMedium ? random.nextInt(0, 13)
                : 0;
        if (enderPearlCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ENDER_PEARL, enderPearlCount));
        }

        if (isMedium && !InventoryUtils.hasItem(this.inventory, Items.WATER_BUCKET)) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.WATER_BUCKET));
        }
        if ((isHard && random.nextFloat() < 0.50F) || (!isHard && isMedium && random.nextFloat() < 0.30F)) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.FLINT_AND_STEEL));
        }
        if (isHard && random.nextFloat() < 0.08F) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.SPYGLASS));
        }
        if (isHard && random.nextFloat() < 0.04F) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.JUKEBOX));
            ItemLike disc = MUSIC_DISCS.get(random.nextInt(MUSIC_DISCS.size()));
            InventoryUtils.addItem(this.inventory, new ItemStack(disc));
        }

        List<ItemLike> blocks = new ArrayList<>(PLACEABLE_BLOCKS);
        int blockStacks = random.nextInt(1, 2);
        for (int i = 0; i < blockStacks && !blocks.isEmpty(); i++) {
            ItemLike block = blocks.remove(random.nextInt(blocks.size()));
            int blockCount = isHard ? random.nextInt(8, 12)
                    : isMedium ? random.nextInt(4, 8)
                    : random.nextInt(0, 6);
            InventoryUtils.addItem(this.inventory, new ItemStack(block, blockCount));
        }

        List<ItemStack> materials = new ArrayList<>();
        if (isHard) {
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.COAL, random.nextInt(0, 5)));

            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.IRON_INGOT, random.nextInt(0, 3)));

            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.GOLD_INGOT, random.nextInt(0, 4)));

            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.REDSTONE, random.nextInt(0, 6)));

            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.LAPIS_LAZULI, random.nextInt(0, 4)));

            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.DIAMOND, random.nextInt(0, 1)));
            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.EMERALD, random.nextInt(0, 1)));
            }
        } else if (isMedium) {
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.COAL, random.nextInt(0, 2)));
            }
            if (random.nextFloat() < 0.70F) {
                materials.add(new ItemStack(Items.IRON_INGOT, random.nextInt(0, 2)));
            }
            if (random.nextFloat() < 0.70F) {
                materials.add(new ItemStack(Items.GOLD_INGOT, random.nextInt(0, 3)));
            }
            if (new Random().nextBoolean()) {
                materials.add(new ItemStack(Items.REDSTONE, random.nextInt(0, 4)));
            }
        } else if (random.nextFloat() >= 0.55F) {
            if (random.nextFloat() < 0.30F) {
                materials.add(new ItemStack(Items.COAL, random.nextInt(0, 1)));
            }
            if (random.nextFloat() < 0.15F) {
                materials.add(new ItemStack(Items.IRON_INGOT, random.nextInt(0, 1)));
            }
        }

        int materialTypes = random.nextInt(0, Math.min(2, materials.size()) + 1);
        for (int i = 0; i < materialTypes; i++) {
            ItemStack material = materials.remove(random.nextInt(materials.size()));
            InventoryUtils.addItem(this.inventory, material);
        }

        return true;
    }

    private void tickFishingStarterStringMigration() {
        if (this.fishingStarterStringVersion >= FISHING_STARTER_STRING_VERSION
                || !this.hasInterest(PlayerNpcInterest.FISHING)
                || Math.floorMod(this.tickCount + this.getId(), FISHING_STARTER_MIGRATION_INTERVAL_TICKS) != 0) {
            return;
        }

        this.completeFishingStarterStringMigration();
    }

    private void completeFishingStarterStringMigration() {
        if (!this.hasInterest(PlayerNpcInterest.FISHING)) {
            return;
        }

        int stringCount = PlayerNpcCraftingUtil.countItem(
                this.inventory,
                stack -> stack.is(Items.STRING)
        );
        int missing = Math.max(0, FISHING_STARTER_STRING_REQUIRED - stringCount);
        if (missing > 0 && !InventoryUtils.addItem(this.inventory, new ItemStack(Items.STRING, missing))) {
            return;
        }
        this.fishingStarterStringVersion = FISHING_STARTER_STRING_VERSION;
    }

    @Override
    public void awardKillScore(@NotNull Entity entity, @NotNull DamageSource damageSource) {
        super.awardKillScore(entity, damageSource);
        if (entity instanceof LivingEntity livingEntity) {
            if (this.level() instanceof ServerLevel serverLevel) {
                this.awardStoredExperience(livingEntity.getExperienceReward(serverLevel, this));
            }
        }
        if (ChatUtil.shouldPlayerNpcTauntKill(this, entity)) {
            ChatUtil.scheduleKillerTaunt(this, entity);
        }
    }

    @Override
    public void onEquipItem(@NotNull EquipmentSlot pSlot, @NotNull ItemStack pOldItem, @NotNull ItemStack pNewItem) {
        if (pSlot == EquipmentSlot.MAINHAND && !this.suppressHeldItemCacheUpdate) {
            this.promoteMainWeaponItemFromEquip(pNewItem, pOldItem);
        }

        if (pSlot == EquipmentSlot.OFFHAND &&
                (pNewItem.is(ItemTags.SWORDS) || pNewItem.getItem() instanceof AxeItem || pNewItem.getItem() instanceof ShieldItem)) {
            this.setOffWeaponItem(pNewItem);
        }

        super.onEquipItem(pSlot, pOldItem, pNewItem);
    }

    public static boolean canSpawn(EntityType<PlayerNpcEntity> entityType, ServerLevelAccessor level,
                                   EntitySpawnReason spawnType, BlockPos position, RandomSource random) {
        ServerLevel serverLevel = level.getLevel();
        boolean naturalSpawn = PlayerNpcNaturalSpawnCap.isNaturalSpawnType(spawnType);
        if ((spawnType == EntitySpawnReason.NATURAL || spawnType == EntitySpawnReason.CHUNK_GENERATION)
                && !PlayerNpcNaturalSpawnCap.isNaturalSpawningEnabled(serverLevel)) {
            return false;
        }
        if (naturalSpawn && !PlayerNpcNaturalSpawnCap.mayAttemptNaturalSpawn(serverLevel.getServer())) {
            return false;
        }
        if (spawnType == EntitySpawnReason.CHUNK_GENERATION
                ? !hasConfiguredNames()
                : !hasAvailableConfiguredName(serverLevel.getServer())) {
            return false;
        }
        if (serverLevel.isDarkOutside()) {
            return false;
        }
        if (!PathfinderMob.checkMobSpawnRules(entityType, level, spawnType, position, random)) {
            return false;
        }
        return !naturalSpawn
                || PlayerNpcNaturalSpawnCap.tryReserveNaturalSpawn(serverLevel.getServer());
    }

    public static AttributeSupplier.Builder createAttributes() {
        AttributeSupplier.Builder builder = Mob.createMobAttributes();

        builder = builder.add(Attributes.MOVEMENT_SPEED, 0.35D);
        builder = builder.add(Attributes.MAX_HEALTH, 20.0D);
        builder = builder.add(Attributes.ARMOR, 0.0D);
        builder = builder.add(Attributes.ATTACK_DAMAGE, 1.0D);
        builder = builder.add(Attributes.FOLLOW_RANGE, 48.0D);
        builder = builder.add(Attributes.MINING_EFFICIENCY, 0.0D);
        builder = builder.add(Attributes.SUBMERGED_MINING_SPEED, 0.2D);
        builder = builder.add(Attributes.STEP_HEIGHT, 1.0D);
        return builder;
    }
}
