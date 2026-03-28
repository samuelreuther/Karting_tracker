# Projektdokumentation Karting Tracker

## Zweck

Die App ist eine Android-Anwendung zur Analyse von Indoor-Kartfahrten mit Smartphone-Sensoren ohne GPS.
Sie zeichnet Sensordaten auf, erkennt Runden heuristisch, speichert Sessions dauerhaft auf dem Geraet
und erlaubt den Vergleich einzelner Runden.

Der aktuelle Stand ist kein Wegwerf-MVP mehr, sondern eine praktisch nutzbare Version mit:

- Sensoraufzeichnung
- Kalibrierung
- persistenter Session-Speicherung
- Track-Verwaltung
- Session-Browsing
- Lap-Detection
- Vergleichsdiagrammen und Text-Insights

## Aktueller Funktionsumfang

- Aufnahme per Start/Stop
- 2-Sekunden-Kalibrierung vor der Session
- Accelerometer- und Gyroscope-Aufnahme mit `SENSOR_DELAY_FASTEST`
- Low-Pass-Filter auf beiden Sensorsignalen
- Berechnung kompatibler Richtungswerte:
  - `longitudinalAccel`
  - `lateralAccel`
- Berechnung robuster pocket-tauglicher Signale:
  - `totalAcceleration`
  - `yawRateAbs`
- Hybrid-Lap-Detection mit Korrelation, Event-Erkennung und Confidence
- Marker fuer Brems- und Cornering-Ereignisse
- Delta-Chart und einfache Fahrstil-Insights
- dauerhafte Speicherung jeder Session als JSON-Datei
- Track-Auswahl und Track-Erstellung
- Session-Liste mit Filter nach Track
- Laden der letzten Session

## Architektur

## Schichten

- `data`
  - Datenmodelle `SensorSample`, `Lap`, `Session`, `Track`
  - `SessionRepository` als zentrale Fachlogik fuer aktuelle Session, gespeicherte Sessions und Track-Zustand
  - `SessionStorageManager` fuer JSON-Persistenz
  - `TrackManager` fuer persistente Track-Verwaltung
- `sensor`
  - `SensorRecorder` kapselt Android-Sensorzugriff
  - `CalibrationManager` entfernt Gravitation und liefert kompatible Fahrdynamikwerte
  - `LowPassFilter` reduziert Rauschen
- `domain`
  - `LapDetector` fuer Mustererkennung, Event-Erkennung und Confidence-Scoring
  - `PeakDetector` fuer Brems- und Cornering-Peaks
  - `LapNormalizer` fuer interpolierte Vergleichskurven
  - `DrivingInsightsGenerator` fuer heuristische Vergleichstexte
- `ui`
  - `SessionViewModel` als zentraler State-Halter
  - `MainFragment`, `LapsFragment`, `ComparisonFragment`, `SessionListFragment`

## Datenfluss

1. Nutzer waehlt oder erstellt einen Track auf dem Main Screen.
2. `SessionViewModel` ruft `SensorRecorder.startRecording()` auf.
3. `SensorRecorder` startet mit Kalibrierung bei stehendem Kart.
4. Nach erfolgreicher Kalibrierung startet `SessionRepository.startSession()`.
5. Waehrend der Session werden `SensorSample`-Objekte gesammelt.
6. Beim Stoppen ruft `SessionRepository.stopSession()` die Lap-Detection auf.
7. `PeakDetector` ergaenzt Brems- und Cornering-Peaks.
8. `SessionStorageManager.saveSession()` speichert die Session sofort als JSON.
9. `SessionViewModel` stellt die Session fuer Lap-Liste, Comparison und Session-Browsing bereit.

## Anforderungsmatrix

