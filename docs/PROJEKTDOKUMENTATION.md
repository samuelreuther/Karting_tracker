# Projektdokumentation Karting Tracker

## Zweck

Die App ist ein Android-MVP zur Analyse von Indoor-Kartfahrten mit Smartphone-Sensoren ohne GPS.
Erfasst werden Beschleunigungs- und Gyroskopdaten. Daraus werden Sessions und Runden abgeleitet,
die anschliessend visuell verglichen werden koennen.

## Zielbild laut Anforderung

- Start/Stop-Aufnahme fuer Sensoren
- Erfassung von Accelerometer und Gyroscope mit `SENSOR_DELAY_FASTEST`
- Speicherung zeitgestempelter Messwerte
- Ableitung von longitudinaler und lateraler Beschleunigung
- Rauschreduktion per Low-Pass-Filter
- Rundenerkennung ueber Mustervergleich in gleitenden Fenstern
- Datenmodell fuer Sample, Lap, Session
- Visualisierung per MPAndroidChart
- Vergleich von zwei Runden nach Normierung auf 0-100 Prozent
- Einfache MVVM-Struktur
- Speicherung nur im Speicher
- Bonus: Brems-Peaks und farbliche Hervorhebung

## Anforderungsmatrix

| Bereich | Anforderung | Status | Umsetzung |
|---|---|---|---|
| Recording | Start-Button | Erfuellt | `MainFragment` startet Aufnahme ueber `SessionViewModel.startRecording()` |
| Recording | Stop-Button | Erfuellt | `MainFragment` stoppt Aufnahme ueber `SessionViewModel.stopRecording()` |
| Recording | Live-Indikator | Erfuellt | Statusanzeige auf Main Screen aus `SessionUiState.statusLabel` |
| Recording | Accelerometer lesen | Erfuellt | `SensorRecorder` registriert `TYPE_ACCELEROMETER` |
| Recording | Gyroscope lesen | Erfuellt | `SensorRecorder` registriert `TYPE_GYROSCOPE` |
| Recording | `SENSOR_DELAY_FASTEST` | Erfuellt | Listener-Registrierung in `SensorRecorder.registerListeners()` |
| Recording | Zeitgestempelte Daten speichern | Erfuellt | `SensorSample` und `SessionRepository.currentSamples` |
| Verarbeitung | Low-Pass-Filter | Erfuellt | `LowPassFilter` fuer Accelerometer und Gyroscope |
| Verarbeitung | Longitudinal/Lateral trennen | Teilweise erfuellt | Aktuell feste Geraeteachsen-Zuordnung: Y = longitudinal, X = lateral |
| Lap Detection | Zeitreihe fortlaufend speichern | Erfuellt | Samples werden waehrend Recording fortlaufend in `SessionRepository` gesammelt |
| Lap Detection | Sliding Window 5-10 Sekunden | Erfuellt | In `LapDetector`: 100-ms-Resampling, Fensterlaenge 60 Punkte = ca. 6 Sekunden |
| Lap Detection | Aehnlichkeit via Dot Product/Korrelation | Erfuellt | Kosinus-Aehnlichkeit ueber dot product und Normen in `windowSimilarity()` |
| Lap Detection | Wiederholende Muster als Runden erkennen | Erfuellt | `LapDetector.detect()` sucht bestes Shift und Grenzen |
| Lap Detection | Runden als Segmente speichern | Erfuellt | `Lap`-Objekte werden in `buildLaps()` erzeugt |
| Datenmodell | `SensorSample` | Erfuellt | Vorhanden |
| Datenmodell | `Lap` | Erfuellt | Vorhanden |
| Datenmodell | `Session` | Erfuellt | Vorhanden |
| Visualisierung | Laps als Line Graphs | Erfuellt | `ComparisonFragment` mit `MPAndroidChart` |
| Visualisierung | X-Achse 0-100 Prozent | Erfuellt | `LapNormalizer` erzeugt normierte Werte und `ChartUtils` setzt X-Achse 0-100 |
| Visualisierung | Y-Achse longitudinal/lateral | Erfuellt | Zwei Charts, jeweils fuer longitudinal und lateral |
| Lap Comparison | Zwei Runden auswaehlen | Erfuellt | Zwei Spinner in `ComparisonFragment` |
| Lap Comparison | Auf gleiche Laenge normieren | Erfuellt | `LapNormalizer.normalize()` auf 101 Punkte |
| Lap Comparison | Overlays Lap A vs Lap B | Erfuellt | Beide DataSets gleichzeitig pro Chart |
| UI | Main Screen | Erfuellt | `fragment_main.xml` |
| UI | Lap Screen | Erfuellt | `fragment_laps.xml` + RecyclerView |
| UI | Comparison Screen | Erfuellt | `fragment_comparison.xml` |
| Technik | Kotlin | Erfuellt | Komplettes Projekt in Kotlin |
| Technik | MVVM / clean structure | Erfuellt | Activity/Fragments + gemeinsames ViewModel + Repository + Domain |
| Technik | SensorManager korrekt nutzen | Erfuellt | Registrierung/Abmeldung in `SensorRecorder` |
| Technik | Lifecycle korrekt behandeln | Weitgehend erfuellt | `SensorRecorder` ist `DefaultLifecycleObserver` |
| Technik | Nur In-Memory | Erfuellt | Keine DB, alles im Repository gehalten |
| Bonus | Peaks hervorheben | Teilweise erfuellt | Peaks werden erkannt und gezahlt, aber im Chart noch nicht markiert |
| Bonus | Farben fuer acceleration/braking/cornering | Teilweise erfuellt | Farbige Linien vorhanden, aber keine semantische Einfaerbung innerhalb derselben Runde |

