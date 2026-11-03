package com.pla.smart_npc.network;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public class PlayerNpcInspectorPacket implements CustomPacketPayload {
    public static final Type<PlayerNpcInspectorPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("smart_npc", "inspector"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerNpcInspectorPacket> STREAM_CODEC = StreamCodec.ofMember(PlayerNpcInspectorPacket::encode, PlayerNpcInspectorPacket::decode);
    public static final int OVERALL_ENTITY_ID = -2;
    private final int entityId;
    private final List<ItemStack> items;
    private final String buildStatusText;
    private final String performanceText;
    private final String dailyJobText;
    private final String requirementsText;
    private final String aiResourceText;
    private final TeamInfo teamInfo;
    private final boolean traceEnabled;

    public enum TeamRole {
        NONE,
        LEADER,
        NPC_FOLLOWER,
        PLAYER_FOLLOWER
    }

    public record TeamInfo(String teamName, String leaderName, TeamRole role) {
        public TeamInfo {
            teamName = teamName == null ? "" : teamName;
            leaderName = leaderName == null ? "" : leaderName;
            role = role == null ? TeamRole.NONE : role;
        }

        public static TeamInfo none() {
            return new TeamInfo("", "", TeamRole.NONE);
        }
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items) {
        this(entityId, items, "");
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText) {
        this(entityId, items, buildStatusText, "");
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText, String performanceText) {
        this(entityId, items, buildStatusText, performanceText, false);
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText, String performanceText, boolean traceEnabled) {
        this(entityId, items, buildStatusText, performanceText, "", traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String requirementsText,
            boolean traceEnabled
    ) {
        this(entityId, items, buildStatusText, performanceText, "", requirementsText, "", traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String dailyJobText,
            String requirementsText,
            boolean traceEnabled
    ) {
        this(entityId, items, buildStatusText, performanceText, dailyJobText, requirementsText, "", traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String dailyJobText,
            String requirementsText,
            TeamInfo teamInfo,
            boolean traceEnabled
    ) {
        this(entityId, items, buildStatusText, performanceText, dailyJobText, requirementsText, "", teamInfo, traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String dailyJobText,
            String requirementsText,
            String aiResourceText,
            boolean traceEnabled
    ) {
        this(entityId, items, buildStatusText, performanceText, dailyJobText, requirementsText,
                aiResourceText, TeamInfo.none(), traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String dailyJobText,
            String requirementsText,
            String aiResourceText,
            TeamInfo teamInfo,
            boolean traceEnabled
    ) {
        this.entityId = entityId;
        this.items = List.copyOf(items);
        this.buildStatusText = buildStatusText == null ? "" : buildStatusText;
        this.performanceText = performanceText == null ? "" : performanceText;
        this.dailyJobText = dailyJobText == null ? "" : dailyJobText;
        this.requirementsText = requirementsText == null ? "" : requirementsText;
        this.aiResourceText = aiResourceText == null ? "" : aiResourceText;
        this.teamInfo = teamInfo == null ? TeamInfo.none() : teamInfo;
        this.traceEnabled = traceEnabled;
    }

    public static PlayerNpcInspectorPacket clear() {
        return new PlayerNpcInspectorPacket(-1, List.of());
    }

    public int entityId() {
        return entityId;
    }

    public List<ItemStack> items() {
        return items;
    }

    public String buildStatusText() {
        return buildStatusText;
    }

    public String performanceText() {
        return performanceText;
    }

    public static PlayerNpcInspectorPacket overall(String aiResourceText) {
        return new PlayerNpcInspectorPacket(OVERALL_ENTITY_ID, List.of(), "", "", "", "", aiResourceText, false);
    }

    public String dailyJobText() {
        return dailyJobText;
    }

    public String requirementsText() {
        return requirementsText;
    }

    public boolean traceEnabled() {
        return traceEnabled;
    }

    public String aiResourceText() {
        return aiResourceText;
    }

    public TeamInfo teamInfo() {
        return teamInfo;
    }

    public void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityId);
        buffer.writeVarInt(this.items.size());
        for (ItemStack stack : this.items) {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, stack);
        }
        buffer.writeUtf(this.buildStatusText);
        buffer.writeUtf(this.performanceText);
        buffer.writeUtf(this.dailyJobText);
        buffer.writeUtf(this.requirementsText);
        buffer.writeUtf(this.aiResourceText);
        buffer.writeUtf(this.teamInfo.teamName());
        buffer.writeUtf(this.teamInfo.leaderName());
        buffer.writeByte(this.teamInfo.role().ordinal());
        buffer.writeBoolean(this.traceEnabled);
    }

    public static PlayerNpcInspectorPacket decode(RegistryFriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
        }
        String buildStatusText = buffer.readUtf();
        String performanceText = buffer.readUtf();
        String dailyJobText = buffer.readUtf();
        String requirementsText = buffer.readUtf();
        String aiResourceText = buffer.readUtf();
        String teamName = buffer.readUtf();
        String leaderName = buffer.readUtf();
        int roleIndex = buffer.readUnsignedByte();
        TeamRole[] roles = TeamRole.values();
        TeamRole role = roleIndex < roles.length ? roles[roleIndex] : TeamRole.NONE;
        boolean traceEnabled = buffer.readBoolean();
        return new PlayerNpcInspectorPacket(entityId, items, buildStatusText, performanceText,
                dailyJobText, requirementsText, aiResourceText, new TeamInfo(teamName, leaderName, role), traceEnabled);
    }

    public static void handle(PlayerNpcInspectorPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> SmartNpcInspectorOverlay.handlePacket(packet));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
