# Projektdokumentation Karting Tracker

## Zweck und Scope

Karting Tracker ist eine Android-App zur Analyse von Indoor-Kartfahrten mit Smartphone-Sensoren ohne GPS.

Die App:

- zeichnet Sensordaten waehrend einer Session auf
- verarbeitet die Rohdaten direkt auf dem Geraet
- erkennt Runden heuristisch
- speichert Sessions dauerhaft als JSON
- erlaubt das Laden alter Sessions
- stellt Runden visuell gegenueber

Der aktuelle Stand ist eine praktisch nutzbare Version fuer reale Testfahrten, aber keine vollstaendig validierte Rennanalyse-Plattform.

## Zusammenfassung des Ist-Stands

### Implementiert

- Start- und Stop-Aufnahme
- 2-Sekunden-Kalibrierung vor Recording
- Aufnahme von Accelerometer und Gyroscope mit `SENSOR_DELAY_FASTEST`
- Low-Pass-Filter fuer beide Sensorstroeme
- Gravitationsermittlung und Gravitationentfernung
- kompatible Richtungswerte:
  - `longitudinalAccel`
  - `lateralAccel`
- robuste pocket-taugliche Signale:
  - `totalAcceleration`
  - `yawRateAbs`
- Lap-Detection mit Sliding Window, Korrelation, Event-Erkennung und Confidence
- Outlap-Markierung fuer eine instabile erste Runde
- Disturbed-Lap-Klassifikation fuer spaete oder unplausible Runden
- automatische Sektor-Erkennung pro Lap
- Sektorzeiten pro Lap
- Peak-Detection fuer Bremsen und Cornering
- lineare Interpolation fuer Lap-Normalisierung
- Comparison Screen mit Overlay-Charts und Zeitverlust-Chart
- Sektorvergleich zwischen zwei Laps
- einfache textliche Fahrstil-Insights
- persistente Session-Speicherung als JSON
- periodisches Autosave waehrend Recording
- Track-Verwaltung
- Track-spezifisches Lernen ueber `TrackProfile`
- Session-Browsing mit Filter
- Laden der letzten Session
- Laden gespeicherter Sessions in den aktiven App-State
- Debug-Erzeugung einer simulierten Test-Session

### Nicht implementiert

- Foreground Service fuer robuste Langzeit- oder Hintergrundaufnahme
- Exportfunktion nach CSV
- Teilen von Sessions ausserhalb des App-Verzeichnisses
- ideal lap / best-sector-Auswertung ueber mehrere Laps oder Sessions
- vollstaendig orientierungsunabhaengige Richtungsdiagramme
- Sensorfusion mit echter Pose-/Orientierungsrekonstruktion
- automatisierte Tests
- Laufvalidierung in dieser lokalen Umgebung

## Simulationsdaten fuer Debug

Es gibt jetzt einen zusaetzlichen Utility-Pfad fuer Entwicklung und Demo:

- `SimulatedSessionGenerator.generateSession(trackName)`
- erzeugt eine vollstaendige `Session` mit kompatiblen `SensorSample`-, `Lap`- und `Session`-Strukturen
- schreibt im Debug-Build einmalig eine Session fuer `Test Track` in den bestehenden JSON-Speicherpfad

Ziel:

- Session-Browser ohne echte Fahrdaten pruefbar machen
- Lap-Liste, Comparison, Charts und Marker mit realistischeren Testdaten pruefbar machen

Aktuelles Verhalten:

- Sampling alle 50 ms
- etwa 8 bis 9 Laps
- erste Lap langsamer und als Outlap markiert
- simulierte Bremszonen, Straights und Cornering-Zonen
- gespeicherte Session wird von der App wie eine normale Session geladen

## Architektur

## Schichten

- `data`
  - Datenmodelle `SensorSample`, `Lap`, `Session`, `Track`, `TrackProfile`
  - `SessionRepository` als zentrale Sitzungs- und Zustandslogik
  - `SessionStorageManager` fuer JSON-Persistenz
  - `TrackManager` fuer persistente Track-Verwaltung
  - `TrackProfileManager` fuer Profil-Persistenz und Profilaufbau aus Sessions
- `sensor`
  - `SensorRecorder` fuer Android-Sensorzugriff und Aufnahmesteuerung
  - `CalibrationManager` fuer Gravitationsermittlung und Projektion
  - `LowPassFilter` fuer einfache Signalglaettung
