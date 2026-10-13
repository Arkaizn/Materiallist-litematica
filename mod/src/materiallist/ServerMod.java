package materiallist;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import materiallist.web.Litematic;
import materiallist.web.Store;
import materiallist.web.Store.User;
import materiallist.web.WebServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Server part (runs on the Fabric server and in singleplayer): starts the website, counts the collection chests
 * and receives data from players with the client mod. Players without the mod can use everything except automatic build progress.
 */
public class ServerMod implements ModInitializer {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = "§a[Materiallist]§r ";

    private Config cfg;
    private Store store;
    private WebServer web;
    private MinecraftServer server;
    private int tick;
    private volatile User localUser;
    /** running file uploads from the game: player -> buffer */
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private final Map<UUID, Long> asked = new HashMap<>();

    private static class Upload { String key, name, file; int next, total; ByteArrayOutputStream data = new ByteArrayOutputStream(); }

    @Override
    public void onInitialize() {
        Net.register();
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> stop());
        ServerTickEvents.END_SERVER_TICK.register(this::onTick);
        ServerPlayNetworking.registerGlobalReceiver(Net.ToServer.TYPE, (msg, ctx) -> ctx.server().execute(() -> receive(ctx.player(), msg)));
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("materiallist")
            .executes(c -> status(c.getSource()))
            .then(Commands.literal("login").executes(c -> login(c.getSource())))
            .then(Commands.literal("chest")
                .then(Commands.literal("off").executes(c -> chest(c.getSource(), null)))
                .then(Commands.argument("project", StringArgumentType.greedyString())
                    .suggests((c, b) -> { ServerPlayer p = c.getSource().getPlayer(); if (p != null && store != null) for (JsonObject pr : store.accessible(user(p))) b.suggest(pr.get("name").getAsString()); return b.buildFuture(); })
                    .executes(c -> chest(c.getSource(), StringArgumentType.getString(c, "project")))))
            .then(Commands.literal("weblogin").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("player", StringArgumentType.word()).executes(this::adminLogin)))));
    }

    private void start(MinecraftServer s) {
        try {
            server = s;
            Path cfgDir = FabricLoader.getInstance().getConfigDir(), gameDir = FabricLoader.getInstance().getGameDir();
            migrate(cfgDir.resolve("materialliste.json"), cfgDir.resolve("materiallist.json"));
            migrate(gameDir.resolve("materialliste"), gameDir.resolve("materiallist"));
            cfg = Config.load(cfgDir.resolve("materiallist.json"));
            Path dir = gameDir.resolve("materiallist");
            store = new Store(dir);
            // In singleplayer only for the local machine, and whoever sits there is logged in automatically
            boolean single = !s.isDedicatedServer();
            localUser = single && s.getSingleplayerProfile() != null ? new User(s.getSingleplayerProfile().id(), s.getSingleplayerProfile().name()) : null;
            web = new WebServer(cfg, store, single ? "127.0.0.1" : cfg.bind, () -> localUser);
            System.out.println("[materiallist] Website running at " + cfg.url() + " (port " + cfg.port + (single ? ", local only" : ", address " + cfg.bind) + "), data in " + dir);
            if (!single && cfg.publicUrl.isBlank()) System.out.println("[materiallist] Tip: set publicUrl in config/materiallist.json to the address players use to reach the site (e.g. http://your.server:" + cfg.port + ").");
        } catch (Exception e) {
            System.err.println("[materiallist] The website could not be started: " + e);
            web = null;
        }
    }

    /** Data from before the rename (Materialliste -> Materiallist-Litematica): move it over once */
    private static void migrate(Path from, Path to) {
        try {
            if (Files.exists(from) && !Files.exists(to)) { Files.move(from, to); System.out.println("[materiallist] Moved " + from + " to " + to); }
        } catch (Exception e) { System.err.println("[materiallist] Could not move " + from + " to " + to + ": " + e); }
    }

    private void stop() {
        if (web != null) web.stop();
        if (store != null) store.saveMarks();
        web = null; store = null; server = null; localUser = null;
        uploads.clear(); asked.clear();
    }

    private static User user(ServerPlayer p) { return new User(p.getUUID(), p.getGameProfile().name()); }
    private static void tell(CommandSourceStack src, String text) { src.sendSuccess(() -> Component.literal(PREFIX + text), false); }

    /* ---------- Commands ---------- */
    private int status(CommandSourceStack src) {
        if (store == null) { src.sendFailure(Component.literal("Materiallist is not running (see server log).")); return 0; }
        ServerPlayer p = src.getPlayer();
        tell(src, "Site: " + cfg.url() + (p == null ? "" : " · " + store.accessible(user(p)).size() + " projects") + "\nLog in: /materiallist login · Collection chest: look at a chest and run /materiallist chest <project>");
        return 1;
    }

    private Component link(String url, String label) {
        return Component.literal(PREFIX).append(Component.literal(label).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    private int login(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (store == null || p == null) { src.sendFailure(Component.literal("Only for players, while Materiallist is running.")); return 0; }
        String url = cfg.url() + "/login?t=" + store.createLogin(user(p));
        src.sendSuccess(() -> link(url, "Click here to open Materiallist (valid for 5 minutes, only for you)"), false);
        return 1;
    }

    /** For the console or server owners: create a login link for a player */
    private int adminLogin(CommandContext<CommandSourceStack> c) {
        if (store == null) return 0;
        String name = StringArgumentType.getString(c, "player");
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        User known = store.knownUser(name);
        User u = online != null ? user(online) : known != null ? known : new User(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        tell(c.getSource(), "Login link for " + u.name() + ": " + cfg.url() + "/login?t=" + store.createLogin(u));
        return 1;
    }

    private int chest(CommandSourceStack src, String projectName) {
        ServerPlayer p = src.getPlayer();
        if (store == null || p == null) return 0;
        HitResult hit = p.pick(6.0, 1f, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) { src.sendFailure(Component.literal("Look at a chest (at most 6 blocks away).")); return 0; }
        String err = mark(p, b.getBlockPos(), projectName, null);
        if (err != null) { src.sendFailure(Component.literal(err)); return 0; }
        tell(src, projectName == null ? "This chest no longer counts." : "This chest is now a collection chest for \"" + projectName + "\".");
        return 1;
    }

    /* ---------- Collection chests ---------- */
    private static String dimension(ServerLevel l) { return l.dimension().identifier().toString(); }

    /** The container at this position (double chests as a whole), null if there is none */
    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock cb) return ChestBlock.getContainer(cb, state, level, pos, true);
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }
    /** Double chests: always use the same half as the key */
    private static BlockPos canonical(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (other.asLong() < pos.asLong()) return other;
        }
        return pos;
    }

    /**
     * Set or remove a collection chest. The project is looked up by name (or schematic file name) among the player's projects.
     * Returns an error message, null on success.
     */
    private String mark(ServerPlayer player, BlockPos pos, String projectName, String file) {
        ServerLevel level = player.level();
        if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100) return "The chest is too far away.";
        if (containerAt(level, pos) == null) return "That is not a container.";
        String key = dimension(level) + "|" + coords(canonical(level, pos));
        User u = user(player);
        synchronized (store) {
            String current = store.markOf(key);
            if (current != null) {
                JsonObject cur = store.get(current);
                if (cur != null && !Store.canAccess(cur, u)) return "This chest belongs to a project you don't have access to.";
            }
            if (projectName == null) { store.setMark(key, null); return null; }
            JsonObject target = find(u, projectName, file);
            if (target == null) return "No project named \"" + projectName + "\" found. You can see your projects on the website.";
            store.setMark(key, target.get("id").getAsString());
        }
        tick = -1;   // count right away
        return null;
    }
    private static String coords(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceFirst("\\.litematic$", "").replaceAll("[\\s_\\-]+", " ").strip(); }

    /** The player's project with this name or this schematic file name */
    private JsonObject find(User u, String name, String file) {
        String n = norm(name), f = norm(file);
        JsonObject byName = null;
        for (JsonObject p : store.accessible(u)) {
            String pf = p.has("source") && p.getAsJsonObject("source").has("filename") ? norm(p.getAsJsonObject("source").get("filename").getAsString()) : "";
            if (!f.isEmpty() && f.equals(pf)) return p;
            if (byName == null && !n.isEmpty() && !n.equals("unnamed") && (n.equals(norm(p.get("name").getAsString())) || n.equals(pf))) byName = p;
        }
        return byName;
    }

    private static void add(Map<String, Integer> counts, String id, int n) { if (n > 0) counts.merge(id.replaceFirst("^minecraft:", ""), n, Integer::sum); }
    /** Count an item, including the contents of shulker boxes and bundles */
    private static void add(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        add(counts, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
        ItemContainerContents box = stack.get(DataComponents.CONTAINER);
        if (box != null) for (ItemStackTemplate t : box.nonEmptyItems()) add(counts, BuiltInRegistries.ITEM.getKey(t.item().value()).toString(), t.count() * stack.getCount());
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) for (ItemStackTemplate t : bundle.items()) add(counts, BuiltInRegistries.ITEM.getKey(t.item().value()).toString(), t.count() * stack.getCount());
    }

    private void onTick(MinecraftServer s) {
        if (store == null) return;
        if (s.isSingleplayer() && localUser == null && s.getSingleplayerProfile() != null) localUser = new User(s.getSingleplayerProfile().id(), s.getSingleplayerProfile().name());
        if (++tick % (Math.max(2, cfg.syncSeconds) * 20) != 0) return;
        try { countChests(s); } catch (Throwable t) { System.err.println("[materiallist] Counting the collection chests failed: " + t); }
    }

    /** Re-reads all collection chests in loaded chunks and writes the stock into the projects */
    private void countChests(MinecraftServer s) {
        Map<String, ServerLevel> levels = new HashMap<>();
        for (ServerLevel l : s.getAllLevels()) levels.put(dimension(l), l);
        synchronized (store) {
            boolean changed = false;
            for (var e : store.markTargets().entrySet()) {
                String key = e.getKey();
                if (store.get(e.getValue()) == null) { store.setMark(key, null); continue; }
                int bar = key.lastIndexOf('|');
                ServerLevel level = levels.get(key.substring(0, bar));
                String[] c = key.substring(bar + 1).split(",");
                BlockPos pos = new BlockPos(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
                if (level == null || !level.isLoaded(pos)) continue;   // not loaded: the last known contents stay
                Container box = containerAt(level, pos);
                if (box == null) { store.setMark(key, null); continue; }   // chest was broken
                Map<String, Integer> counts = new TreeMap<>();
                for (int i = 0; i < box.getContainerSize(); i++) add(counts, box.getItem(i));
                changed |= store.setMarkItems(key, counts);
            }
            if (changed) store.saveMarks();
            // Stock per project: collection chests, optionally plus the inventories of members who are online
            Map<String, Boolean> seen = new HashMap<>();
            for (String id : store.markTargets().values()) seen.put(id, true);
            for (String id : store.syncedIds()) seen.put(id, true);   // also projects whose last collection chest was just removed
            for (ServerPlayer p : s.getPlayerList().getPlayers()) for (JsonObject pr : store.accessible(user(p))) if (pr.has("countInventory") && pr.get("countInventory").getAsBoolean()) seen.put(pr.get("id").getAsString(), true);
            for (String id : seen.keySet()) {
                JsonObject pr = store.get(id);
                if (pr == null) continue;
                Map<String, Integer> stock = store.markStock(id);
                if (pr.has("countInventory") && pr.get("countInventory").getAsBoolean()) for (ServerPlayer p : s.getPlayerList().getPlayers()) if (Store.canAccess(pr, user(p))) {
                    Inventory inv = p.getInventory();
                    for (int i = 0; i < inv.getContainerSize(); i++) add(stock, inv.getItem(i));
                }
                JsonObject o = new JsonObject();
                stock.forEach(o::addProperty);
                store.applySync(pr, o, null, null);
            }
        }
    }

    /* ---------- Messages from the client mod ---------- */
    private void receive(ServerPlayer player, Net.ToServer msg) {
        if (store == null || web == null) return;
        try {
            JsonObject o = msg.kind().equals("file") ? null : GSON.fromJson(new String(msg.data(), StandardCharsets.UTF_8), JsonObject.class);
            switch (msg.kind()) {
                case "state" -> state(player, o);
                case "mark" -> {
                    String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : null;
                    String err = mark(player, new BlockPos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt()), name, o.has("file") ? o.get("file").getAsString() : null);
                    if (err != null) player.sendSystemMessage(Component.literal(PREFIX + "§c" + err), true);
                }
                case "file" -> file(player, msg.data());
                default -> { }
            }
        } catch (Exception e) { /* broken or forged message: ignore */ }
    }

    /** A player's active Litematica placements: record build progress, request unknown schematics */
    private void state(ServerPlayer player, JsonObject o) {
        User u = user(player);
        for (JsonElement e : o.getAsJsonArray("placements")) {
            JsonObject pl = e.getAsJsonObject();
            String name = pl.get("name").getAsString(), file = pl.has("file") ? pl.get("file").getAsString() : "", key = pl.get("key").getAsString();
            JsonObject target;
            synchronized (store) {
                target = find(u, name, file);
                if (target != null) {
                    Map<String, Long> placed = new LinkedHashMap<>();
                    for (JsonElement pe : pl.getAsJsonArray("placed")) {
                        JsonObject b = pe.getAsJsonObject();
                        Map<String, String> props = new HashMap<>();
                        if (b.has("props")) for (var kv : b.getAsJsonObject("props").entrySet()) props.put(kv.getKey(), kv.getValue().getAsString());
                        Litematic.blockToItems(b.get("name").getAsString(), props, Math.max(0, Math.min(100_000_000, b.get("count").getAsInt())), placed);
                    }
                    JsonObject pj = new JsonObject();
                    placed.forEach((k, v) -> { String id = Store.cleanId(k); if (id != null) pj.addProperty(id, v); });
                    store.applySync(target, target.has("sync") && target.getAsJsonObject("sync").has("stock") ? target.getAsJsonObject("sync").getAsJsonObject("stock") : new JsonObject(), pj, u.name());
                }
            }
            // No project for it yet: fetch the file from the client and create one (ask at most every 30 s)
            long now = System.currentTimeMillis();
            if (target == null && cfg.autoCreateFromLitematica && !file.isEmpty() && !uploads.containsKey(u.uuid()) && now - asked.getOrDefault(u.uuid(), 0L) > 30_000
                && ServerPlayNetworking.canSend(player, Net.ToClient.TYPE)) {
                asked.put(u.uuid(), now);
                ServerPlayNetworking.send(player, new Net.ToClient("want", key.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    /** One chunk of a schematic file. Layout: 4 bytes header length, JSON header {key, name, file, index, total}, then the data. */
    private void file(ServerPlayer player, byte[] data) throws Exception {
        UUID id = player.getUUID();
        int hl = ((data[0] & 255) << 24) | ((data[1] & 255) << 16) | ((data[2] & 255) << 8) | (data[3] & 255);
        JsonObject h = GSON.fromJson(new String(data, 4, hl, StandardCharsets.UTF_8), JsonObject.class);
        int index = h.get("index").getAsInt(), total = h.get("total").getAsInt();
        Upload up = uploads.get(id);
        if (index == 0) {
            if (total < 1 || (long) total * Net.CHUNK > ((long) cfg.maxUploadMb << 20) + Net.CHUNK) return;
            up = new Upload();
            up.key = h.get("key").getAsString(); up.name = h.get("name").getAsString(); up.file = h.get("file").getAsString(); up.total = total;
            uploads.put(id, up);
        }
        if (up == null || index != up.next || total != up.total || !up.key.equals(h.get("key").getAsString())) { uploads.remove(id); return; }
        up.data.write(data, 4 + hl, data.length - 4 - hl);
        up.next++;
        if (up.next < up.total) return;
        uploads.remove(id);
        final Upload done = up;
        final User u = user(player);
        final WebServer site = web;
        // Parse in the background so the server doesn't lag
        Thread t = new Thread(() -> {
            try {
                byte[] bytes = done.data.toByteArray();
                Litematic.Parsed r = Litematic.parse(bytes);
                String fn = done.file.replaceAll("[\\\\/\\x00-\\x1f]", "_");
                JsonObject src = new JsonObject();
                src.addProperty("type", "litematic"); src.addProperty("filename", fn.length() > 150 ? fn.substring(0, 150) : fn); src.add("meta", r.meta());
                site.createProject(u, done.name.isBlank() ? fn.replaceFirst("\\.[^.]+$", "") : done.name, r.items(), src, bytes);
                System.out.println("[materiallist] Created project \"" + done.name + "\" from the Litematica placement of " + u.name());
            } catch (Exception e) { System.err.println("[materiallist] Schematic from " + u.name() + " could not be imported: " + e.getMessage()); }
        }, "materiallist-import");

        t.setDaemon(true);
        t.start();
    }
}
