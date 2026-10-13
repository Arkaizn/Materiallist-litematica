package materiallist.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import materiallist.Net;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client part (optional): reports the active Litematica placements and build progress to the server, uploads unknown schematics
 * and shows a button for collection chests in the chest screen. Does nothing if the server doesn't run Materiallist.
 */
public class MaterialSync implements ClientModInitializer {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long MAX_FILE = 64L << 20;

    private final Path dir = FabricLoader.getInstance().getConfigDir().resolve("materiallist-sync");
    private final Path oldDir = FabricLoader.getInstance().getConfigDir().resolve("materialliste-sync");   // before the rename
    private final boolean litematica = FabricLoader.getInstance().isModLoaded("litematica");

    private String worldId;
    private Map<String, LitematicaBridge.Cache> placements = new HashMap<>();
    private Map<String, String> marks = new HashMap<>();   // "x,y,z" -> placement (only for the button label)
    private boolean dirty;
    private Screen lastScreen;
    private BlockPos openPos, lastHit;
    private int tick;
    private String lastBody = "";
    private long lastSent;
    private final Deque<Net.ToServer> queue = new ArrayDeque<>();
    private final Map<String, Boolean> warned = new HashMap<>();

    @Override
    public void onInitializeClient() {
        Net.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ScreenEvents.AFTER_INIT.register(this::addButton);
        ClientPlayNetworking.registerGlobalReceiver(Net.ToClient.TYPE, (msg, ctx) -> ctx.client().execute(() -> onMessage(msg)));
    }

    private static boolean connected() { return ClientPlayNetworking.canSend(Net.ToServer.TYPE); }
    private void warn(String what, Throwable t) { if (warned.put(what + t, true) == null) { System.err.println("[materiallist] " + what + ": " + t); t.printStackTrace(); } }

    private void onTick(Minecraft mc) {
        try {
            if (mc.player == null || mc.level == null) { leaveWorld(); return; }
            if (worldId == null) enterWorld(mc);
            Screen screen = mc.gui.screen();
            if (screen == null) {
                HitResult hit = mc.hitResult;
                lastHit = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos().immutable() : null;
                openPos = null; lastScreen = null;
            }
            tick++;
            for (int i = 0; i < 2 && !queue.isEmpty(); i++) ClientPlayNetworking.send(queue.poll());   // file upload in small portions
            if (tick % 200 == 0 && dirty) saveWorld();
            if (tick % 100 == 0 && litematica && connected()) sendState(mc);
        } catch (Throwable t) { warn("Error in tick", t); }
    }

    private void enterWorld(Minecraft mc) {
        String id = mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null ? "sp_" + mc.getSingleplayerServer().getWorldData().getLevelName()
            : "mp_" + (mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "server");
        worldId = id.replaceAll("[^A-Za-z0-9._-]", "_");
        placements = new HashMap<>(); marks = new HashMap<>();
        try {
            if (Files.isDirectory(oldDir) && !Files.exists(dir)) Files.move(oldDir, dir);
            Path f = dir.resolve(worldId + ".json");
            if (Files.exists(f)) {
                JsonObject o = GSON.fromJson(Files.readString(f), JsonObject.class);
                if (o.has("placements")) placements = GSON.fromJson(o.get("placements"), new TypeToken<HashMap<String, LitematicaBridge.Cache>>() { }.getType());
                if (o.has("chests")) marks = GSON.fromJson(o.get("chests"), new TypeToken<HashMap<String, String>>() { }.getType());
            }
        } catch (Exception e) { System.err.println("[materiallist] Saved state is not readable: " + e); }
        if (placements == null) placements = new HashMap<>();
        if (marks == null) marks = new HashMap<>();
        lastBody = ""; tick = 0; dirty = false; queue.clear();
    }

    private void leaveWorld() {
        if (worldId == null) return;
        saveWorld();
        worldId = null; lastScreen = null; openPos = null; lastHit = null; queue.clear();
    }

    private void saveWorld() {
        if (worldId == null) return;
        try {
            Files.createDirectories(dir);
            JsonObject o = new JsonObject();
            o.add("placements", GSON.toJsonTree(placements));
            o.add("chests", GSON.toJsonTree(marks));
            Files.writeString(dir.resolve(worldId + ".json"), GSON.toJson(o));
            dirty = false;
        } catch (Exception e) { System.err.println("[materiallist] Saving failed: " + e); }
    }

    /* ---------- Litematica -> Server ---------- */
    private void sendState(Minecraft mc) {
        JsonArray pl = new JsonArray();
        try { if (LitematicaBridge.scan(mc, placements, pl)) dirty = true; } catch (Throwable t) { warn("Litematica sync failed", t); return; }
        if (pl.size() == 0) return;
        String all = new Gson().toJson(pl);
        long now = System.currentTimeMillis();
        if (all.equals(lastBody) && now - lastSent < 60_000) return;
        lastBody = all; lastSent = now;
        for (JsonElement e : pl) {   // each placement separately so a packet never gets too big
            JsonObject body = new JsonObject();
            JsonArray one = new JsonArray();
            one.add(e);
            body.add("placements", one);
            byte[] data = new Gson().toJson(body).getBytes(StandardCharsets.UTF_8);
            if (data.length <= 28_000) ClientPlayNetworking.send(new Net.ToServer("state", data));
        }
    }