- `domain`
  - `LapDetector` fuer heuristische Rundenerkennung
  - `SectorDetector` fuer heuristische Sektorgrenzen und Sektorzeiten
  - `PeakDetector` fuer Brems- und Cornering-Peaks
  - `LapNormalizer` fuer interpolierte Vergleichskurven
  - `TimeLossCalculator` fuer leichte Zeitverlust-Approximation
  - `DrivingInsightsGenerator` fuer einfache Heuristiken
- `ui`
  - `SessionViewModel` als zentraler State-Halter
  - `MainFragment`, `LapsFragment`, `ComparisonFragment`, `SessionListFragment`

## Zentrale Designentscheidung

Die App verwendet zwei Signalarten parallel:

- kompatible Richtungswerte fuer Charts und bestehende UI:
  - `longitudinalAccel`
  - `lateralAccel`
- robustere Signale fuer Realbetrieb und Lap-Detection:
  - `totalAcceleration`
  - `yawRateAbs`

Damit bleibt die bestehende Visualisierung nutzbar, waehrend die Rundenerkennung weniger von der Telefonlage abhaengt.

## Datenmodell

## SensorSample

`SensorSample` enthaelt pro Zeitpunkt:

- `timestampNs`
- `accelX`, `accelY`, `accelZ`
- `gyroX`, `gyroY`, `gyroZ`
- `longitudinalAccel`
- `lateralAccel`
- `totalAcceleration`
- `yawRateAbs`

Wichtig:

- die rohen Sensorwerte bleiben erhalten
- die abgeleiteten Werte werden direkt mitgespeichert
- `yawRateAbs` ist die Magnitude des gesamten Gyro-Vektors, nicht `abs(gyroZ)`

## Lap

`Lap` enthaelt:

- `id`
- `samples`
- `lapTimeMs`
- `startTimestampNs`
- `endTimestampNs`
- `brakingPeakIndices`
- `corneringPeakIndices`
- `sectorBoundaries`
- `sectorTimesMs`
- `confidenceScore`
- `isOutlap`
- `isDisturbed`

Bedeutung:

- `confidenceScore` stammt aus der Rundenerkennung
- `isOutlap` markiert aktuell nur eine als instabil erkannte erste Runde
- `isDisturbed` markiert unplausible oder fahrerisch/verkehrsbedingt gestoerte Runden
- `sectorBoundaries` speichert interne Grenzpunkte auf 0-100-Skala
- `sectorTimesMs` speichert die daraus berechneten Abschnittszeiten

## Session

`Session` enthaelt:

- `id`
- `trackName`
- `startTimeEpochMs`
- `endTimeEpochMs`
- `startTimestampNs`
- `endTimestampNs`
- `samples`
- `laps`
- `estimatedLapTimeMs`

## Track

`Track` ist aktuell minimal:

- `name`

Es gibt noch keine Streckenmetadaten wie Laenge, Layout oder Indoor-Standort.

## TrackProfile

`TrackProfile` enthaelt pro Track:

- `trackName`
- `averageLapTimeMs`
- `lapTimeStdDevMs`
- `averageLapLengthSamples`
- `averageTotalAcceleration`
- `averageYawRateAbs`
- `typicalBrakingZones`
- `typicalCorneringZones`
- `typicalSectorBoundaries`
- `sessionCount`

## Datenfluss

1. Nutzer waehlt einen Track oder legt einen neuen Track an.
2. `SessionViewModel.startRecording()` ruft `SensorRecorder.startRecording()` auf.
3. `SensorRecorder` geht in `RecorderPhase.CALIBRATING`.
4. `CalibrationManager` sammelt fuer ca. 2 Sekunden Accel-Werte.
5. Nach abgeschlossener Kalibrierung startet `SessionRepository.startSession(...)`.
6. Waehren Recording erzeugt `SensorRecorder` fortlaufend `SensorSample`.
7. `SessionRepository.appendSample(...)` sammelt die Samples und aktualisiert Live-State.
8. Waehren Recording speichert das Repository alle 5 Sekunden einen Session-Snapshot.
9. Beim Stop ruft `SessionRepository.stopSession(...)` die Verarbeitung auf.
10. `LapDetector` erzeugt Laps.
11. `PeakDetector` berechnet Peak-Indizes pro Lap.
12. `SectorDetector` berechnet Sektorgrenzen und Sektorzeiten pro Lap.
13. `SessionRepository.classifyLaps(...)` markiert `isDisturbed`.
14. `SessionStorageManager` speichert die finale Session als JSON.
15. `TrackProfileManager.updateProfile(...)` aktualisiert das Track-Profil aus historischen Sessions.
16. `SessionViewModel` stellt Session, Lap-Liste und Comparison-State fuer die UI bereit.

