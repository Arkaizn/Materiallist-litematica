# Minecraft Materialliste

Materiallisten für Bauprojekte als Webseite, direkt aus dem Minecraft-Server heraus (wie BlueMap). Eine einzige Mod-Jar für **Fabric, Minecraft 26.3**:

- **Auf dem Server:** Die Mod startet beim Serverstart die Webseite. Spieler brauchen nichts zu installieren.
- **Im Singleplayer:** Dieselbe Jar startet die Seite nur für den eigenen Rechner (`http://localhost:8177`), ohne Anmeldung.
- **Auf dem Client (optional):** Erkennt Litematica-Platzierungen automatisch, legt daraus Projekte an, meldet den Baufortschritt und zeigt im Kistenfenster einen Knopf für Sammelkisten.

Braucht Fabric API. Die Jar liegt in `mod/build/materialliste-2.0.0+26.3.jar`, neu bauen mit `mod/build.sh` (kein Gradle nötig, nur die Prism-Instanz).

## Einrichten auf dem Server

1. Jar in den `mods`-Ordner des Fabric-Servers legen, Server starten.
2. `config/materialliste.json` anpassen (wird beim ersten Start angelegt) und Server neu starten:

```json
{
  "port": 8177,
  "bind": "0.0.0.0",
  "publicUrl": "http://play.example.org:8177",
  "allowedHosts": [],
  "maxUploadMb": 64,
  "maxProjectsPerPlayer": 50,
  "autoCreateFromLitematica": true,
  "syncSeconds": 5
}
```

- **`publicUrl`**: die Adresse, über die Spieler die Seite erreichen. Sie steht im Login-Link, und ihr Hostname ist automatisch erlaubt.
- **`allowedHosts`**: weitere Hostnamen, über die die Seite aufgerufen werden darf, z.B. `["liste.example.org"]`. IP-Adressen und `localhost` gehen immer. Alles andere wird mit Fehler 403 abgelehnt (Schutz vor fremden Webseiten). `["*"]` schaltet die Prüfung ab.
- Der Port muss in der Firewall offen sein. Ein Proxy wie Velocity leitet ihn nicht weiter: Die Seite läuft auf dem Rechner des eigentlichen Fabric-Servers.

Die Daten liegen im Serverordner unter `materialliste/` (Projekte, Schematics, Sammelkisten, Anmeldungen, Texturen-Cache). Für ein Backup diesen Ordner kopieren.

## Benutzen

| Im Spiel | |
|---|---|
| `/materialliste login` | Gibt dir einen Link in den Chat. Draufklicken, dann bist du im Browser 30 Tage als dieser Spieler angemeldet. |
| `/materialliste kiste <Projekt>` | Die Kiste, die du anschaust, wird Sammelkiste für das Projekt. Ihr Inhalt wird automatisch gezählt. |
| `/materialliste kiste aus` | Die Kiste zählt nicht mehr. |
| `/materialliste` | Adresse der Seite und Kurzhilfe. |
| `/materialliste weblogin <Spieler>` | Nur Konsole/Besitzer: Login-Link für einen Spieler erzeugen. |

Auf der Seite:

- **Projekte anlegen**: `.litematic` oder JSON-Materialliste hochladen (oder automatisch per Client-Mod).
- **Checkliste**, **Nächste Schritte** (was sammeln, was craften, wohin mit Vorhandenem), **Grundmaterialien**, **Crafting-Schritte**, **3D-Ansicht**, **Bearbeiten & Export**.
- **Teilen**: Knopf „Teilen“ oben rechts, dann Spielernamen eintragen. Geteilte Spieler sehen das Projekt, tragen Mengen ein, setzen Sammelkisten und können die Schematic herunterladen (unter „Bearbeiten & Export“). Löschen und Teilen kann nur der Besitzer.
- **Zusammenlegen**: Hat jemand dieselbe Schematic schon als eigenes Projekt, erscheint im geteilten Projekt der Knopf „Zusammenlegen“. Sammelkisten und eingetragene Mengen wandern ins gemeinsame Projekt, das eigene wird gelöscht.
- **Nächste Schritte**: eine Karte je Endprodukt mit allem, was dafür insgesamt nötig ist, und den Crafting-Schritten in der richtigen Reihenfolge. Dazu eine Sammelliste aller fehlenden Rohstoffe.
- **Bestand**: kommt aus den Sammelkisten (auch Inhalt von Shulkerboxen und Bündeln), auf Wunsch zusätzlich aus den Inventaren der Mitglieder, die online sind. Von Hand eingetragene Mengen gelten, bis sich der Wert im Spiel ändert oder du auf ✎ klickst.

