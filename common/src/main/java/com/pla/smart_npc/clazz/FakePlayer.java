package com.pla.smart_npc.clazz;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringUtil;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FakePlayer extends PathfinderMob {
    private static final int PROFILE_RETRY_INTERVAL_TICKS = 20 * 30;
    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(FakePlayer.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<ResolvableProfile> PROFILE = SynchedEntityData.defineId(
            FakePlayer.class,
            EntityDataSerializers.RESOLVABLE_PROFILE
    );
    private static final List<PlayerNpcInterest> DEFAULT_INTERESTS = List.of(
            PlayerNpcInterest.BUILDING
    );
    private static final Queue<FakePlayerName> NAME_POOL = new ArrayDeque<>();
    private static List<FakePlayerName> cachedNames = List.of();
    private static boolean nameConfigInitialized;
    private static long cachedNameConfigRevision = Long.MIN_VALUE;
    private static final Queue<FakePlayer> PROFILE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Object PROFILE_LOCK = new Object();
    private static Thread profileThread;

    private GameProfile profile;
    private Identifier skin;
    private Identifier cape;
    private Identifier elytra;
    private boolean skinAvailable;
    private boolean capeAvailable;
    private boolean elytraAvailable;
    private volatile boolean profileUpdateQueued;
    private boolean profileRefreshRequired;
    private int profileRetryTicks;
    private FakePlayerName cachedUsername;
    private String cachedUsernameValue = "";
    private long cachedUsernameConfigRevision = Long.MIN_VALUE;

    public double xCloakO;
    public double yCloakO;
    public double zCloakO;
    public double xCloak;
    public double yCloak;
    public double zCloak;

    public FakePlayer(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(NAME, "");
        builder.define(PROFILE, ResolvableProfile.createUnresolved(""));
    }

    @Override
    public void onSyncedDataUpdated(@NotNull EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (NAME.equals(key)) {
            this.invalidateCachedUsername();
            this.profile = null;
            this.profileUpdateQueued = false;
            this.profileRefreshRequired = !this.level().isClientSide();
            this.profileRetryTicks = 0;
            this.clearTextureState();
            if (this.hasUsername()) {
                this.getProfile();
            }
        } else if (PROFILE.equals(key)) {
            this.profile = this.entityData.get(PROFILE).partialProfile();
            this.profileUpdateQueued = false;
            this.clearTextureState();
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.updateCapeMotion();
        if (!this.level().isClientSide() && this.profileRefreshRequired && this.hasUsername()) {
            if (this.profileRetryTicks > 0) {
                this.profileRetryTicks--;
            } else {
                requestProfileUpdate(this);
            }
        }
    }

    private void updateCapeMotion() {
        this.xCloakO = this.xCloak;
        this.yCloakO = this.yCloak;
        this.zCloakO = this.zCloak;
        double xDiff = this.getX() - this.xCloak;
        double yDiff = this.getY() - this.yCloak;
        double zDiff = this.getZ() - this.zCloak;
        double limit = 10.0D;

        if (xDiff > limit || xDiff < -limit) {
            this.xCloak = this.getX();
            this.xCloakO = this.xCloak;
        }
        if (yDiff > limit || yDiff < -limit) {
            this.yCloak = this.getY();
            this.yCloakO = this.yCloak;
        }
        if (zDiff > limit || zDiff < -limit) {
            this.zCloak = this.getZ();
            this.zCloakO = this.zCloak;
        }

        this.xCloak += xDiff * 0.25D;
        this.yCloak += yDiff * 0.25D;
        this.zCloak += zDiff * 0.25D;
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(@NotNull ServerLevelAccessor level, @NotNull DifficultyInstance difficulty, @NotNull EntitySpawnReason spawnType, @Nullable SpawnGroupData groupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, groupData);
        MinecraftServer server = level.getLevel().getServer();
        if ((server == null || server.isSameThread()) && !this.ensureConfiguredUsername()) {
            return result;
        }
        this.setLeftHanded(false);
        return result;
    }

    @Override
    protected void addAdditionalSaveData(@NotNull ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.hasUsername()) {
            output.putString("Username", this.getUsername().getCombinedNames());
        }
        if (isCompleteProfile(this.profile)) {
            output.store("Profile", ResolvableProfile.CODEC, ResolvableProfile.createResolved(this.profile));
        }
    }

    @Override
    protected void readAdditionalSaveData(@NotNull ValueInput input) {
        super.readAdditionalSaveData(input);
        String username = input.getStringOr("Username", "");
        if (!StringUtil.isNullOrEmpty(username)) {
            this.setUsername(username);
        }
        GameProfile savedProfile = input.read("Profile", ResolvableProfile.CODEC)
                .map(ResolvableProfile::partialProfile)
                .orElse(null);
        if (savedProfile != null) {
            this.applyProfile(savedProfile);
            // Profiles saved by 1.21.1 can carry texture signatures that are no longer
            // accepted by Authlib 7. Keep displaying the saved profile while refreshing it.
            this.profileRefreshRequired = !this.level().isClientSide();
        }
    }

    /** Keeps legacy NPC inventory code on the server-only entity drop API. */
    public @Nullable ItemEntity spawnAtLocation(ItemStack stack) {
        return this.level() instanceof ServerLevel serverLevel
                ? super.spawnAtLocation(serverLevel, stack)
                : null;
    }

    public @Nullable MinecraftServer getServer() {
        return this.level().getServer();
    }

    public CommandSourceStack createCommandSourceStack() {
        return this.createCommandSourceStackForNameResolution((ServerLevel) this.level());
    }

    @Override
    public @Nullable Component getCustomName() {
        Component customName = super.getCustomName();
        if (customName != null && !customName.getString().isEmpty()) {
            return customName;
        }
        String displayName = this.getUsername().getDisplayName();
        return StringUtil.isNullOrEmpty(displayName) ? null : Component.literal(displayName);
    }

    @Override
    public boolean hasCustomName() {
        return super.hasCustomName() || !StringUtil.isNullOrEmpty(this.getUsername().getDisplayName());
    }

    @Override
    public @NotNull Component getDisplayName() {
        return this.getName();
    }

    public boolean hasUsername() {
        return !StringUtil.isNullOrEmpty(this.entityData.get(NAME));
    }

    public FakePlayerName getUsername() {
        if (!this.hasUsername() && !this.level().isClientSide()) {
            MinecraftServer server = this.level().getServer();
            if (server == null || server.isSameThread()) {
                this.ensureConfiguredUsername();
            }
        }
        String usernameValue = this.entityData.get(NAME);
        long configRevision = SmartNpcNamesConfig.getPlayerNpcNameEntriesRevision();
        if (this.cachedUsername == null
                || this.cachedUsernameConfigRevision != configRevision
                || !this.cachedUsernameValue.equals(usernameValue)) {
            this.cachedUsername = new FakePlayerName(usernameValue);
            this.cachedUsernameValue = usernameValue;
            this.cachedUsernameConfigRevision = configRevision;
        }
        return this.cachedUsername;
    }

    /** Assigns a configured identity without allowing persistent server state onto chunk workers. */
    protected boolean ensureConfiguredUsername() {
        if (this.hasUsername()) {
            return true;
        }
        FakePlayerName nextName = nextConfiguredName(this.getRandom(), this.level().getServer());
        if (nextName == null) {
            this.discard();
            return false;
        }
        this.setUsername(nextName);
        return true;
    }

    public static boolean hasConfiguredNames() {
        return !configuredNames().isEmpty();
    }

    public void setUsername(String username) {
        this.setUsername(new FakePlayerName(username));
    }

    public void setUsername(FakePlayerName username) {
        FakePlayerName newName = username;
        if (newName == null || newName.isInvalid()) {
            MinecraftServer server = this.level().getServer();
            if (server != null && !server.isSameThread()) {
                return;
            }
            newName = nextConfiguredName(this.getRandom(), this.level().getServer());
            if (newName == null) {
                return;
            }
        }
        FakePlayerName oldName = this.hasUsername() ? this.getUsername() : null;

        useName(newName);
        this.entityData.set(NAME, newName.getCombinedNames());
        this.invalidateCachedUsername();

        if (!Objects.equals(oldName, newName)) {
            this.profile = null;
            this.profileUpdateQueued = false;
            this.profileRefreshRequired = !this.level().isClientSide();
            this.profileRetryTicks = 0;
            this.clearTextureState();
            this.getProfile();
        }
    }

    public @Nullable GameProfile getProfile() {
        if (this.profile == null && this.hasUsername()) {
            String skinName = this.getUsername().getSkinName();
            GameProfile syncedProfile = this.entityData.get(PROFILE).partialProfile();
            this.profile = isResolvedProfileForName(syncedProfile, skinName)
                    ? syncedProfile
                    : new GameProfile(UUIDUtil.createOfflinePlayerUUID(skinName), skinName);
            if (!this.level().isClientSide() && !isCompleteProfile(this.profile)) {
                requestProfileUpdate(this);
            }
        }
        return this.profile;
    }

    public void setProfile(@Nullable GameProfile profile) {
        this.applyProfile(profile);
        this.profileRefreshRequired = false;
        this.profileRetryTicks = 0;
    }

    private void applyProfile(@Nullable GameProfile profile) {
        this.profile = profile;
        this.profileUpdateQueued = false;
        this.clearTextureState();
        if (!this.level().isClientSide() && profile != null) {
            this.entityData.set(PROFILE, ResolvableProfile.createResolved(profile));
        }
    }

    public boolean isTextureAvailable(MinecraftProfileTexture.Type type) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            return this.skinAvailable;
        }
        if (type == MinecraftProfileTexture.Type.ELYTRA) {
            return this.elytraAvailable;
        }
        return this.capeAvailable;
    }

    public @Nullable Identifier getTexture(MinecraftProfileTexture.Type type) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            return this.skin;
        }
        if (type == MinecraftProfileTexture.Type.ELYTRA) {
            return this.elytra;
        }
        return this.cape;
    }

    public void setTexture(MinecraftProfileTexture.Type type, Identifier location) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            this.skin = location;
            this.skinAvailable = true;
        } else if (type == MinecraftProfileTexture.Type.ELYTRA) {
            this.elytra = location;
            this.elytraAvailable = true;
        } else {
            this.cape = location;
            this.capeAvailable = true;
        }
    }

    private void clearTextureState() {
        this.skin = null;
        this.cape = null;
        this.elytra = null;
        this.skinAvailable = false;
        this.capeAvailable = false;
        this.elytraAvailable = false;
    }

    private static @Nullable FakePlayerName nextConfiguredName(RandomSource random, @Nullable MinecraftServer server) {
        synchronized (NAME_POOL) {
            List<FakePlayerName> configuredNames = configuredNames();
            if (configuredNames.isEmpty()) {
                return null;
            }

            Set<String> usedNameKeys = server == null
                    ? Set.of()
                    : PlayerNpcForceTickManager.livingNpcNameKeys(server);
            NAME_POOL.removeIf(name -> isNameUsed(name, usedNameKeys));
            if (NAME_POOL.isEmpty()) {
                List<FakePlayerName> shuffled = new ArrayList<>();
                for (FakePlayerName name : configuredNames) {
                    if (!isNameUsed(name, usedNameKeys)) {
                        shuffled.add(name);
                    }
                }
                Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
                NAME_POOL.addAll(shuffled);
            }
            return NAME_POOL.poll();
        }
    }

    private static void useName(FakePlayerName name) {
        synchronized (NAME_POOL) {
            configuredNames();
            NAME_POOL.removeIf(candidate -> sameName(candidate, name));
        }
    }

    public static String getRandomHardcodedName(RandomSource random) {
        FakePlayerName name = nextConfiguredName(random, null);
        return name == null ? "" : name.getCombinedNames();
    }

    public static boolean hasAvailableConfiguredName(MinecraftServer server) {
        List<FakePlayerName> configuredNames = configuredNames();
        if (configuredNames.isEmpty()) {
            return false;
        }

        Set<String> usedNameKeys = PlayerNpcForceTickManager.livingNpcNameKeys(server);
        for (FakePlayerName name : configuredNames) {
            if (!isNameUsed(name, usedNameKeys)) {
                return true;
            }
        }
        return false;
    }

    private static List<FakePlayerName> configuredNames() {
        synchronized (NAME_POOL) {
            long configRevision = SmartNpcNamesConfig.getPlayerNpcNameEntriesRevision();
            if (nameConfigInitialized && cachedNameConfigRevision == configRevision) {
                return cachedNames;
            }
            List<String> configuredEntries = SmartNpcNamesConfig.getPlayerNpcNameEntries();

            List<FakePlayerName> names = new ArrayList<>();
            Set<String> usedSkinNames = new HashSet<>();
            for (String configuredEntry : configuredEntries) {
                SmartNpcNamesConfig.parseNameEntry(configuredEntry).ifPresent(entry -> {
                    if (!usedSkinNames.add(normalizeName(entry.skinName()))) {
                        return;
                    }
                    PlayerNpcInterest[] interests = entry.interests().toArray(PlayerNpcInterest[]::new);
                    names.add(new FakePlayerName(entry.skinName(), entry.displayName(), interests));
                });
            }

            cachedNames = List.copyOf(names);
            cachedNameConfigRevision = configRevision;
            nameConfigInitialized = true;
            NAME_POOL.clear();
            return cachedNames;
        }
    }

    private void invalidateCachedUsername() {
        this.cachedUsername = null;
        this.cachedUsernameValue = "";
        this.cachedUsernameConfigRevision = Long.MIN_VALUE;
    }

    private static boolean isNameUsed(FakePlayerName name, Set<String> usedNameKeys) {
        return usedNameKeys.contains(normalizeName(name.getSkinName()))
                || usedNameKeys.contains(normalizeName(name.getDisplayName()));
    }

    private static boolean sameName(FakePlayerName first, FakePlayerName second) {
        return normalizeName(first.getSkinName()).equals(normalizeName(second.getSkinName()))
                || normalizeName(first.getDisplayName()).equals(normalizeName(second.getDisplayName()));
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static void requestProfileUpdate(FakePlayer entity) {
        if (entity.profileUpdateQueued) {
            return;
        }
        entity.profileUpdateQueued = true;
        PROFILE_QUEUE.add(entity);
        synchronized (PROFILE_LOCK) {
            if (profileThread == null || profileThread.getState() == Thread.State.TERMINATED) {
            profileThread = new Thread(FakePlayer::runProfileUpdates, "PlayerNpc FakePlayer Profile Updater");
                profileThread.setDaemon(true);
                profileThread.start();
            }
        }
    }

    private static void runProfileUpdates() {
        FakePlayer entity;
        while ((entity = PROFILE_QUEUE.poll()) != null) {
            GameProfile currentProfile = entity.profile;
            if (currentProfile == null) {
                entity.profileUpdateQueued = false;
                continue;
            }
            try {
                FakePlayer target = entity;
                MinecraftServer server = target.getServer();
                if (server != null) {
                    String requestedName = currentProfile.name();
                    GameProfile resolvedProfile = server.services().nameToIdCache().get(requestedName)
                            .map(nameAndId -> server.services().sessionService().fetchProfile(nameAndId.id(), true))
                            .map(result -> result.profile())
                            .orElse(null);
                    server.execute(() -> target.finishProfileUpdate(requestedName, resolvedProfile));
                } else {
                    target.profileUpdateQueued = false;
                }
            } catch (Exception ignored) {
                FakePlayer target = entity;
                MinecraftServer server = target.getServer();
                String requestedName = currentProfile.name();
                if (server != null) {
                    server.execute(() -> target.finishProfileUpdate(requestedName, null));
                } else {
                    target.profileUpdateQueued = false;
                }
            }
        }
    }

    private void finishProfileUpdate(String requestedName, @Nullable GameProfile resolvedProfile) {
        GameProfile latestProfile = this.profile;
        if (latestProfile == null || !requestedName.equalsIgnoreCase(latestProfile.name())) {
            return;
        }
        if (isCompleteProfile(resolvedProfile)) {
            this.setProfile(resolvedProfile);
            return;
        }
        this.profileUpdateQueued = false;
        this.profileRefreshRequired = true;
        this.profileRetryTicks = PROFILE_RETRY_INTERVAL_TICKS;
    }

    private static boolean isCompleteProfile(@Nullable GameProfile profile) {
        return profile != null
                && profile.id() != null
                && !StringUtil.isNullOrEmpty(profile.name())
                && !profile.id().equals(UUIDUtil.createOfflinePlayerUUID(profile.name()));
    }

    private static boolean isResolvedProfileForName(@Nullable GameProfile profile, String name) {
        return isCompleteProfile(profile)
                && profile.name().equalsIgnoreCase(name);
    }

    public static final class FakePlayerName {
        private final String skinName;
        private final String displayName;
        private final List<PlayerNpcInterest> interests;

        public FakePlayerName(String combinedName) {
            String[] names = combinedName == null ? new String[] {""} : combinedName.split(":", 2);
            this.skinName = names[0];
            this.displayName = names.length > 1 && !StringUtil.isNullOrEmpty(names[1]) ? names[1] : null;
            this.interests = configuredInterests(this.skinName);
        }

        public FakePlayerName(String skinName, String displayName) {
            this.skinName = skinName;
            this.displayName = StringUtil.isNullOrEmpty(displayName) ? null : displayName;
            this.interests = configuredInterests(this.skinName);
        }

        public FakePlayerName(String skinName, PlayerNpcInterest... interests) {
            this.skinName = skinName;
            this.displayName = null;
            this.interests = sanitizeInterests(interests);
        }

        public FakePlayerName(String skinName, String displayName, PlayerNpcInterest... interests) {
            this.skinName = skinName;
            this.displayName = StringUtil.isNullOrEmpty(displayName) ? null : displayName;
            this.interests = sanitizeInterests(interests);
        }

        public String getSkinName() {
            return this.skinName;
        }

        public String getDisplayName() {
            return StringUtil.isNullOrEmpty(this.displayName) ? this.skinName : this.displayName;
        }

        public String getCombinedNames() {
            if (StringUtil.isNullOrEmpty(this.displayName) || this.skinName.equals(this.displayName)) {
                return this.skinName;
            }
            return this.skinName + ":" + this.displayName;
        }

        public List<PlayerNpcInterest> getInterests() {
            return this.interests;
        }

        public boolean hasInterest(PlayerNpcInterest interest) {
            return this.interests.contains(interest);
        }

        public String getInterestDisplayText() {
            if (this.interests.isEmpty()) {
                return "";
            }
            List<String> names = new ArrayList<>(this.interests.size());
            for (PlayerNpcInterest interest : this.interests) {
                names.add(interest.displayName());
            }
            return String.join(", ", names);
        }

        private static List<PlayerNpcInterest> sanitizeInterests(PlayerNpcInterest... interests) {
            List<PlayerNpcInterest> result = new ArrayList<>();
            if (interests == null || interests.length == 0) {
                addInterests(result, DEFAULT_INTERESTS);
                return List.copyOf(result);
            }

            for (PlayerNpcInterest interest : interests) {
                addInterest(result, interest);
            }
            if (result.isEmpty()) {
                addInterests(result, DEFAULT_INTERESTS);
            }
            return List.copyOf(result);
        }

        private static List<PlayerNpcInterest> configuredInterests(String skinName) {
            if (!StringUtil.isNullOrEmpty(skinName)) {
                for (FakePlayerName name : configuredNames()) {
                    if (skinName.equalsIgnoreCase(name.skinName)) {
                        return name.interests;
                    }
                }
            }
            return DEFAULT_INTERESTS;
        }

        private static void addInterests(List<PlayerNpcInterest> result, List<PlayerNpcInterest> interests) {
            for (PlayerNpcInterest interest : interests) {
                addInterest(result, interest);
            }
        }

        private static void addInterest(List<PlayerNpcInterest> result, PlayerNpcInterest interest) {
            if (interest != null && !result.contains(interest)) {
                result.add(interest);
            }
        }

        public boolean isInvalid() {
            return StringUtil.isNullOrEmpty(this.skinName);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof FakePlayerName other && this.getCombinedNames().equals(other.getCombinedNames());
        }

        @Override
        public int hashCode() {
            return this.getCombinedNames().hashCode();
        }

        @Override
        public String toString() {
            return this.getCombinedNames();
        }
    }
}