| Bereich | Anforderung | Status | Umsetzung |
|---|---|---|---|
| Recording | Start-Button | Erfuellt | `MainFragment` startet Aufnahme ueber `SessionViewModel.startRecording()` |
| Recording | Stop-Button | Erfuellt | `MainFragment` stoppt Aufnahme ueber `SessionViewModel.stopRecording()` |
| Recording | Live-Indikator | Erfuellt | `SessionUiState.statusLabel` mit Idle, Calibrating, Recording, Stopped |
| Recording | Accelerometer lesen | Erfuellt | `SensorRecorder` registriert `TYPE_ACCELEROMETER` |
| Recording | Gyroscope lesen | Erfuellt | `SensorRecorder` registriert `TYPE_GYROSCOPE` |
| Recording | `SENSOR_DELAY_FASTEST` | Erfuellt | Listener-Registrierung in `SensorRecorder.registerListeners()` |
| Recording | Kalibrierung vor Session | Erfuellt | `CalibrationManager` sammelt ca. 2 Sekunden stationaere Daten |
| Verarbeitung | Low-Pass-Filter | Erfuellt | `LowPassFilter` fuer Accel und Gyro |
| Verarbeitung | Gravitation entfernen | Erfuellt | `CalibrationManager.projectAcceleration()` |
| Verarbeitung | Pocket-taugliche Signale | Erfuellt | `SensorSample.totalAcceleration` und `SensorSample.yawRateAbs` |
| Datenmodell | `SensorSample` | Erfuellt | kompatible und robuste Signale im Modell vorhanden |
| Datenmodell | `Lap` | Erfuellt | Samples, Lap Time, Peak-Indizes vorhanden |
| Datenmodell | `Session` | Erfuellt | Track, Zeiten, Samples, Laps vorhanden |
| Datenmodell | `Track` | Erfuellt | `Track(name: String)` vorhanden |
| Lap Detection | Sliding Window | Erfuellt | 100-ms-Resampling, 60 Punkte Fenster |
| Lap Detection | Korrelation | Erfuellt | Kosinus-Aehnlichkeit in `LapDetector.windowSimilarity()` |
| Lap Detection | Event-Erkennung | Erfuellt | Brems- und Cornering-Ereignisse in `LapDetector` |
| Lap Detection | Confidence Score | Erfuellt | Korrelation, Event-Praesenz und Dauer-Konsistenz kombiniert |
| Lap Detection | min/max Lap Time | Erfuellt | 15 bis 120 Sekunden in `buildLaps()` |
| Lap Detection | Ausreisser-Filter | Erfuellt | Laps mit >30 Prozent Abweichung werden verworfen |
| Lap Detection | Fallback | Erfuellt | eine Session wird notfalls als einzelne Lap gespeichert |
| Persistenz | Session als JSON speichern | Erfuellt | `SessionStorageManager.saveSession()` |
| Persistenz | Dateiname `session_<track>_<timestamp>.json` | Erfuellt | Dateinamenschema in `SessionStorageManager` |
| Persistenz | Sessions laden | Erfuellt | `loadAllSessions()`, `loadSessionsForTrack()`, `loadLastSession()` |
| Persistenz | Speichern auf Stop | Erfuellt | direkt in `SessionRepository.stopSession()` |
| Track Management | Track auswaehlen | Erfuellt | Spinner auf Main Screen |
| Track Management | Track anlegen | Erfuellt | Dialog auf Main Screen |
| Track Management | Track persistieren | Erfuellt | `TrackManager` mit `SharedPreferences` |
| Session Browsing | Liste aller Sessions | Erfuellt | `SessionListFragment` |
| Session Browsing | Nach Track filtern | Erfuellt | Filter-Spinner in `SessionListFragment` |
| Session Browsing | Session laden | Erfuellt | Session wird in `SessionRepository` geladen |
| UX | Letzte Session laden | Erfuellt | Button `Load last session` auf Main Screen |
| Visualisierung | Lap-Overlay | Erfuellt | `ComparisonFragment` mit MPAndroidChart |
| Visualisierung | Delta-Chart | Erfuellt | zusaetzlicher Chart in `ComparisonFragment` |
| Visualisierung | Peak-Marker | Erfuellt | Marker fuer Brems- und Cornering-Peaks |
| Insights | Textliche Hinweise | Erfuellt | `DrivingInsightsGenerator` |

## Implementierung im Detail

## 1. Recording und Kalibrierung

### Ablauf

- Starten der Aufnahme aktiviert nicht sofort die Session.
- Zunaechst laeuft eine Kalibrierungsphase von etwa 2 Sekunden.
- In dieser Zeit wird angenommen, dass das Kart stillsteht.
- Aus den Accelerometer-Daten wird ein mittlerer Gravitationsvektor berechnet.
- Erst danach startet die eigentliche Session-Aufzeichnung.

### Technische Umsetzung

- `SensorRecorder`
  - verwaltet `RecorderPhase.IDLE`, `CALIBRATING`, `RECORDING`
  - nutzt separaten `HandlerThread`
  - verarbeitet Sensorereignisse in `onSensorChanged()`
- `CalibrationManager`
  - akkumuliert stationaere Accel-Werte
  - normalisiert den Gravitationsvektor
  - entfernt den Gravitationsanteil aus kuenftigen Beschleunigungswerten

