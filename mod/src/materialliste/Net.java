package materialliste;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Nachrichten zwischen Client-Mod und Server-Mod. Läuft über die normale Spielverbindung, braucht also keine Adresse und kein Passwort. */
public final class Net {
    /** Grösse eines Datei-Stücks beim Hochladen einer Schematic */
    public static final int CHUNK = 24_000;

    /** Client -> Server. kind: "state" (JSON mit Platzierungen), "mark" (JSON: Sammelkiste setzen), "file" (JSON-Kopf + Datei-Stück) */
    public record ToServer(String kind, byte[] data) implements CustomPacketPayload {
        public static final Type<ToServer> TYPE = new Type<>(Identifier.fromNamespaceAndPath("materialliste", "to_server"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ToServer> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(32), ToServer::kind, ByteBufCodecs.byteArray(30_000), ToServer::data, ToServer::new);
        @Override public Type<ToServer> type() { return TYPE; }
    }

    /** Server -> Client. kind: "want" (Schlüssel einer Platzierung, deren Datei der Server haben möchte) */
    public record ToClient(String kind, byte[] data) implements CustomPacketPayload {
        public static final Type<ToClient> TYPE = new Type<>(Identifier.fromNamespaceAndPath("materialliste", "to_client"));
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
