package materiallist;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Messages between client mod and server mod. Runs over the normal game connection, so it needs no address and no password. */
public final class Net {
    /** Size of one file chunk when uploading a schematic */
    public static final int CHUNK = 24_000;

    /** Client -> Server. kind: "state" (JSON with placements), "mark" (JSON: set collection chest), "file" (JSON header + file chunk) */
    public record ToServer(String kind, byte[] data) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(Identifier.fromNamespaceAndPath("materiallist", "to_server"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToServer> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32), ToServer::kind, ByteBufCodecs.byteArray(30_000), ToServer::data, ToServer::new);
        @Override public Type<ToServer> type() { return TYPE; }
    }

    /** Server -> Client. kind: "want" (key of a placement whose file the server wants) */
    public record ToClient(String kind, byte[] data) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(Identifier.fromNamespaceAndPath("materiallist", "to_client")
);
        public static final StreamCodec<RegistryFriendlyByteBuf, ToClient> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32), ToClient::kind, ByteBufCodecs.byteArray(30_000), ToClient::data, ToClient::new);
        @Override public Type<ToClient> type() { return TYPE; }
    }

    private static boolean registered;
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.serverboundPlay().register(ToServer.TYPE, ToServer.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ToClient.TYPE, ToClient.CODEC);
    }

    private Net() { }
}