### Wichtige Annahme

Die App ist inzwischen robuster gegen unbekannte Telefonlage, aber nicht vollkommen orientationsinvariant.
Die kompatiblen Richtungswerte `longitudinalAccel` und `lateralAccel` bleiben eine angenaeherte Projektion.

## 2. Signalverarbeitung

### Klassische kompatible Signale

Diese Felder bleiben erhalten, damit bestehende Visualisierung und Datenmodell kompatibel bleiben:

- `longitudinalAccel`
- `lateralAccel`

Sie werden nach Gravitation-Entfernung auf eine angenaeherte Fahr-Ebene projiziert.

### Robuste pocket-taugliche Signale

Fuer realen Einsatz mit variabler Orientierung verwendet die App zusaetzlich:

- `totalAcceleration`
  - Betrag der gravitationsbereinigten Beschleunigung
- `yawRateAbs`
  - Betrag der Z-Gyro-Rate

Diese Werte sind deutlich robuster, wenn das Telefon locker in der Tasche liegt oder verdreht ist.

## 3. Lap Detection

### Ziel

Rundengrenzen sollen auch unter realen Bedingungen erkannt werden, selbst wenn die Richtungssignale unzuverlaessig sind.

### Verwendete Signale

Die aktuelle Lap-Detection arbeitet primaer mit:

- `totalAcceleration`
- `yawRateAbs`

### Schritte

1. Resampling auf 100-ms-Buckets.
2. Sliding Window ueber 60 Punkte, also ca. 6 Sekunden.
3. Test mehrerer Shift-Werte als Kandidaten fuer moegliche Rundendauer.
4. Korrelation zweier Fenster ueber `totalAcceleration` und `yawRateAbs`.
5. Zusatzpruefung:
   - Bremsereignis vorhanden
   - Cornering-Ereignis vorhanden
6. Duplicate-Filter fuer zu nahe Kandidaten.
7. Confidence-Berechnung aus:
   - Korrelation
   - Event-Praesenz
   - Dauer-Konsistenz
8. Verwerfen unplausibler Laps:
   - unter 15 Sekunden
   - ueber 120 Sekunden
   - mehr als 30 Prozent Abweichung vom Mittelwert
9. Fallback auf eine einzelne Lap, wenn die Erkennung instabil ist.

### Event-Erkennung

- Bremsereignis:
  - deutlicher Abfall in `totalAcceleration`
- Cornering-Ereignis:
  - hohes `yawRateAbs`
  - gleichzeitig erhoehte `totalAcceleration`

### Logging

`LapDetector` schreibt Ergebnisse und Confidence-Werte per `Log.i(...)`.

## 4. Peak Detection

`PeakDetector` erzeugt Peak-Indizes fuer:

- Brems-Peaks
- Cornering-Peaks

Diese Peaks werden:

- in `Lap` gespeichert
- im Comparison Screen als Marker dargestellt
- in Text-Zusammenfassungen verwendet

## 5. Persistenz

### SessionStorageManager

`SessionStorageManager` speichert jede Session als JSON-Datei im app-spezifischen Speicher.

### Speicherort

- `context.filesDir/sessions`

### Dateinamen

- `session_<trackName>_<timestamp>.json`

### Gespeicherte Inhalte

- Session-ID
- Track-Name
- Start- und Endzeit
- Start- und Endzeitstempel
- alle `SensorSample`
- alle `Lap`
- Lap Times
- Peak-Indizes

### Integrationspunkt

- Speichern erfolgt unmittelbar in `SessionRepository.stopSession()`

Damit ist Datenverlust nach dem Stoppen stark reduziert.

## 6. Track Management

### Modell

- `Track(name: String)`

### Speicher

- `TrackManager` nutzt `SharedPreferences`

### Main Screen

Vor dem Starten kann der Nutzer:

- einen bestehenden Track waehlen
- einen neuen Track per Dialog anlegen

### Session-Integration

Jede Session traegt `trackName`.

### Nutzen

- Sessions koennen nach Track gruppiert und gefiltert werden.
- Historische Daten pro Strecke sind damit spaeter nutzbar.

## 7. Session Browsing

### Neuer Screen

- `SessionListFragment`

### Funktionen

- Liste aller gespeicherten Sessions
- Anzeige von:
  - Track-Name
  - Datum
  - Anzahl Laps
  - Anzahl Samples
- Filter nach Track
- Session laden und direkt zu:
  - Lap-Liste
  - Comparison

## 8. Main Screen

### Implementiert