## Architektur

## Schichten

- `data`
  - Datenmodelle `SensorSample`, `Lap`, `Session`
  - `SessionRepository` als In-Memory-Speicher und zentrale Session-Verwaltung
- `sensor`
  - `SensorRecorder` kapselt Android-Sensorzugriff
  - `LowPassFilter` reduziert Rauschen
- `domain`
  - `LapDetector` fuer Mustererkennung und Rundenschnitt
  - `LapNormalizer` fuer Vergleich auf normierter Zeitachse
  - `PeakDetector` fuer einfache Brems-Peak-Erkennung
- `ui`
  - `SessionViewModel` als zentraler State-Halter fuer alle Screens
  - `MainFragment`, `LapsFragment`, `ComparisonFragment`

## Datenfluss

1. Nutzer startet Aufnahme auf dem Main Screen.
2. `SessionViewModel` ruft `SensorRecorder.startRecording()` auf.
3. `SensorRecorder` registriert Sensorlistener auf separatem `HandlerThread`.
4. Accelerometer- und Gyroskopdaten werden gefiltert.
5. Bei jedem Accelerometer-Event wird ein `SensorSample` erzeugt.
6. `SessionRepository.appendSample()` sammelt die Samples im Speicher.
7. Beim Stoppen ruft `SessionRepository.stopSession()` die Rundenerkennung auf.
8. `LapDetector` erzeugt `Lap`-Segmente.
9. `PeakDetector` ergaenzt einfache Brems-Peaks.
10. `SessionViewModel` stellt daraus UI-State fuer Listen und Charts bereit.

## Implementierung im Detail

## 1. Recording

### Implementiert

- Sensoren:
  - `TYPE_ACCELEROMETER`
  - `TYPE_GYROSCOPE`
- Sampling:
  - `SensorManager.SENSOR_DELAY_FASTEST`
- Threading:
  - eigener `HandlerThread("karting-sensor-thread")`
- Start/Stop:
  - Start erzeugt neue Session
  - Stop beendet Listener und startet Verarbeitung
- Live-Status:
  - Recording-Status, Sample-Anzahl, Live-Beschleunigungen, erkannte Runden

### Technische Umsetzung

`SensorRecorder` registriert beide Sensoren und verarbeitet Events in `onSensorChanged()`.

- Gyro-Events aktualisieren den zuletzt bekannten Gyro-Zustand.
- Accelerometer-Events erzeugen einen vollstaendigen `SensorSample`.
- `timestampNs` wird aus `event.timestamp` uebernommen.

### Wichtige Annahme

Die Achsenzuordnung ist aktuell fest verdrahtet:

- `filteredAccel[1]` -> longitudinal
- `filteredAccel[0]` -> lateral

Das funktioniert nur dann sinnvoll, wenn das Smartphone reproduzierbar gleich montiert ist.

