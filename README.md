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

1. App öffnen → **Mit ChatGPT anmelden** (optional) → **Overlay starten** → Bildschirmaufnahme erlauben.
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

* **ChatGPT – „Mit ChatGPT anmelden“ (Standard):** OAuth-Login mit deinem ChatGPT-Plus/Pro-Abo,
  **kein API-Key**. Die Bild-Erkennung läuft über das Kontingent deines Abos (OpenAIs offizielles
  „Sign in with ChatGPT“ für Open-Source-Apps). Technisch: OAuth 2.0 + PKCE, Endpunkte per
  OpenID-Discovery von `auth.openai.com`, dynamische Client-Registrierung, Redirect über
  `http://127.0.0.1:1455/auth/callback`
  (kleiner lokaler Server in der App), Tokens verschlüsselt im Android-Keystore, automatischer
  Token-Refresh. Anfragen gehen an die Responses API.
* **Keine AI** – funktioniert ab der Grundstellung komplett offline.
* **Erweitert:** Claude oder OpenAI per API-Key.

**Claude-Abo-Login gibt es nicht:** Anthropic erlaubt den OAuth-Login mit Claude Free/Pro/Max nur
in Claude.ai und Claude Code. Fremd-Apps dürfen ihn laut Nutzungsbedingungen nicht anbieten, und
Anthropic blockiert solche Clients auch technisch. Für Claude bleibt nur ein API-Key.

Keine Einrichtung nötig: Wie in OpenAIs offiziellem
[sign-in-with-chatgpt-devkit](https://github.com/openai/sign-in-with-chatgpt-devkit) meldet sich
die App beim ersten Login mit dem Platzhalter-Client `dynamic_agent_client` an. ChatGPT vergibt
dabei automatisch eine eigene Client-ID für diese Installation, die die App speichert und für
spätere Logins und Token-Refreshes verwendet. Nach dem Login lädt die App die Modelle, die dein
Plan anbietet, und wählt ein kleines davon.

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
  ai/       Sign in with ChatGPT (OAuth/PKCE), Claude (Anthropic SDK), OpenAI API-Key
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