## Recording und Sensorverarbeitung

## SensorRecorder

`SensorRecorder`:

- kapselt `SensorManager`
- registriert `TYPE_ACCELEROMETER` und `TYPE_GYROSCOPE`
- verwendet `SENSOR_DELAY_FASTEST`
- laeuft auf eigenem `HandlerThread`
- kennt drei Phasen:
  - `IDLE`
  - `CALIBRATING`
  - `RECORDING`

Lifecycle-Verhalten:

- bei `onStop()` werden Sensorlistener abgemeldet
- bei `onStart()` werden sie wieder registriert, falls Recording aktiv ist
- es gibt aber keinen Foreground Service

Konsequenz:

- die App ist lifecycle-aware innerhalb der sichtbaren App
- sie ist nicht auf echte Hintergrundrobustheit ausgelegt

## Low-Pass-Filter

Der aktuelle Filter ist bewusst einfach gehalten:

- ein Filter fuer Accelerometer
- ein Filter fuer Gyroscope
- Ziel ist Rauschreduktion, nicht Praezisionsrekonstruktion

## Kalibrierung

`CalibrationManager` nimmt fuer etwa 2 Sekunden stationaere Accelerometerdaten auf und berechnet:

- mittleren Gravitationsvektor
- normierten Gravitationsvektor
- angenaeherten Vorwaertsvektor in der Fahr-Ebene
- dazu orthogonalen Lateralvektor

Danach wird fuer jede Accel-Messung:

1. die Gravitation entfernt
2. die verbleibende Bewegung auf die Fahr-Ebene projiziert
3. daraus berechnet:
   - `longitudinalAccel`
   - `lateralAccel`
   - `totalAcceleration`

Wichtige Einschraenkung:

- `totalAcceleration` ist robust
- `longitudinalAccel` und `lateralAccel` bleiben nur angenaehert korrekt
- bei lockerer Taschenlage ist die Lap-Detection robuster als die Richtungsinterpretation der Diagramme

## Pocket-taugliche Signale

Fuer reale Nutzung mit unbekannter Orientierung verwendet die App zusaetzlich:

- `totalAcceleration`
  - Betrag der gravitationsbereinigten Beschleunigung
- `yawRateAbs`
  - Betrag der Gyro-Vektormagnitude `sqrt(gx^2 + gy^2 + gz^2)`

Diese Signale treiben die Rundenerkennung und Peak-Logik.

## Lap-Detection

## Ziel

Rundengrenzen sollen auch dann erkennbar bleiben, wenn die reinen Achssignale unzuverlaessig sind.

## Verwendete Signale

`LapDetector` arbeitet primaer mit:

- `totalAcceleration`
- `yawRateAbs`

Die Vergleichscharts nutzen dagegen weiterhin `longitudinalAccel` und `lateralAccel`.

## Algorithmus im aktuellen Code

1. Resampling der Session in 100-ms-Buckets.
2. Sliding Window ueber 60 Buckets, also etwa 6 Sekunden.
3. Test mehrerer Shift-Werte als Rundendauer-Kandidaten.
4. Kosinus-Aehnlichkeit zwischen aktuellem und historischem Fenster.
5. Event-Pruefung im Fenster:
   - braking-like: markanter Abfall in `totalAcceleration`
   - cornering-like: erhoehte `yawRateAbs` plus erhoehte `totalAcceleration`
6. Lokale Maxima der Aehnlichkeit werden als Boundary-Kandidaten betrachtet.
7. Kandidaten mit zu geringem Abstand werden dedupliziert.
8. Confidence wird berechnet aus:
   - Similarity
   - Event-Praesenz
   - Dauer-Konsistenz zum erwarteten Shift
9. Wenn ein `TrackProfile` existiert:
   - wird der Shift-Suchraum um die erwartete Rundenzeit verengt
   - wird historische Fenster-Aehnlichkeit mit Profil-Aehnlichkeit gemischt
   - werden bekannte Brems- und Cornering-Zonen leicht bevorzugt