    private void onMessage(Net.ToClient msg) {
        try {
            if (!msg.kind().equals("want") || !litematica || !queue.isEmpty()) return;
            String key = new String(msg.data(), StandardCharsets.UTF_8);
            Path file = LitematicaBridge.fileOf(key);
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE) return;
            byte[] bytes = Files.readAllBytes(file);
            int total = Math.max(1, (bytes.length + Net.CHUNK - 1) / Net.CHUNK);
            for (int i = 0; i < total; i++) {
                JsonObject h = new JsonObject();
                h.addProperty("key", key); h.addProperty("name", key.substring(0, key.indexOf('|'))); h.addProperty("file", file.getFileName().toString());
                h.addProperty("index", i); h.addProperty("total", total);
                byte[] head = new Gson().toJson(h).getBytes(StandardCharsets.UTF_8);
                byte[] part = Arrays.copyOfRange(bytes, i * Net.CHUNK, Math.min(bytes.length, (i + 1) * Net.CHUNK));
                byte[] data = new byte[4 + head.length + part.length];
                data[0] = (byte) (head.length >>> 24); data[1] = (byte) (head.length >>> 16); data[2] = (byte) (head.length >>> 8); data[3] = (byte) head.length;
                System.arraycopy(head, 0, data, 4, head.length);
                System.arraycopy(part, 0, data, 4 + head.length, part.length);
                queue.add(new Net.ToServer("file", data));
            }
        } catch (Throwable t) { warn("Schematic could not be sent", t); }
    }

    /* ---------- Button in the chest screen ---------- */
    private void addButton(Minecraft mc, Screen screen, int width, int height) {
        try {
            if (mc.player == null || mc.level == null || !litematica || !connected()) return;
            if (!(screen instanceof AbstractContainerScreen<?> acs) || acs.getMenu() instanceof InventoryMenu) return;
            if (screen != lastScreen) {
                openPos = lastHit != null && mc.level.getBlockEntity(lastHit) instanceof Container ? lastHit : null;
                if (openPos != null) {
                    // Double chests: both halves share one mark, kept under the same half the server uses
                    BlockPos half = otherHalf(mc.level, openPos);
                    if (half != null) {
                        BlockPos main = half.asLong() < openPos.asLong() ? half : openPos, side = main == half ? openPos : half;
                        String moved = marks.remove(coords(side));
                        if (moved != null) { marks.putIfAbsent(coords(main), moved); dirty = true; }
                        openPos = main;
                    }
                }
                lastScreen = screen;
            }
            if (openPos == null) return;
            final BlockPos pos = openPos;
            final String key = coords(pos);
            Button button = Button.builder(Component.literal(label(key)), b -> {
                List<String[]> opts = LitematicaBridge.active();
                String cur = marks.get(key);
                int i = -1;
                for (int n = 0; n < opts.size(); n++) if (opts.get(n)[0].equals(cur)) i = n;
                JsonObject o = new JsonObject();
                o.addProperty("x", pos.getX()); o.addProperty("y", pos.getY()); o.addProperty("z", pos.getZ());
                if (i + 1 < opts.size()) {
                    String next = opts.get(i + 1)[0];
                    marks.put(key, next);
                    o.addProperty("name", opts.get(i + 1)[1]); o.addProperty("file", next.substring(next.indexOf('|') + 1));
                } else marks.remove(key);
                dirty = true;
                ClientPlayNetworking.send(new Net.ToServer("mark", new Gson().toJson(o).getBytes(StandardCharsets.UTF_8)));
                b.setMessage(Component.literal(label(key)));
            }).bounds(width / 2 - 88, 2, 176, 18)
              .tooltip(Tooltip.create(Component.literal("Click to choose which Litematica placement this chest collects material for. Only collection chests count for Materiallist.")))
              .build();
            Screens.getWidgets(screen).add(button);
        } catch (Throwable t) { warn("Button could not be shown", t); }
    }

    private static String coords(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }
    /** The other half of a double chest, null for everything else */
    private static BlockPos otherHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
            return pos.relative(ChestBlock.getConnectedDirection(state)).immutable();
        return null;
    }

    private String label(String key) {
        String m = marks.get(key);
        if (m == null) return "Materiallist: not counted";
        String name = m.contains("|") ? m.substring(0, m.indexOf('|')) : m;
        return "§aCollection chest: " +
 (name.length() > 20 ? name.substring(0, 19) + "…" : name);
    }
}