## Mit und ohne Client-Mod

| | ohne Client-Mod | mit Client-Mod + Litematica |
|---|---|---|
| Seite, Login, Teilen, 3D-Ansicht | ja | ja |
| Schematic hochladen | auf der Seite | automatisch aus der aktiven Platzierung |
| Sammelkisten | per Befehl | per Knopf im Kistenfenster oder Befehl |
| Baufortschritt (gesetzte Blöcke abziehen) | nein | ja |

Der Server kann nicht sehen, was ein Spieler in Litematica geladen hat. Das geht nur über die Client-Mod. Die alte Mod `materialliste-sync` muss vorher aus dem `mods`-Ordner entfernt werden.

## Umzug von der Docker-Version

1. Der alte Container ist bereits gestoppt, die alten Dateien liegen in `archive/docker-version/`.
2. `archive/docker-version/data/projects/` und `…/schematics/` in den Ordner `materialliste/` des Servers kopieren (vor dem Serverstart).
3. Im Spiel `/materialliste login`. Projekte ohne Besitzer gehören dem ersten Spieler, der sich anmeldet.


## Sicherheit

- **Anmeldung nur über das Spiel**: Der Login-Link ist ein Einmal-Token (5 Minuten gültig), also kann sich niemand als ein anderer Spieler ausgeben. Sitzungen liegen nur als Hash auf dem Server.
- **Zugriff**: Jeder sieht nur eigene und mit ihm geteilte Projekte. Fremde Projekte antworten wie nicht vorhandene.
- **Fremde Webseiten** können keine Anfragen im Namen eines angemeldeten Spielers schicken (Prüfung von `Origin`, `Host` und Cookie mit `SameSite`).
- **Uploads** sind begrenzt: 64 MB pro Datei, 512 MB entpackt, 64 Mio. Blöcke, 50 Projekte pro Spieler.
- **Kein HTTPS**: Die Seite spricht nur HTTP. Wer mitlesen kann (z.B. im selben WLAN), kann das Sitzungs-Cookie abgreifen. Für öffentliche Server einen Reverse-Proxy mit HTTPS davorsetzen und dessen Domain als `publicUrl` eintragen.
- **Client-Mod**: Der gemeldete Baufortschritt wird dem Client geglaubt. Ein manipulierter Client kann also den Fortschritt seiner eigenen bzw. mit ihm geteilten Projekte fälschen, mehr nicht.

## Texturen (Icons und 3D-Ansicht)

Texturen und Blockmodelle werden beim ersten Aufruf einmalig aus [misode/mcmeta](https://github.com/misode/mcmeta) geladen und in `materialliste/cache/assets/` abgelegt. Ohne Internetverbindung des Servers gibt es Buchstaben-Icons und einfarbige Blöcke. Zum Neuladen den Ordner `materialliste/cache/` löschen.

## Eigene Rezepte

`materialliste/recipes.custom.json` anlegen, Beispiel in `examples/recipes.custom.example.json`. So lassen sich Mod-Blöcke ergänzen oder Rezepte überschreiben.

## Hinweise zum Litematic-Import

- Doppel-Slabs zählen als 2, die obere Hälfte von Türen und Betten wird nicht gezählt, Wand-Fackeln/Schilder werden zum Item, Blumentöpfe ergeben Topf + Pflanze, Redstone-Leitung ergibt Redstone.
- Luft, Wasser, Lava und Piston-Köpfe werden ignoriert.
- Inhalte von Kisten in der Schematic werden nicht gezählt.