10. Zwischen Boundary-Kandidaten werden Laps gebildet.
11. Laps ausserhalb von 15 bis 120 Sekunden werden verworfen.
12. Erste Runde wird separat als moegliche Outlap klassifiziert.
13. Danach werden weitere instabile Laps gegen den Mittelwert der nicht-Outlaps gefiltert.
14. Wenn keine stabile Segmentierung bleibt, faellt das System auf eine einzelne Lap zurueck.

## Outlap-Behandlung

Warum:

- die erste Runde ist oft untypisch wegen Anfahren, Sortieren, Aufwaermen oder unvollstaendigem Einstieg in die Strecke

Aktuelles Verhalten:

- nur die erste erkannte Runde wird speziell betrachtet
- Kriterien:
  - starke Abweichung der Rundendauer gegenueber den folgenden Runden
  - oder niedriger `confidenceScore`
- wenn auffaellig:
  - `isOutlap = true`
  - Runde bleibt sichtbar
  - sie wird aber standardmaessig nicht fuer Lap A oder Lap B vorausgewaehlt

Was noch nicht umgesetzt ist:

- explizite Erkennung von Inlaps
- Mehrfach-Outlaps
- getrennte Behandlung von Boxenstopps oder Unterbrechungen

## Disturbed-Lap-Behandlung

Zusatzlogik in `SessionRepository.classifyLaps(...)`:

- Referenz-Lap-Time wird nur aus Laps berechnet, die:
  - nicht Outlap sind
  - `confidenceScore >= 0.6` haben
- eine Lap wird als `isDisturbed = true` markiert, wenn mindestens eines gilt:
  - `lapTimeMs > avgLapTime * 1.15`
  - `confidenceScore < 0.5`
  - weniger als 2 Brems-Peaks
  - weniger als 2 Cornering-Peaks

Wichtig:

- `isOutlap` bleibt davon unberuehrt
- eine Runde kann gleichzeitig `isOutlap = true` und `isDisturbed = true` sein
- die UI bevorzugt fuer den Vergleich Laps, die weder Outlap noch Disturbed sind

## Peak-Detection

`PeakDetector` arbeitet ebenfalls mit pocket-tauglichen Signalen.

### Brems-Peaks

Heuristik:

- markanter Abfall in `totalAcceleration`
- lokales Minimum
- Mindestabstand zwischen Peaks

### Cornering-Peaks

Heuristik:

- hohe `yawRateAbs`
- ausreichend hohe `totalAcceleration`
- lokales Maximum
- Mindestabstand zwischen Peaks

Ergebnis:

- Peak-Indizes werden in `Lap` gespeichert
- Marker werden in den Charts angezeigt
- die Insight-Logik nutzt die normalisierten Markerpositionen

## Sektor-Erkennung

`SectorDetector` teilt eine Lap ohne GPS in 2 bis 4 Abschnitte.

Verwendete Signale:

- normalisierte `totalAcceleration`
- normalisierte `yawRateAbs`

Strategie:

1. `LapNormalizer.normalizeSignal(...)` bringt beide Signale auf dieselbe 0-100-Skala.
2. starke Minima in `totalAcceleration` werden als Bremszonen interpretiert.
3. starke Maxima in `yawRateAbs` werden als Cornering-Zonen interpretiert.
4. beide Punktmengen werden zusammengelegt.
5. zu nahe Punkte werden ueber Mindestabstand entfernt.
6. es bleiben 1 bis 3 interne Grenzpunkte.
7. daraus entstehen 2 bis 4 Sektoren pro Lap.
8. wenn keine stabilen Event-Punkte bleiben, verwendet der Detektor als Fallback eine mittige Grenze bei 50 Prozent.

Rueckgabe:

- `sectorBoundaries` enthaelt nur die internen Grenzpunkte
- Start `0` und Ende `100` werden implizit angenommen

## Sektorzeiten

`SectorDetector.computeSectorTimes(...)` berechnet aus den Prozent-Grenzen reale Abschnittszeiten:

1. Prozentgrenzen werden auf Sample-Indizes der Original-Lap abgebildet.
2. Start und Ende der Lap werden hinzugefuegt.
3. die Zeitdifferenz zwischen den Timestamp-Grenzen ergibt die Sektorzeit in Millisekunden.

Wichtig:

- die Zeit kommt aus den originalen Sample-Timestamps
- die Grenzen kommen aus dem sensorbasierten Pattern
- dadurch bleibt die Loesung indoor-tauglich und GPS-frei

## Lap-Normalisierung und Comparison

## LapNormalizer

Der Normalisierer veraendert die Rohsamples nicht.

Stattdessen:

- jede Lap bleibt roh gespeichert
- fuer die Visualisierung wird eine neue normalisierte Kurve erzeugt

Aktuelle Normalisierung:

- lineare Interpolation
- standardmaessig `251` Zielpunkte
- X-Achse 0-100 Prozent

Interpoliert werden aktuell:

- `longitudinalAccel`
- `lateralAccel`

Peak-Marker:

- werden anhand der Original-Peak-Indizes auf 0-100 Prozent umgerechnet

## Comparison Screen

Der Comparison Screen zeigt:

- Spinner fuer Lap A und Lap B
- Longitudinal-Overlay
- Lateral-Overlay
- Time-Loss-Graph
- Sektorvergleich als Textblock
- Peak-Marker
- Summary-Text
- 2 bis 4 einfache Insights

Default-Selektion:

- bevorzugt zwei Laps, die weder Outlap noch Disturbed sind
- faellt danach auf nicht-Outlap-Laps zurueck
- faellt danach auf nicht-Disturbed-Laps zurueck
- faellt zuletzt auf beliebige vorhandene Laps zurueck

## Time-Loss-Graph

Der dritte Chart zeigt jetzt eine angenaeherte reale Zeitdifferenz statt reiner Signaldifferenz.

Interpretation:

- positive Werte bedeuten: Lap A ist an dieser Stelle langsamer als Lap B
- negative Werte bedeuten: Lap A ist an dieser Stelle schneller als Lap B

Implementierung in `TimeLossCalculator`:

1. `LapNormalizer.normalizeSignal(...)` normalisiert `totalAcceleration` beider Laps auf dieselbe Punktzahl.
2. Die normierte Beschleunigung wird mit festem `dt = 0.1 s` zu einer einfachen Geschwindigkeitskurve integriert.
3. Aus der Geschwindigkeitskurve wird eine kumulative Zeitkurve mit konstantem Distanzschritt abgeleitet.
4. Die Zeitkurven werden auf die reale Lap-Time skaliert.
5. `timeA - timeB` ergibt den Zeitverlust entlang der Runde.

Grenzen:

- das ist eine leichte Approximation
- keine absolute Fahrzeuggeschwindigkeit
- keine echte Distanzmessung
- sinnvoller als das bisherige Signal-Delta, aber keine Referenzmessung

## Sektorvergleich

Der Comparison Screen erzeugt zusaetzlich kompakte Sektor-Deltas:

- `S1: +0.12s`
- `S2: -0.08s`
- `S3: +0.30s`

Bedeutung:

- positives Delta: Lap A ist in diesem Sektor langsamer
- negatives Delta: Lap A ist in diesem Sektor schneller

## Driving Insights

`DrivingInsightsGenerator` nutzt einfache Heuristiken:

- spaeteres Bremsen aus Markerpositionen
- hoehere Cornering-Last aus maximaler lateraler Beschleunigung
- bessere positive Beschleunigung aus Mittel positiver longitudinaler Werte

Wichtig:

- die Texte sind bewusst einfach
- sie sind keine belastbare Rennfahrerbewertung
- bei lockerer Telefonlage kann die lap-basierte Erkennung sinnvoller sein als die Richtungsinterpretation der Insights

## Persistenz

## SessionStorageManager

Sessions werden als JSON-Dateien in app-spezifischem Speicher abgelegt.

Speicherort:

- `context.filesDir/sessions`

Dateiname:

- `session_<trackName>_<startTimeEpochMs>.json`

Sanitizing:

- nicht erlaubte Zeichen im Tracknamen werden fuer den Dateinamen ersetzt

Gespeicherte Inhalte:

- Session-Metadaten
- alle Rohsamples
- alle berechneten Laps
- Peak-Indizes
- Outlap- und Disturbed-Flags
- Sektorgrenzen und Sektorzeiten
- geschatzte Rundenzeit

Die App nutzt derzeit keine Datenbank.

Zusatz fuer Debug:

- simulierte Sessions werden ueber denselben `SessionStorageManager.saveSession(...)`-Pfad gespeichert
- dadurch landen sie im normalen Session-Verzeichnis und erscheinen direkt in `SessionListFragment`

## SessionRepository

`SessionRepository` ist die zentrale Fachlogik.

Verantwortung:

- Recording-Status
- laufende Samples
- aktuelle Session
- letzte Session
- gespeicherte Sessions
- aktiver Track
- aktuelles Track-Profil
- Autosave
- finale Verarbeitung bei Stop
- Rehydration geladener Sessions
- Lap-Klassifikation
- Profil-Update nach Sessionende