## 2. Datenverarbeitung

### Low-Pass-Filter

Der Filter ist in `LowPassFilter` implementiert.

- Alpha: `0.18f`
- Zustand: 3-dimensional fuer X, Y, Z
- Einsatz:
  - einmal fuer Accelerometer
  - einmal fuer Gyroscope

### Ableitung der Fahrdynamik

Es gibt in diesem MVP keine komplexe Orientierungsschaetzung.
Die App verwendet direkt die Geraeteachsen als Naeherung fuer:

- longitudinale Beschleunigung
- laterale Beschleunigung

### Einschraenkung

Gravitation wird nicht explizit herausgerechnet und die Geraeteorientierung wird nicht dynamisch kalibriert.
Damit ist die Signalqualitaet stark von der Montageposition abhaengig.

## 3. Rundenerkennung

### Ziel

Wiederkehrende Muster in Beschleunigungs- und Gyrodaten sollen genutzt werden, um Rundenenden zu finden.

### Implementierte Methode

`LapDetector` arbeitet heuristisch in mehreren Schritten:

1. Rohdaten werden auf 100-ms-Buckets heruntergesampelt.
2. Pro Bucket werden Mittelwerte fuer
   - longitudinale Beschleunigung
   - laterale Beschleunigung
   - Yaw-Rate (`gyroZ`)
   berechnet.
3. Ein Sliding Window von 60 Punkten wird verwendet.
4. 60 Punkte bei 100 ms entsprechen ca. 6 Sekunden und liegen damit in der geforderten Spanne von 5-10 Sekunden.
5. Es werden verschiedene Shifts zwischen 150 und 1200 Punkten getestet.
6. Ein Shift entspricht einer moeglichen Rundendauer.
7. Fuer jedes Shift wird eine Aehnlichkeit berechnet.
8. Die beste Shift-Hypothese wird ausgewaehlt.
9. Lokale Maxima oberhalb eines Schwellwerts werden als Runden-Grenzkandidaten interpretiert.
10. Daraus werden `Lap`-Segmente geschnitten.

### Aehnlichkeitsmass

Verwendet wird eine Kosinus-Aehnlichkeit auf Basis von dot product:

- Signal A:
  - longitudinal
  - lateral
  - `yawRate * 0.5`
- Signal B:
  - dieselben Groessen

Damit werden Formaehnlichkeiten zwischen zwei Fenstern verglichen.

### Fallback-Verhalten

Wenn die Datenlage fuer eine robuste Segmentierung nicht reicht, faellt die Logik auf genau eine Runde zurueck:

- gesamte Session = eine `Lap`

Das ist fuer ein MVP sinnvoll, weil die App dann immer noch Daten visualisieren kann.

### Einschraenkungen

- Keine Streckenkarte, keine echte Start/Ziel-Referenz
- Kein maschinelles Lernen
- Keine adaptive Kalibrierung pro Strecke
- Heuristiken sind empfindlich gegen:
  - stark unregelmaessige Runden
  - kurze Sessions
  - Safety-Car- oder Abbruchphasen
  - veraenderte Telefonlage

## 4. Datenmodell

### `SensorSample`

Enthaelt:

- Zeitstempel in Nanosekunden
- Accelerometer X/Y/Z
- Gyroscope X/Y/Z
- longitudinale Beschleunigung
- laterale Beschleunigung

### `Lap`

Enthaelt:

- laufende ID
- Liste von `SensorSample`
- `lapTimeMs`
- Start- und Endzeitstempel
- `brakingPeakIndices`

### `Session`

Enthaelt:

- Session-ID
- Start- und Endzeit
- alle Session-Samples
- alle erkannten Runden
- geschaetzte Rundendauer

## 5. Visualisierung

### Umsetzung

Die Visualisierung basiert auf `MPAndroidChart`.

- Zwei getrennte Charts:
  - longitudinal
  - lateral
- X-Achse:
  - 0 bis 100 Prozent
- Pro Chart:
  - Lap A
  - Lap B

### Normierung

`LapNormalizer.normalize()` interpoliert jede Runde auf 101 Stuetzpunkte.

- 0 = Rundenstart
- 100 = Rundenende

Das erlaubt den direkten Vergleich auch bei unterschiedlichen Rundenzeiten.

## 6. Rundenvergleich

