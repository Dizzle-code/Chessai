# ♞ Chess AI – Zug-Overlay für Android

Ein schwebender **♞-Button** über jeder Schach-App: antippen → die App erkennt das Brett auf dem
Bildschirm und legt zwei **leuchtende Punkte** darüber – **Cyan** auf die Figur, die du ziehen
sollst, **Gelb** auf das Zielfeld. Es wird nichts automatisch gezogen, es ist nur ein Overlay.

## Download

**[ChessAI.apk (neuester Build)](https://github.com/Dizzle-code/Chessai/releases/latest/download/ChessAI.apk)**

Die APK wird bei jedem Push automatisch von GitHub Actions gebaut (inkl. Stockfish 17.1 für
arm64-Handys). Auf dem Handy herunterladen, „Installation aus unbekannten Quellen“ erlauben,
installieren. Android 8.0 oder neuer.

## So funktioniert's

1. App öffnen → AI-Anbieter wählen (optional) → **Overlay starten** → Bildschirmaufnahme erlauben.
2. Schach-App öffnen, den ♞-Button an eine freie Stelle ziehen.
3. Wenn du am Zug bist: **♞ antippen** → Cyan = Figur, Gelb = Zielfeld.
4. **Lange drücken** öffnet das Menü: Auto-Modus, Brett manuell festlegen, mit AI neu einlesen,
   neue Partie, gelernte Figuren löschen, beenden.

Im **Auto-Modus** schaut die App alle 1,5 s (lokal, ohne Tokens) aufs Brett und zeigt nach jedem
Gegnerzug automatisch den nächsten besten Zug.

## Token-sparend by Design

Die AI wird nur für das *Erkennen* der Stellung gebraucht – und auch das so selten wie möglich:

| Schritt | Kosten |
| --- | --- |
| Brett auf dem Screenshot finden (Schachbrettmuster-Suche) | 0 Tokens |
| Startstellung erkennen und dabei alle Figuren „lernen“ | 0 Tokens |
| Partie verfolgen: welcher legale Zug (bzw. Zug + Antwort) erklärt das neue Bild? | 0 Tokens |
| Neue Stellung mit gelernten Figuren klassifizieren | 0 Tokens |
| Unbekanntes Figuren-Design / Einstieg mitten in der Partie | **1 AI-Aufruf** (~450×450-JPEG + 70-Token-Prompt, ~80 Tokens Antwort) |
| Besten Zug berechnen | 0 Tokens – lokal mit **Stockfish** (bzw. integrierter Engine) |

Die gelernten Figuren werden gespeichert. Typisch: **ein einziger AI-Aufruf pro Figuren-Design** –
danach läuft alles lokal. Die App zeigt die verbrauchten Tokens an.

## Login / AI-Anbieter

* **Claude** – Anthropic API-Key ([hier erstellen](https://console.anthropic.com/settings/keys)),
  Standardmodell `claude-opus-5-5` mit niedrigem Effort; `claude-haiku-5-5` ist günstiger.
  Umgesetzt mit dem offiziellen Anthropic Java SDK.
* **ChatGPT** – OpenAI API-Key ([hier erstellen](https://platform.openai.com/api-keys)),
  Standardmodell `gpt-5-mini`, Bild mit `detail: low`.
* **Keine AI** – funktioniert ab der Grundstellung komplett offline.

Ein Login mit dem **Claude-Pro/Max-Abo** (wie in Claude Code) oder dem **ChatGPT-Plus-Abo** ist
für Drittanbieter-Apps nicht vorgesehen bzw. laut Nutzungsbedingungen nicht erlaubt – diese
Abo-Logins gelten nur für die offiziellen Apps. Deshalb nutzt die App API-Keys; sie werden mit
einem Schlüssel aus dem Android-Keystore verschlüsselt gespeichert und nur an den jeweiligen
Anbieter gesendet.

## Fairplay

Engine-Hilfe in Online-Partien gegen Menschen (chess.com, lichess, …) verstößt gegen deren Regeln
und führt zur Kontosperre. Gedacht ist die App zum Lernen, für Analysen und für Partien gegen
Computer-Gegner.

## Projektstruktur

```
core/   reines Kotlin (lokal testbar, ohne Android)
  chess/    Zuggenerator, FEN, Make/Unmake (Perft-getestet)
  engine/   integrierte Alpha-Beta-Engine + UCI-Anbindung für Stockfish
  vision/   Brett-Lokalisierung, Feld-Merkmale, lernende Figuren-Bibliothek
  ai/       Claude (Anthropic SDK) und OpenAI als Bild-zu-Stellung-Leser
  Analyzer  Pipeline: Tracking → lokale Erkennung → Startstellung → AI (nur falls nötig)
app/    Android: Overlay-Service (MediaProjection), ♞-Button, Leuchtpunkte, Einstellungen
```

### Selbst bauen

```bash
CORE_ONLY=1 ./gradlew :core:test      # Tests ohne Android SDK
./gradlew :app:assembleRelease        # APK (Android SDK nötig)
```

Stockfish wird im CI aus dem offiziellen Quellcode (Tag `sf_17.1`) mit dem Android NDK
kompiliert (`make build ARCH=armv8 COMP=ndk`) und als `libstockfish.so` mitgeliefert. Fehlt die
Datei, nutzt die App automatisch die integrierte Engine. Stockfish steht unter der GPLv3.