Wichtige States:

- `isRecording`
- `sampleCount`
- `lastSample`
- `latestSession`
- `currentSession`
- `storedSessions`
- `availableTracks`
- `currentTrackName`
- `currentTrackProfile`

## Autosave

Waehrend Recording:

- alle 5 Sekunden wird ein Snapshot geschrieben
- dieselbe Session-Datei wird ueberschrieben
- die Datei ist ueber `trackName + startTimeEpochMs` stabil identifizierbar

Finalisierung:

- bei `stopSession()` wird die Session voll verarbeitet und erneut gespeichert

Recovery:

- wenn eine gespeicherte Session Samples, aber noch keine Laps enthaelt, verarbeitet das Repository sie beim Laden nach
- wenn eine gespeicherte Session bereits Laps, aber noch keine Sektor-Metadaten enthaelt, werden diese beim Laden nacherzeugt
- geladene Laps werden beim Laden erneut klassifiziert, damit `isDisturbed` auch fuer aeltere Dateien konsistent bleibt

Grenze:

- Autosave reduziert Datenverlust
- es ersetzt keinen Foreground Service und keine garantierte Hintergrundausfuehrung

## Track Management

`TrackManager` speichert Tracks in `SharedPreferences`.

Aktuelles Verhalten:

- Tracks werden als String-Set gehalten
- der zuletzt ausgewaehlte Track wird gespeichert
- Standardwert ist `General Track`

Aktuell nicht vorhanden:

- Track-Loeschen
- Umbenennen
- Reihenfolge/Sortierung nach letzter Nutzung
- Streckenprofile oder Metadaten

## Track-spezifisches Lernen

`TrackProfileManager` speichert Profile als JSON unter:

- `context.filesDir/track_profiles/track_<trackName>.json`

Profilaufbau:

1. Sessions des Tracks laden
2. Nur Sessions mit mindestens 2 brauchbaren Laps verwenden
3. Outlaps, Disturbed-Laps und Laps mit geringer Confidence ignorieren
4. `totalAcceleration` und `yawRateAbs` aller gueltigen Laps auf 101 Punkte normalisieren
5. Mittelkurven bilden
6. typische Brems- und Cornering-Zonen als Minima/Maxima extrahieren
7. typische Sektorgrenzen aus historischen Laps mitteln
8. Mittelwert und Standardabweichung der Lap-Time speichern

Nutzung in neuer Session:

- falls Profil vorhanden, wird die Lap-Detection frueh und enger um die erwartete Rundenzeit gesucht
- falls Profil vorhanden, werden typische Sektorgrenzen fuer neue Laps wiederverwendet
- Profile mit `sessionCount < 2` wirken absichtlich schwacher, um Ueberanpassung zu vermeiden

UI:

- Main Screen zeigt an, ob fuer den gewaehlten Track bereits ein Profil benutzt wird

## UI und Navigation

## Main Screen

Aktuelle Funktionen:

- Track-Spinner
- neuen Track anlegen
- Start
- Stop
- Live-Status
- Sample Count
- Live `longitudinalAccel`
- Live `lateralAccel`
- erkannte Lap-Anzahl
- geschaetzte Rundenzeit
- Hinweis, ob ein Track-Profil aktiv genutzt wird
- `Load last session`
- `Browse sessions`
- Navigation zu Lap-Liste und Comparison

Hinweis:

- die Live-Werte zeigen nur die kompatiblen Richtungswerte, nicht `totalAcceleration` oder `yawRateAbs`

## Lap Screen

Aktuelle Darstellung pro Lap:

- Lap-Nummer
- Outlap-Markierung
- Disturbed-Markierung
- Rundendauer
- Sample-Anzahl
- Anzahl Brems-Peaks
- Anzahl Cornering-Peaks
- Sektorzeiten je Lap
- Confidence-Wert

## SessionListFragment

Aktuelle Funktionen:

- alle gespeicherten Sessions auflisten
- Filter nach Track
- pro Session anzeigen:
  - Trackname
  - Datum
  - Lap-Anzahl
  - Sample-Anzahl
- Session laden
- direkt zu Laps oder Comparison navigieren

## Laden gespeicherter Sessions

Beim Laden einer Session:

- `SessionRepository.loadSession(...)` setzt `currentSession`
- `SessionViewModel` bezieht `laps` aus `currentSession`
- Comparison-State wird neu berechnet
- Standardauswahl fuer Lap A und Lap B wird zurueckgesetzt
- Charts werden ueber die Fragment-Observer neu gezeichnet

Das war ein frueherer Fehlerpfad und ist jetzt explizit behoben.

## Anforderungsmatrix

| Bereich | Anforderung | Status | Umsetzung |
|---|---|---|---|
| Recording | Start/Stop | Erfuellt | Main Screen ueber `SessionViewModel` und `SensorRecorder` |
| Recording | Live-Indikator | Erfuellt | `SessionUiState.statusLabel` |
| Recording | Accel + Gyro lesen | Erfuellt | `SensorRecorder` |
| Recording | Schnellste Rate | Erfuellt | `SENSOR_DELAY_FASTEST` |
| Recording | Kalibrierung vor Session | Erfuellt | `CalibrationManager`, ca. 2 Sekunden |
| Verarbeitung | Low-Pass-Filter | Erfuellt | separate Filter fuer Accel und Gyro |
| Verarbeitung | Gravitation entfernen | Erfuellt | `CalibrationManager.projectAcceleration()` |
| Verarbeitung | Pocket-taugliche Signale | Erfuellt | `totalAcceleration`, `yawRateAbs` |
| Datenmodell | `SensorSample`, `Lap`, `Session`, `Track` | Erfuellt | vorhanden und persistent speicherbar |
| Datenmodell | `TrackProfile` | Erfuellt | gespeichert als JSON pro Track |
| Lap Detection | Sliding Window + Korrelation | Erfuellt | `LapDetector` |
| Lap Detection | Event-Erkennung | Erfuellt | braking/cornering checks |
| Lap Detection | Confidence | Erfuellt | Similarity * Event * Dauer-Konsistenz |
| Lap Detection | Outlap-Erkennung | Erfuellt | erste instabile Runde wird markiert |
| Lap Detection | Disturbed-Lap-Klassifikation | Erfuellt | spaete, unplausible oder peak-arme Laps werden markiert |
| Lap Detection | Sektor-Erkennung | Erfuellt | `SectorDetector` findet interne Grenzpunkte aus Brems- und Cornering-Zonen |
| Lap Detection | Fallback | Erfuellt | einzelne Lap bei instabiler Segmentierung |
| Persistenz | JSON-Speicherung | Erfuellt | `SessionStorageManager` |
| Persistenz | Autosave | Erfuellt | Repository-Snapshot alle 5 Sekunden |
| Persistenz | Session-Laden | Erfuellt | alle Sessions, Track-spezifisch, letzte Session |
| Track Learning | Profil pro Track speichern | Erfuellt | `TrackProfileManager` |
| Track Learning | Profil in Lap-Detection nutzen | Erfuellt | engerer Shift-Suchraum und Profil-Bias |
| Track Learning | Sektorgrenzen wiederverwenden | Erfuellt | `typicalSectorBoundaries` im Track-Profil |
| Track Management | Track auswaehlen/anlegen | Erfuellt | Main Screen + `TrackManager` |
| Session Browsing | Liste, Filter, Laden | Erfuellt | `SessionListFragment` |
| Visualisierung | Lap-Overlay | Erfuellt | Comparison Screen |
| Visualisierung | Time-Loss-Graph | Erfuellt | `TimeLossCalculator` + Comparison Screen |
| Visualisierung | Sektorvergleich | Erfuellt | Sektor-Deltas im Comparison Screen |
| Visualisierung | Sektorzeiten pro Lap | Erfuellt | Lap-Liste zeigt Abschnittszeiten |
| Visualisierung | Peak-Marker | Erfuellt | Bremsen und Cornering |
| Insights | Text-Feedback | Erfuellt | `DrivingInsightsGenerator` |
| Robustheit | Orientation-unabhaengige Yaw-Erkennung | Erfuellt | Gyro-Magnitude |
| Robustheit | Voll orientierungsfreie Richtungsdiagramme | Nicht erfuellt | Charts nutzen weiterhin angenaeherten Richtungsbezug |
| Betrieb | Foreground Service | Nicht erfuellt | derzeit nicht vorhanden |
| Export | CSV/Share | Nicht erfuellt | derzeit nicht vorhanden |
| Qualitaet | Automatisierte Tests | Nicht erfuellt | derzeit nicht vorhanden |

## Bekannte Grenzen