- Track-Spinner
- Start
- Stop
- Live-Status
- Sample Count
- Live longitudinal/lateral
- geschaetzte Rundendauer
- `Load last session`
- `Browse sessions`
- Navigation zu Laps und Comparison

## 9. Lap Screen

### Implementiert

- Liste erkannter Laps
- Lap Time
- Anzahl Samples
- Anzahl Brems-Peaks
- Anzahl Cornering-Peaks

## 10. Comparison Screen

### Implementiert

- Auswahl Lap A und Lap B
- interpolierte Normierung per `LapNormalizer`
- Longitudinal-Chart
- Lateral-Chart
- Delta-Chart
- Marker fuer Peaks
- textliche Zusammenfassung
- heuristische Fahrstil-Insights

## 11. Lifecycle und Zustandsverwaltung

### Implementiert

- gemeinsames `SessionViewModel`
- `StateFlow` fuer Recording-State, Tracks, gespeicherte Sessions und Comparison-State
- `SensorRecorder` als `DefaultLifecycleObserver`
- Session-Laden aktualisiert `SessionRepository` und UI-State

### Was jetzt robust ist

- Sessions ueberleben App-Neustarts
- letzter Run kann schnell wieder geladen werden
- Sessions sind nach Track organisiert

## Bekannte Grenzen

- Kein Foreground Service, daher keine echte Hintergrundaufnahme fuer lange Sessions
- Noch kein periodisches Autosave waehrend einer laufenden Session
- Keine Exportfunktion nach CSV oder externem Share
- Keine echte 3D-Orientierungsfusion fuer vollstaendig freie Telefonlage
- `yawRateAbs` basiert nur auf `gyroZ`, nicht auf vollstaendig richtungsunabhaengiger Rotationsmagnitude
- Lap-Detection ist heuristisch und nicht gegen reale Referenzdaten validiert

## Offene Punkte

## Fachlich offen

- bessere Strecke-spezifische Historiennutzung fuer Lap-Detection
- robustere Erkennung von Outlaps, Inlaps und Unterbrechungen
- Sektorzeiten oder abschnittsweise Vergleiche
- genauere Zeitverlustanalyse statt nur Signal-Differenzen

## Technisch offen

- echtes Autosave waehrend Recording
- Export als CSV oder JSON ausserhalb des App-Verzeichnisses
- Foreground Service fuer laengere Sessions
- Unit-Tests fuer:
  - `LapDetector`
  - `SessionStorageManager`
  - `TrackManager`
  - `LapNormalizer`
- UI-Tests fuer:
  - Track-Erstellung
  - Session-Browsing
  - Load-Last-Session-Flow
- Build- und Laufvalidierung in echter Android-Umgebung

## UX offen

- klarerer Setup-Hinweis fuer Pocket-Nutzung
- bessere Fehlertexte bei unzureichender Kalibrierung
- visuelle Kennzeichnung geladenen Sessions gegenueber frisch aufgenommenen Sessions
- semantische Farbsegmente innerhalb derselben Lap:
  - gruen fuer Beschleunigen
  - rot fuer Bremsen
  - blau fuer Cornering

## Empfohlene naechste Schritte

1. App in Android Studio auf einem echten Geraet bauen und testen.
2. Mehrere Sessions je Track aufzeichnen und die Schwellwerte fuer Event-Detection feinjustieren.
3. Autosave waehrend Recording ergaenzen.
4. Exportfunktion fuer Sessions hinzufuegen.
5. Tests fuer Persistenz und Lap-Detection nachziehen.

## Relevante Dateien

- `app/src/main/java/com/kartingtracker/data/SessionRepository.kt`
- `app/src/main/java/com/kartingtracker/data/SessionStorageManager.kt`
- `app/src/main/java/com/kartingtracker/data/TrackManager.kt`
- `app/src/main/java/com/kartingtracker/data/Track.kt`
- `app/src/main/java/com/kartingtracker/sensor/SensorRecorder.kt`
- `app/src/main/java/com/kartingtracker/sensor/CalibrationManager.kt`
- `app/src/main/java/com/kartingtracker/sensor/LowPassFilter.kt`
- `app/src/main/java/com/kartingtracker/domain/LapDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/PeakDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/LapNormalizer.kt`
- `app/src/main/java/com/kartingtracker/domain/DrivingInsightsGenerator.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionViewModel.kt`
- `app/src/main/java/com/kartingtracker/ui/main/MainFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/laps/LapsFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/comparison/ComparisonFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/sessions/SessionListFragment.kt`
