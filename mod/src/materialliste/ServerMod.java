package materialliste;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import materialliste.web.Litematic;
import materialliste.web.Store;
import materialliste.web.Store.User;
import materialliste.web.WebServer;
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
 * Server-Teil (läuft auf dem Fabric-Server und im Singleplayer): startet die Webseite, zählt die Sammelkisten
 * und nimmt Daten von Spielern mit Client-Mod entgegen. Spieler ohne Mod können alles ausser dem automatischen Baufortschritt nutzen.
 */
public class ServerMod implements ModInitializer {
    private static final Gson GSON = new Gson();
    private static final String PREFIX = "§a[Materialliste]§r ";

    private Config cfg;
    private Store store;
    private WebServer web;
    private MinecraftServer server;
    private int tick;
    private volatile User localUser;
    /** laufende Datei-Uploads aus dem Spiel: Spieler -> Puffer */
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
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> d.register(Commands.literal("materialliste")
            .executes(c -> status(c.getSource()))
            .then(Commands.literal("login").executes(c -> login(c.getSource())))
            .then(Commands.literal("kiste")
                .then(Commands.literal("aus").executes(c -> chest(c.getSource(), null)))
                .then(Commands.argument("projekt", StringArgumentType.greedyString())
                    .suggests((c, b) -> { ServerPlayer p = c.getSource().getPlayer(); if (p != null && store != null) for (JsonObject pr : store.accessible(user(p))) b.suggest(pr.get("name").getAsString()); return b.buildFuture(); })
                    .executes(c -> chest(c.getSource(), StringArgumentType.getString(c, "projekt")))))
            .then(Commands.literal("weblogin").requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.argument("spieler", StringArgumentType.word()).executes(this::adminLogin)))));
    }

    private void start(MinecraftServer s) {
        try {
            server = s;
            cfg = Config.load(FabricLoader.getInstance().getConfigDir().resolve("materialliste.json"));
            Path dir = FabricLoader.getInstance().getGameDir().resolve("materialliste");
            store = new Store(dir);
            // Im Singleplayer nur für den eigenen Rechner, und wer dort sitzt, ist automatisch angemeldet
            boolean single = !s.isDedicatedServer();
            localUser = single && s.getSingleplayerProfile() != null ? new User(s.getSingleplayerProfile().id(), s.getSingleplayerProfile().name()) : null;
            web = new WebServer(cfg, store, single ? "127.0.0.1" : cfg.bind, () -> localUser);
            System.out.println("[materialliste] Webseite läuft auf " + cfg.url() + " (Port " + cfg.port + (single ? ", nur lokal" : ", Adresse " + cfg.bind) + "), Daten in " + dir);
            if (!single && cfg.publicUrl.isBlank()) System.out.println("[materialliste] Tipp: Trag in config/materialliste.json unter publicUrl die Adresse ein, über die Spieler die Seite erreichen (z.B. http://dein.server:" + cfg.port + ").");
        } catch (Exception e) {
            System.err.println("[materialliste] Die Webseite konnte nicht gestartet werden: " + e);
            web = null;
        }
    }

    private void stop() {
        if (web != null) web.stop();
        if (store != null) store.saveMarks();
        web = null; store = null; server = null; localUser = null;
        uploads.clear(); asked.clear();
    }

    private static User user(ServerPlayer p) { return new User(p.getUUID(), p.getGameProfile().name()); }
    private static void tell(CommandSourceStack src, String text) { src.sendSuccess(() -> Component.literal(PREFIX + text), false); }

    /* ---------- Befehle ---------- */
    private int status(CommandSourceStack src) {
        if (store == null) { src.sendFailure(Component.literal("Die Materialliste läuft nicht (siehe Server-Log).")); return 0; }
        ServerPlayer p = src.getPlayer();
        tell(src, "Seite: " + cfg.url() + (p == null ? "" : " · " + store.accessible(user(p)).size() + " Projekte") + "\nAnmelden: /materialliste login · Sammelkiste: Kiste anschauen und /materialliste kiste <Projekt>");
        return 1;
    }

    private Component link(String url, String label) {
        return Component.literal(PREFIX).append(Component.literal(label).withStyle(st -> st.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))));
    }

    private int login(CommandSourceStack src) {
        ServerPlayer p = src.getPlayer();
        if (store == null || p == null) { src.sendFailure(Component.literal("Nur für Spieler, solange die Materialliste läuft.")); return 0; }
        String url = cfg.url() + "/login?t=" + store.createLogin(user(p));
        src.sendSuccess(() -> link(url, "Hier klicken, um die Materialliste zu öffnen (5 Minuten gültig, nur für dich)"), false);
        return 1;
    }

    /** Für die Konsole bzw. Server-Besitzer: Login-Link für einen Spieler erzeugen */
    private int adminLogin(CommandContext<CommandSourceStack> c) {
        if (store == null) return 0;
        String name = StringArgumentType.getString(c, "spieler");
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        User known = store.knownUser(name);
        User u = online != null ? user(online) : known != null ? known : new User(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
        tell(c.getSource(), "Login-Link für " + u.name() + ": " + cfg.url() + "/login?t=" + store.createLogin(u));
        return 1;
    }

    private int chest(CommandSourceStack src, String projectName) {
        ServerPlayer p = src.getPlayer();
        if (store == null || p == null) return 0;
        HitResult hit = p.pick(6.0, 1f, false);
        if (!(hit instanceof BlockHitResult b) || hit.getType() != HitResult.Type.BLOCK) { src.sendFailure(Component.literal("Schau eine Kiste an (höchstens 6 Blöcke entfernt).")); return 0; }
        String err = mark(p, b.getBlockPos(), projectName, null);
        if (err != null) { src.sendFailure(Component.literal(err)); return 0; }
        tell(src, projectName == null ? "Diese Kiste zählt nicht mehr." : "Diese Kiste ist jetzt Sammelkiste für „" + projectName + "“.");
        return 1;
    }

    /* ---------- Sammelkisten ---------- */
    private static String dimension(ServerLevel l) { return l.dimension().identifier().toString(); }

    /** Der Behälter an dieser Stelle (Doppelkisten als Ganzes), null wenn dort keiner steht */
    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock cb) return ChestBlock.getContainer(cb, state, level, pos, true);
        return level.getBlockEntity(pos) instanceof Container c ? c : null;
    }
    /** Doppelkisten: immer dieselbe Hälfte als Schlüssel */
    private static BlockPos canonical(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.hasProperty(ChestBlock.TYPE) && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (other.asLong() < pos.asLong()) return other;
        }
        return pos;
    }

    /**
     * Sammelkiste setzen oder entfernen. Das Projekt wird über den Namen (oder den Schematic-Dateinamen) unter den Projekten des Spielers gesucht.
     * Gibt eine Fehlermeldung zurück, null wenn es geklappt hat.
     */
    private String mark(ServerPlayer player, BlockPos pos, String projectName, String file) {
        ServerLevel level = player.level();
        if (!level.isLoaded(pos) || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 100) return "Die Kiste ist zu weit weg.";
        if (containerAt(level, pos) == null) return "Das ist kein Behälter.";
        String key = dimension(level) + "|" + coords(canonical(level, pos));
        User u = user(player);
        synchronized (store) {
            String current = store.markOf(key);
            if (current != null) {
                JsonObject cur = store.get(current);
                if (cur != null && !Store.canAccess(cur, u)) return "Diese Kiste gehört zu einem Projekt, auf das du keinen Zugriff hast.";
            }
            if (projectName == null) { store.setMark(key, null); return null; }
            JsonObject target = find(u, projectName, file);
            if (target == null) return "Kein Projekt mit dem Namen „" + projectName + "“ gefunden. Deine Projekte siehst du auf der Seite.";
            store.setMark(key, target.get("id").getAsString());
        }
        tick = -1;   // gleich zählen
        return null;
    }
    private static String coords(BlockPos p) { return p.getX() + "," + p.getY() + "," + p.getZ(); }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceFirst("\\.litematic$", "").replaceAll("[\\s_\\-]+", " ").strip(); }

    /** Projekt des Spielers mit diesem Namen bzw. diesem Schematic-Dateinamen */
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
    /** Item zählen, inklusive Inhalt von Shulkerboxen und Bündeln */
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
        try { countChests(s); } catch (Throwable t) { System.err.println("[materialliste] Zählen der Sammelkisten fehlgeschlagen: " + t); }
    }

    /** Liest alle Sammelkisten in geladenen Chunks neu aus und schreibt den Bestand in die Projekte */
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
                if (level == null || !level.isLoaded(pos)) continue;   // nicht geladen: letzter bekannter Inhalt bleibt
                Container box = containerAt(level, pos);
                if (box == null) { store.setMark(key, null); continue; }   // Kiste wurde abgebaut
                Map<String, Integer> counts = new TreeMap<>();
                for (int i = 0; i < box.getContainerSize(); i++) add(counts, box.getItem(i));
                changed |= store.setMarkItems(key, counts);
            }
            if (changed) store.saveMarks();
            // Bestand je Projekt: Sammelkisten, auf Wunsch plus Inventare der Mitglieder, die gerade online sind
            Map<String, Boolean> seen = new HashMap<>();
            for (String id : store.markTargets().values()) seen.put(id, true);
            for (String id : store.syncedIds()) seen.put(id, true);   // auch Projekte, deren letzte Sammelkiste gerade entfernt wurde
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

    /* ---------- Nachrichten von der Client-Mod ---------- */
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
        } catch (Exception e) { /* kaputte oder gefälschte Nachricht: ignorieren */ }
    }

    /** Aktive Litematica-Platzierungen eines Spielers: Baufortschritt eintragen, unbekannte Schematics anfordern */
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
            // Noch kein Projekt dazu: Datei vom Client holen und eines anlegen (höchstens alle 30 s nachfragen)
            long now = System.currentTimeMillis();
            if (target == null && cfg.autoCreateFromLitematica && !file.isEmpty() && !uploads.containsKey(u.uuid()) && now - asked.getOrDefault(u.uuid(), 0L) > 30_000
                && ServerPlayNetworking.canSend(player, Net.ToClient.TYPE)) {
                asked.put(u.uuid(), now);
                ServerPlayNetworking.send(player, new Net.ToClient("want", key.getBytes(StandardCharsets.UTF_8)));
            }
        }
    }

    /** Ein Stück einer Schematic-Datei. Aufbau: 4 Byte Kopflänge, JSON-Kopf {key, name, file, index, total}, dann die Daten. */
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
        // Auswerten im Hintergrund, damit der Server nicht ruckelt
        Thread t = new Thread(() -> {
            try {
                byte[] bytes = done.data.toByteArray();
                Litematic.Parsed r = Litematic.parse(bytes);
                String fn = done.file.replaceAll("[\\\\/\\x00-\\x1f]", "_");
                JsonObject src = new JsonObject();
                src.addProperty("type", "litematic"); src.addProperty("filename", fn.length() > 150 ? fn.substring(0, 150) : fn); src.add("meta", r.meta());
                site.createProject(u, done.name.isBlank() ? fn.replaceFirst("\\.[^.]+$", "") : done.name, r.items(), src, bytes);
                System.out.println("[materialliste] Projekt „" + done.name + "“ aus der Litematica-Platzierung von " + u.name() + " angelegt");
            } catch (Exception e) { System.err.println("[materialliste] Schematic von " + u.name() + " konnte nicht übernommen werden: " + e.getMessage()); }
        }, "materialliste-import");
        t.setDaemon(true);
        t.start();
    }
}