- kein Foreground Service
- keine garantierte Hintergrundaufnahme bei aggressivem Android-App-Management
- keine vollstaendige Pose-/Orientierungsrekonstruktion
- `longitudinalAccel` und `lateralAccel` bleiben bei wechselnder Telefonlage nur angenaehert interpretierbar
- Insights sind heuristisch
- Lap-Detection ist heuristisch und nicht gegen Referenz-Transponder validiert
- Time-Loss ist eine Approximation aus Beschleunigung, nicht echte Fahrzeugzeitmessung
- Sektorgrenzen sind heuristisch aus Musterpunkten abgeleitet, nicht physisch vermessen
- keine Exportfunktion
- keine Tests im Projekt

## Was noch offen ist

## Fachlich offen

- robustere Erkennung von Inlaps und Unterbrechungen
- streckenspezifische Nutzung historischer Sessions fuer bessere Lap-Detection
- ideal lap / best sectors ueber mehrere Sessions
- genauere Zeitverlustanalyse ueber echte Distanz- oder Geschwindigkeitsreferenzen
- moegliche alternative Vergleichsmodi auf Basis von `totalAcceleration` und `yawRateAbs`

## Technisch offen

- Foreground Service fuer Recording
- Exportfunktion
- Loeschen und Umbenennen von Tracks
- Loeschen oder Archivieren alter Sessions
- Tests fuer:
  - `LapDetector`
  - `SessionRepository`
  - `SessionStorageManager`
  - `TrackManager`
  - `LapNormalizer`
- UI-Tests fuer:
  - Track-Erstellung
  - Session-Laden
  - Comparison-State nach Sessionwechsel

## UX offen

- klarere Hinweise fuer Pocket-Nutzung
- bessere Fehlermeldungen bei schlechter Kalibrierung
- deutlicherer Unterschied zwischen frisch aufgenommener und geladener Session
- Visualisierung der robusten Signale direkt im UI
- moegliche Warnung, wenn nur Outlap oder nur eine einzige Lap vorliegt

## Empfohlene naechste Schritte

1. Projekt in Android Studio auf echtem Android-Geraet bauen und testen.
2. Mehrere Sessions mit fester und loser Telefonlage aufzeichnen.
3. Schwellenwerte der Lap- und Peak-Erkennung anhand echter Fahrdaten feinjustieren.
4. Foreground Service ergaenzen.
5. Exportfunktion einfuehren.
6. Tests fuer Persistenz und Lap-Detection nachziehen.

## Relevante Dateien

- `app/src/main/java/com/kartingtracker/data/SensorSample.kt`
- `app/src/main/java/com/kartingtracker/data/Lap.kt`
- `app/src/main/java/com/kartingtracker/data/Session.kt`
- `app/src/main/java/com/kartingtracker/data/Track.kt`
- `app/src/main/java/com/kartingtracker/data/TrackProfile.kt`
- `app/src/main/java/com/kartingtracker/data/SessionRepository.kt`
- `app/src/main/java/com/kartingtracker/data/SessionStorageManager.kt`
- `app/src/main/java/com/kartingtracker/data/TrackManager.kt`
- `app/src/main/java/com/kartingtracker/data/TrackProfileManager.kt`
- `app/src/main/java/com/kartingtracker/data/SimulatedSessionGenerator.kt`
- `app/src/main/java/com/kartingtracker/AppContainer.kt`
- `app/src/main/java/com/kartingtracker/sensor/SensorRecorder.kt`
- `app/src/main/java/com/kartingtracker/sensor/CalibrationManager.kt`
- `app/src/main/java/com/kartingtracker/sensor/LowPassFilter.kt`
- `app/src/main/java/com/kartingtracker/domain/LapDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/SectorDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/PeakDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/LapNormalizer.kt`
- `app/src/main/java/com/kartingtracker/domain/TimeLossCalculator.kt`
- `app/src/main/java/com/kartingtracker/domain/DrivingInsightsGenerator.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionViewModel.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionUiModels.kt`
- `app/src/main/java/com/kartingtracker/ui/main/MainFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/laps/LapsFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/comparison/ComparisonFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/sessions/SessionListFragment.kt`

## Hinweis zur Build-Validierung

In dieser Arbeitsumgebung standen kein Java, kein Gradle und kein Android SDK zur Verfuegung. Die Dokumentation wurde deshalb gegen den vorhandenen Quellcode abgeglichen, aber nicht durch einen echten lokalen Android-Build verifiziert.
