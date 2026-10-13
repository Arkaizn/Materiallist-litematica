package materialliste;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Einstellungen des Servers, liegen in config/materialliste.json */
public class Config {
    /** Port der Webseite */
    public int port = 8177;
    /** Adresse, auf der gelauscht wird. Im Singleplayer immer 127.0.0.1. */
    public String bind = "0.0.0.0";
    /** Adresse, unter der Spieler die Seite erreichen. Wird für den Login-Link benutzt, z.B. http://play.example.org:8177 */
    public String publicUrl = "";
    /** Weitere Hostnamen, über die die Seite aufgerufen werden darf (der aus publicUrl ist automatisch erlaubt). "*" erlaubt alle. */
    public List<String> allowedHosts = new ArrayList<>();
    public int maxUploadMb = 64;
    public int maxProjectsPerPlayer = 50;
    /** Projekte automatisch anlegen, wenn ein Spieler mit Client-Mod eine Litematica-Platzierung aktiv hat */
    public boolean autoCreateFromLitematica = true;
    /** Sekunden zwischen zwei Zählungen der Sammelkisten */
    public int syncSeconds = 5;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Config load(Path file) {
        Config c = new Config();
        try {
            if (Files.exists(file)) { Config r = GSON.fromJson(Files.readString(file), Config.class); if (r != null) c = r; }
        } catch (Exception e) { System.err.println("[materialliste] config/materialliste.json ist nicht lesbar, es gelten die Standardwerte: " + e); return c; }
        if (c.allowedHosts == null) c.allowedHosts = new ArrayList<>();
        if (c.bind == null || c.bind.isBlank()) c.bind = "0.0.0.0";
        if (c.publicUrl == null) c.publicUrl = "";
        try { Files.createDirectories(file.getParent()); Files.writeString(file, GSON.toJson(c)); } catch (Exception ignored) { }
        return c;
    }

    public String url() {
        String u = publicUrl.isBlank() ? "http://localhost:" + port : publicUrl.trim();
        return u.replaceAll("/+$", "");
    }
}