### Implementiert

- Auswahl von zwei Runden per Spinner
- Normierung beider Runden
- Overlay in zwei Charts
- Vergleichstext:
  - welche Runde schneller ist
  - Zeitdifferenz
  - Anzahl Brems-Peaks

### Noch nicht implementiert

- Delta-Linie zwischen den beiden Runden
- Abschnittsweise Zeitdifferenz pro Sektor
- Marker fuer Brems-Peaks direkt im Chart

## 7. UI-Umsetzung

## Main Screen

Implementiert:

- Start
- Stop
- Statusanzeige
- Sample Count
- Live longitudinal/lateral
- Anzahl erkannter Runden
- geschaetzte Rundendauer
- Navigation zu Laps und Comparison

## Lap Screen

Implementiert:

- Liste erkannter Runden
- Rundenzeit
- Anzahl Samples
- Anzahl erkannter Brems-Peaks

## Comparison Screen

Implementiert:

- Auswahl Lap A
- Auswahl Lap B
- Ueberlagerte Charts
- Zusammenfassung schneller/langsamer

## Lifecycle und Zustandsverwaltung

### Implementiert

- Gemeinsames `SessionViewModel` fuer alle Screens
- Sensorlistener werden ueber Lifecycle-Callbacks registriert/abgemeldet
- UI basiert auf `StateFlow`
- Fragments sammeln Daten mit `repeatOnLifecycle(Lifecycle.State.STARTED)`

### Einschraenkung

Es gibt keine Persistenz ueber App-Neustarts oder Prozessverlust hinaus.

## Offene Punkte

## Fachlich offen

- Orientierungskalibrierung des Smartphones
- Bessere Trennung von Fahrdynamik und Gravitation
- Robustere Rundenerkennung fuer verschiedene Streckenlaengen und Fahrstile
- Plausibilisierung erkannter Rundenzeiten
- Sektorzeiten oder Abschnittsvergleich

## Technisch offen

- Persistenz von Sessions, z. B. per Datei oder Room
- Exportfunktion, z. B. CSV oder JSON
- Hintergrundaufnahme / Foreground Service fuer laengere Sessions
- Runtime-Checks fuer mehr Sensortypen und Geraetevarianten
- Tests:
  - Unit-Tests fuer `LapDetector`
  - Unit-Tests fuer `LapNormalizer`
  - UI-Tests fuer Screen-Flow
- Build-Validierung in einer echten Android-Umgebung

## UX offen

- Hinweis-/Setup-Screen zur korrekten Telefonmontage
- Bessere Fehlertexte bei zu kurzer Session
- Chart-Marker fuer Bremsen, Einlenken, Beschleunigen
- Farbige Segmentdarstellung innerhalb einer Runde:
  - gruen fuer Beschleunigen
  - rot fuer Bremsen
  - blau fuer Cornering

## Bekannte Grenzen des MVP

- Keine echte Fahrzeugkoordinaten-Transformation
- Keine GPS- oder Beacon-Referenz
- Keine automatische Erkennung falscher Telefonlage
- Bei sehr kurzen Aufnahmen wird die komplette Session als eine Runde gespeichert
- Die Rundenerkennung ist heuristisch und nicht validiert gegen reale Referenzdaten

## Empfohlene naechste Schritte

1. Build und Lauf auf echtem Android-Geraet validieren.
2. Testdaten aufzeichnen und die Lap-Detection-Schwellen gegen reale Indoor-Kart-Sessions justieren.
3. Telefonlage/Kalibrierung als expliziten Setup-Schritt einfuehren.
4. Brems-Peaks direkt im Chart markieren.
5. Persistenz und Export hinzufuegen.

## Relevante Dateien

- `app/src/main/java/com/kartingtracker/sensor/SensorRecorder.kt`
- `app/src/main/java/com/kartingtracker/sensor/LowPassFilter.kt`
- `app/src/main/java/com/kartingtracker/data/SessionRepository.kt`
- `app/src/main/java/com/kartingtracker/domain/LapDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/LapNormalizer.kt`
- `app/src/main/java/com/kartingtracker/domain/PeakDetector.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionViewModel.kt`
- `app/src/main/java/com/kartingtracker/ui/main/MainFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/laps/LapsFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/comparison/ComparisonFragment.kt`
