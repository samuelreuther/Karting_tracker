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

Wichtig fuer dieses Dokument:

- Abschnitte mit "aktueller Stand" beschreiben den heute implementierten Code
- explizit als "offen" markierte Abschnitte beschreiben noch nicht umgesetzte Weiterentwicklungen

## Zusammenfassung des Ist-Stands

### Implementiert

- Start- und Stop-Aufnahme
- Foreground Service fuer Recording mit permanenter Notification
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
- globale Lap-Detection mit Boundary-Generierung, Segment-Scoring und dynamischer Optimierung ueber die gesamte Session
- explizite Lap-Phasen:
  - `NORMAL`
  - `OUTLAP`
  - `INLAP`
  - `INTERRUPTED`
- kalibriertes Lap-Confidence-Modell mit normalisierten Teil-Scores
- Disturbed-Lap-Klassifikation fuer spaete oder unplausible Runden
- Session-Quality-Bewertung pro verarbeiteter Session
- automatische Sektor-Erkennung pro Lap
- stabile Sektorverwendung ueber `TrackProfile.typicalSectorBoundaries`
- Sektorzeiten pro Lap
- Peak-Detection fuer Bremsen und Cornering
- lineare Interpolation fuer Lap-Normalisierung
- Comparison Screen mit Overlay-Charts und Zeitverlust-Chart
- Comparison Screen mit Track-Map-Overlay fuer Segmentmarker
- Sektorvergleich zwischen zwei Laps
- Ideal-Lap-Berechnung aus Best-Sektoren
- strukturierte Telemetrie-Insights mit Segmentanalyse
- Theoretical-Best-Lap-Berechnung pro Session
- Top-Time-Loss-Map und Chart-Marker fuer schwache Segmente
- Track-Layout-Editor mit Bildimport, Startpunkt, Fahrtrichtung und manueller Kurvenpflege
- automatische Centerline-Extraktion aus Track-Map-Bildern mit deterministischer Kurvenklassifikation (`TIGHT`/`MEDIUM`/`FAST`)
- automatische Projektion erkannter Schwachstellen auf Track-Maps mit Corner-Fallback ohne Bildzwang
- persistente Session-Speicherung als JSON
- Anzeige von Sample-Count und Dateigroesse im Session-Browser
- Reprocessing gespeicherter Sessions aus Rohdaten mit Processing-Versionierung
- periodisches Autosave waehrend Recording mit separaten Partial-Snapshots
- Track-Verwaltung
- dropdown-basierte Track-Auswahl mit persistierter Letztwahl und Duplicate-Schutz
- Track-spezifisches Lernen ueber `TrackProfile`
- geschuetztes Track-Learning mit Quality-Guard, Ausreisserfilter und Profil-Reifegrad
- Session-Browsing mit Filter
- Loeschen einzelner Sessions
- Loeschen kompletter Tracks inklusive Sessions und Track-Profil
- Laden der letzten Session
- Laden gespeicherter Sessions in den aktiven App-State
- Debug-Erzeugung von drei simulierten 10-Minuten-Test-Sessions

### Nicht implementiert

- Exportfunktion nach CSV
- Teilen von Sessions ausserhalb des App-Verzeichnisses
- echte Wiederaufnahme einer bereits laufenden Sensoraufnahme nach Prozess-Tod oder Reboot
- vollstaendig orientierungsunabhaengige Richtungsdiagramme
- Sensorfusion mit echter Pose-/Orientierungsrekonstruktion
- keine vollstaendige End-to-End-Testabdeckung; gezielte Domain-Unit-Tests fuer Kurvenklassifikation vorhanden
- Laufvalidierung in dieser lokalen Umgebung

## Simulationsdaten fuer Debug

Es gibt jetzt einen zusaetzlichen Utility-Pfad fuer Entwicklung und Demo:

- `SimulatedSessionGenerator.generateSession(trackName)`
- `SimulatedSessionGenerator.generateSeededSession(trackName, seed, durationMinutes = 10)`
- erzeugt eine vollstaendige `Session` mit kompatiblen `SensorSample`-, `Lap`- und `Session`-Strukturen
- schreibt im Debug-Build einmalig drei Sessions fuer `Test Track` in den bestehenden JSON-Speicherpfad

Ziel:

- Session-Browser ohne echte Fahrdaten pruefbar machen
- Lap-Liste, Comparison, Charts und Marker mit realistischeren Testdaten pruefbar machen

Aktuelles Verhalten:

- Sampling alle 50 ms
- etwa 10 Minuten Laufzeit
- ca. 12.000 Samples pro Session
- ca. 23 bis 25 Laps mit Ziel-Lap-Time von etwa 24 bis 26 Sekunden
- pro Lap realistische Variation bei Bremsintensitaet, Bremspunkt, Cornering-Aggressivitaet und Exit-Acceleration
- gelegentliche leicht unperfekte Laps mit milder Stoerung, aber weiter detektierbaren Peaks
- gespeicherte Sessions werden von der App wie normale Sessions geladen

## Architektur

## Schichten

- `data`
  - Datenmodelle `SensorSample`, `Lap`, `Session`, `SessionQuality`, `Track`, `TrackProfile`
  - `SessionRepository` als zentrale Sitzungs- und Zustandslogik
  - `SessionStorageManager` fuer JSON-Persistenz
  - `TrackManager` fuer persistente Track-Verwaltung
  - `TrackProfileManager` fuer Profil-Persistenz und Profilaufbau aus Sessions
  - `TrackLayoutManager` fuer persistente Layoutdaten je Strecke
  - `TrackMapManager` fuer Map-Metadaten, Bundled-Asset-Seeding und Fallback-Kurven
- `sensor`
  - `SensorRecorder` fuer Android-Sensorzugriff und Aufnahmesteuerung
  - `CalibrationManager` fuer Gravitationsermittlung und Projektion
  - `LowPassFilter` fuer einfache Signalglaettung
- `service`
  - `RecordingForegroundService` fuer background-sicheres Recording
  - `RecordingNotificationHelper` fuer Notification-Channel und laufende Status-Notification
- `domain`
  - `LapDetector` als oeffentlicher Einstiegspunkt fuer Rundenerkennung
  - `LapDetector2` fuer globale Segmentierung und kalibrierte Lap-Confidence
  - `BoundaryGenerator` fuer Boundary-Evidenz und Boundary-Kandidaten
  - `GlobalSegmenter` fuer globale Session-Segmentierung per Dynamic-Programming-artiger Optimierung
  - `SectorDetector` fuer heuristische Sektorgrenzen und Sektorzeiten
  - `PeakDetector` fuer Brems- und Cornering-Peaks
  - `LapNormalizer` fuer interpolierte Vergleichskurven
  - `TimeLossCalculator` fuer stabilisierte Zeitverlust-Approximation
  - `TimeLossResult` als internes Ergebnis mit Delta-Kurve und Confidence
  - `SessionQualityEvaluator` fuer Session-Qualitaetsbewertung
  - `DrivingCoachAnalyzer` fuer Session-Telemetrieanalyse, Zeitverlust-Ursachen und Coaching
  - `MapOverlayProjector` fuer Zuordnung von Zeitverlust-Segmenten auf Kartenmarker
  - `TrackLayoutMapper` fuer Kurvensortierung und Mapping zwischen Detection und Layout
- `ui`
  - `SessionViewModel` als zentraler State-Halter
  - `MainFragment`, `LapsFragment`, `ComparisonFragment`, `SessionListFragment`, `TrackLayoutFragment`

## Zentrale Designentscheidung

Die App verwendet zwei Signalarten parallel:

- kompatible Richtungswerte fuer Charts und bestehende UI:
  - `longitudinalAccel`
  - `lateralAccel`
- robustere Signale fuer Realbetrieb und Lap-Detection:
  - `totalAcceleration`
  - `yawRateAbs`

Damit bleibt die bestehende Visualisierung nutzbar, waehrend die Rundenerkennung weniger von der Telefonlage abhaengt.

Zusatz seit v21:

- Vergleichsmarker koennen auf einer hinterlegten Streckenkarte visualisiert werden
- wenn keine Karte verfuegbar ist, bleibt eine textuelle Fallback-Darstellung aktiv
- wenn nur eine Karte ohne manuelle Kurvenpunkte vorhanden ist, wird ein gleichmaessiger Corner-Fallback fuer Markerpositionen genutzt

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
- `lapPhase`
- `isOutlap`
- `isDisturbed`

Bedeutung:

- `confidenceScore` stammt aus dem kalibrierten Confidence-Modell der Rundenerkennung
- `lapPhase` kann aktuell sein:
  - `NORMAL`
  - `OUTLAP`
  - `INLAP`
  - `INTERRUPTED`
- `isOutlap` bleibt als kompatibles Ableitungsfeld erhalten
- `isDisturbed` markiert unplausible, gestoerte oder fuer Vergleiche/Learning ungeeignete Runden bzw. Segmente
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
- `insights`
- `theoreticalBestLapTimeMs`
- `topTimeLossSegments`
- `segmentMarkers`
- `quality`
- `processingVersion`
- `isPartial`

`quality` ist optional, weil rohe Autosave-Snapshots noch keine verarbeiteten Laps enthalten.

`insights` speichert bis zu fuenf textliche Coaching-Hinweise pro final verarbeiteter Session.

`theoreticalBestLapTimeMs` speichert die theoretische Bestzeit aus den besten Session-Segmenten.

`topTimeLossSegments` speichert die groessten Segmentverluste mit Ursache.

`segmentMarkers` speichert Markerposition, Schweregrad und Label fuer die Chart-Visualisierung.

`processingVersion` kennzeichnet, mit welcher Verarbeitungslogik die Session zuletzt voll analysiert wurde.

`isPartial` markiert ungefinalisierte Autosave-Snapshots, die getrennt von finalen Sessions gespeichert werden.

## SessionQuality

`SessionQuality` enthaelt:

- `overallScore`
- `validLapRatio`
- `avgConfidence`
- `disturbedLapRatio`
- `lapTimeVariance`

Bedeutung:

- `overallScore` ist die verdichtete Lern-Eignung der Session auf Skala `0.0` bis `1.0`
- `validLapRatio` zaehlt Laps, die nicht Outlap, nicht Disturbed und ausreichend sicher sind
- `lapTimeVariance` ist normiert, damit stark streuende Sessions schlechter bewertet werden

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
- `confidenceScore`

`confidenceScore` beschreibt die Reife und Stabilitaet des Profils auf Skala `0.0` bis `1.0`.


## TrackLayout und Kurvenklassifikation

`TrackLayout` speichert neben manuellen Corner-Ankern jetzt auch automatisch erkannte Streckenstruktur:

- `centerlinePoints`: normierte, geordnete Centerline-Punkte (`0..1`)
- `detectedCorners`: Liste aus Segmenten mit
  - `index`
  - `startIndex`
  - `peakIndex`
  - `endIndex`
  - `type` (`TIGHT`, `MEDIUM`, `FAST`)
  - `curvature` (Peak-Kruemmung)

Detektionspipeline (deterministisch):

1. Centerline aus Layout nutzen oder aus Bitmap radial extrahieren
2. Polylinie glätten
3. Kruemmung über Richtungsänderung `p[i-k], p[i], p[i+k]` berechnen
4. lokale Maxima über Schwellwert als Kurvenkandidaten markieren
5. nahe Peaks mergen, Segmentgrenzen bestimmen, Corner-Typ klassifizieren
6. Ergebnis im Layout persistieren und für Overlay/Analyse wiederverwenden

## Datenfluss

1. Nutzer waehlt einen Track oder legt einen neuen Track an.
2. Die Auswahl erfolgt ueber ein Dropdown; neue Tracks werden ueber einen Dialog erzeugt und nach dem Speichern direkt selektiert.
3. `SessionViewModel.startRecording()` ruft nur bei gueltigem `currentTrackName` `Context.startRecordingService(trackName)` auf.
4. `RecordingForegroundService` startet, erstellt den Notification-Channel und ruft `startForeground(...)`.
5. Der Service uebernimmt die Kontrolle ueber `SensorRecorder.startRecording()`.
6. `SensorRecorder` geht in `RecorderPhase.CALIBRATING`.
7. `CalibrationManager` sammelt fuer ca. 2 Sekunden Accel-Werte.
8. Nach abgeschlossener Kalibrierung startet `SessionRepository.startSession(...)`.
9. Waehren Recording erzeugt `SensorRecorder` fortlaufend `SensorSample`.
10. `SessionRepository.appendSample(...)` sammelt die Samples und aktualisiert Live-State.
11. Waehren Recording speichert das Repository alle 5 Sekunden einen Session-Snapshot.
12. Beim Stop ruft der Service `SensorRecorder.stopRecording()` auf.
13. `SessionRepository.stopSession(...)` fuehrt die Verarbeitung aus.
14. `LapDetector` erzeugt Laps.
15. `PeakDetector` berechnet Peak-Indizes pro Lap.
16. `SectorDetector` berechnet Sektorgrenzen und Sektorzeiten pro Lap.
17. `SessionRepository.classifyLaps(...)` markiert `isDisturbed`.
18. `SessionQualityEvaluator` berechnet die Session-Qualitaet.
19. `DrivingCoachAnalyzer.analyzeSession(...)` erzeugt Session-Coaching-Feedback, Theoretical Best Lap und Segmentmarker.
20. `SessionStorageManager` speichert die finale Session als JSON.
21. `TrackProfileManager.updateProfile(...)` aktualisiert das Track-Profil nur mit ausreichend guten Sessions.
22. `SessionViewModel` stellt Session, Lap-Liste und Comparison-State fuer die UI bereit.

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

Wichtig:

- `SensorRecorder` ist nicht mehr an den Activity-Lifecycle gebunden
- Start und Stop erfolgen jetzt ueber `RecordingForegroundService`
- dadurch bleiben die Sensorlistener aktiv, wenn die App in den Hintergrund wechselt

Konsequenz:

- die App ist deutlich robuster bei Hintergrundbetrieb und ausgeschaltetem Display
- der Recorder selbst bleibt aber weiterhin eine einfache Sensor-Komponente ohne eigene Service-Logik

## RecordingForegroundService

Der neue `RecordingForegroundService` kapselt den background-sicheren Recording-Betrieb.

Verantwortung:

- Starten und Stoppen des eigentlichen Recordings
- sofortige Promotion zu einem Foreground Service
- laufende Status-Notification
- Wake-Lock waehrend aktiver Aufnahme
- sauberes Stoppen mit `stopForeground(...)` und `stopSelf()`

Notification:

- permanenter Notification-Channel
- zeigt Trackname, Status, Dauer und Sample-Anzahl
- enthaelt eine Stop-Aktion
- wird etwa jede Sekunde aktualisiert

Android-Anforderungen:

- Manifest-Permissions:
  - `FOREGROUND_SERVICE`
  - `FOREGROUND_SERVICE_HEALTH`
  - `HIGH_SAMPLING_RATE_SENSORS`
  - `POST_NOTIFICATIONS`
  - `WAKE_LOCK`
- Service-Deklaration mit `android:foregroundServiceType="health"`
- `stopWithTask="false"` fuer fortlaufendes Recording auch nach Verlassen der Activity

Restart-Verhalten:

- der Service verwendet `START_NOT_STICKY`
- ein bereits laufender Service bleibt bei UI-Wechseln stabil
- nach echtem Prozess-Tod wird das Recording nicht automatisch ueber einen Sticky-Restart rekonstruiert
- Start, Foreground-Promotion, Notification-Updates und Shutdown sind defensiv abgefangen und fuehren bei Fehlern zu sauberem Stop statt zu einem haengenden Service-Zustand
- OEM-spezifische Battery-Optimierungen koennen trotz Foreground Service weiterhin aggressiv sein

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

Die aktuelle Rundenerkennung verwendet bereits die globale `LapDetector2`-Pipeline.

Verarbeitungsschritte:

1. Resampling der Session in 100-ms-Buckets.
2. Bildung von `ResampledFrame`-Werten mit:
   - `totalAcceleration`
   - `yawRateAbs`
   - abgeleitetem `activity`-Signal
3. Prior-Schaetzung fuer die Rundendauer:
   - bevorzugt aus `TrackProfile.averageLapTimeMs`
   - sonst aus sessionspezifischer Wiederholungsstruktur
4. `BoundaryGenerator` erzeugt Boundary-Kandidaten aus:
   - Repeat-Evidenz
   - Boundary-Schaerfe
   - Pause-Edges
   - Anchor-Punkten entlang der erwarteten Rundenzeit
5. `GlobalSegmenter` baut Segment-Hypothesen zwischen Boundary-Kandidaten.
6. Jedes Segment wird ueber mehrere Merkmale bewertet:
   - `durationScore`
   - `templateMatchScore`
   - Event-Plausibilitaet
   - Boundary-Schaerfe
   - Aktivitaetsverhaeltnis
7. Die Session wird als globale Folge solcher Segmente optimiert.
8. Jedes gewaehlte Segment wird als:
   - `NORMAL`
   - `OUTLAP`
   - `INLAP`
   - `INTERRUPTED`
   klassifiziert.
9. Fuer jedes Segment wird ein kalibrierter `confidenceScore` berechnet.
10. Falls die globale Loesung instabil ist, faellt das System auf ein einzelnes Low-Confidence-Segment zurueck.

Zielfunktion:

- jede moegliche Segmentkante zwischen zwei Boundary-Kandidaten bekommt einen Segment-Score
- zusaetzlich werden Uebergaenge zwischen aufeinanderfolgenden Segmenten bewertet
- optimiert wird die gesamte Session als konsistenter Pfad durch den Kandidatenraum

Damit wird die Rundenerkennung nicht mehr ueber lokale Maxima entschieden, sondern ueber die beste Gesamtsegmentierung der Session.

## Outlap-, Inlap- und Unterbrechungsbehandlung

Warum:

- die erste Runde ist oft untypisch wegen Anfahren, Sortieren, Aufwaermen oder unvollstaendigem Einstieg in die Strecke

Aktuelles Verhalten:

- `OUTLAP` wird als eigene Phase bevorzugt am Session-Anfang oder direkt nach Unterbrechung gewaehlt
- `INLAP` wird als eigene Phase bevorzugt am Session-Ende oder direkt vor einer laengeren Pause gewaehlt
- `INTERRUPTED` markiert Segmente mit deutlicher Niedrigaktivitaet oder pauseartigem Verlauf
- alle diese Segmente bleiben sichtbar und persistent gespeichert
- fuer Default-Vergleiche bevorzugt die UI weiterhin saubere `NORMAL`-Laps
- `INTERRUPTED`-Segmente werden nicht als normale Runden behandelt und nicht fuer Track-Learning verwendet

## Disturbed-Lap-Behandlung

Zusatzlogik in `SessionRepository.classifyLaps(...)`:

- Pass 1:
  - Referenz-Lap-Time wird nur aus Laps berechnet, die:
  - `lapPhase == NORMAL` haben
  - `confidenceScore >= 0.7` haben
- dadurch haengt die Baseline nicht von einem vorherigen `isDisturbed`-Status ab
- Pass 2:
  - eine Lap wird als `isDisturbed = true` markiert, wenn mindestens eines gilt:
  - `lapPhase == INLAP`
  - `lapPhase == INTERRUPTED`
  - `lapTimeMs > avgLapTime * 1.15`
  - `confidenceScore < 0.55`
  - weniger als 2 Brems-Peaks
  - weniger als 2 Cornering-Peaks

Wichtig:

- `isOutlap` bleibt davon unberuehrt
- eine Runde kann gleichzeitig `isOutlap = true` und `isDisturbed = true` sein
- die UI bevorzugt fuer den Vergleich Laps, die weder Outlap noch Disturbed sind

## Session Quality

`SessionQualityEvaluator` bewertet jede verarbeitete Session aus ihren Laps.

Kennzahlen:

- `validLapRatio`
  - Laps mit:
    - `lapPhase == NORMAL`
    - nicht `isDisturbed`
    - `confidenceScore >= 0.7`
- `avgConfidence`
  - Mittelwert aller `confidenceScore`
- `highConfidenceLapRatio`
  - Anteil normaler, nicht gestoerter Laps mit `confidenceScore >= 0.85`
- `disturbedLapRatio`
  - Anteil gestoerter Laps
- `lapTimeVariance`
  - normierte Standardabweichung der Lap-Time

Gesamtscore:

- `overallScore =`
  - `0.35 * validLapRatio`
  - `+ 0.30 * avgConfidence`
  - `+ 0.15 * highConfidenceLapRatio`
  - `+ 0.10 * (1 - disturbedLapRatio)`
  - `+ 0.10 * (1 - lapTimeVariance)`

Nutzung:

- wird in `Session.quality` persistiert
- wird beim Laden alter Sessions bei Bedarf neu berechnet
- steuert, ob eine Session das `TrackProfile` ueberhaupt beeinflussen darf

## Lap-Confidence-Modell

Der aktuelle `confidenceScore` ist als kalibriertes, deterministisches Modell implementiert.

Bedeutung:

- `confidenceScore` bleibt im Bereich `0.0` bis `1.0`
- Bedeutung: "Wie wahrscheinlich ist es, dass dieses Segment eine korrekt erkannte Runde ist?"
- der Score ist zwischen Sessions besser vergleichbar als die fruehere lokale Heuristik

Normalisierte Teil-Scores:

- `durationScore`
  - Uebereinstimmung der Lap-Time mit erwarteter Rundendauer
- `similarityScore`
  - Aehnlichkeit zur vorherigen nicht-unterbrochenen Lap
- `templateMatchScore`
  - Aehnlichkeit zum Track-Template aus `TrackProfile`
- `eventScore`
  - Plausibilitaet der Brems- und Cornering-Ereignisse
- `boundarySharpnessScore`
  - Plausibilitaet der Start- und Endgrenzen im Signal

Normalisierung:

- alle Teil-Scores werden auf `0.0` bis `1.0` normiert
- Dauer verwendet eine gaussfoermige Normierung relativ zu erwarteter Lap-Time und Standardabweichung
- Aehnlichkeiten basieren auf normierten Kosinus-Scores
- Event-Plausibilitaet basiert auf Peak-Anzahl und -Konsistenz
- Boundary-Schaerfe basiert auf Boundary-Evidenz des globalen Segmentierers

Kombination:

- gewichtetes geometrisches Mittel statt roher Multiplikation einzelner Heuristiken
- fehlende Merkmale werden ueber die verbleibenden Gewichte aufgefangen
- zusaetzlich wirken:
  - Phasen-Anpassung fuer `OUTLAP`, `INLAP`, `INTERRUPTED`
  - Source-Reliability-Anpassung je nach Reife des `TrackProfile`

Gewichte:

- `durationScore`: `0.30`
- `similarityScore`: `0.25`
- `templateMatchScore`: `0.20`
- `eventScore`: `0.15`
- `boundarySharpnessScore`: `0.10`

Interpretation:

- `> 0.85`: sehr verlaesslich
- `0.70 bis 0.85`: gut nutzbar
- `0.55 bis 0.70`: grenzwertig
- `< 0.55`: vermutlich falsch segmentiert

## Peak-Detection

`PeakDetector` arbeitet ebenfalls mit pocket-tauglichen Signalen.

Zur Robustheitsverbesserung werden die Eingangssignale vor der Peak-Erkennung leicht geglaettet:

- Moving-Average-Fenster mit Breite `5`
- Glaettung fuer:
  - `totalAcceleration`
  - `yawRateAbs`
- die Peak-Logik selbst bleibt deterministisch und unveraendert
- Ziel ist geringere Noise-Empfindlichkeit und weniger falsch gestoerte Laps

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
- die Peak-Anzahl ist ausserdem Bestandteil des kalibrierten Lap-Confidence-Modells

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

Stabile Nutzung ueber Track-Profil:

- wenn `TrackProfile.typicalSectorBoundaries` mindestens 2 interne Grenzpunkte enthaelt, werden diese fuer alle Laps dieses Tracks verwendet
- in diesem Fall findet keine erneute lap-spezifische Sektor-Erkennung statt
- dadurch bleiben Sektorvergleich und Ideal Lap konsistent ueber mehrere Laps und Sessions

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
- Ideal-Lap-Zeit
- Best-Sektoren des Ideal Laps
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
2. Ein leichter Moving-Average glattet das Eingangssignal.
3. Jede Lap wird per Z-Score normalisiert:
   - `(accel - mean) / stdDev`
4. Die daraus entstehende relative Beschleunigung wird zu einer kuenstlichen Geschwindigkeitskurve integriert.
5. Die Geschwindigkeit wird dabei begrenzt:
   - Minimum `1.0 m/s`
   - Maximum `32.0 m/s`
6. Alle 10 Schritte wird eine Drift-Korrektur mit Faktor `0.98` angewendet.
7. Aus der positiven Geschwindigkeitskurve wird eine monotone Distanzkurve aufgebaut.
8. Die Zeitkurve wird anschliessend ueber dieselbe normalisierte Distanzachse fuer beide Laps interpoliert.
9. Die Kurven werden auf die reale Lap-Time skaliert.
10. `timeA - timeB` ergibt den Zeitverlust entlang der Runde.
11. Bei niedriger Confidence wird zusaetzlich ein einfacher Pattern-Alignment-Fallback beigemischt.

Interne API:

- `computeTimeLoss(lapA, lapB): List<Float>` bleibt fuer die UI der Hauptzugang
- `computeTimeLossResult(lapA, lapB): TimeLossResult` liefert zusaetzlich eine Confidence fuer spaetere Erweiterungen

Grenzen:

- das ist weiterhin eine leichte Approximation
- keine absolute Fahrzeuggeschwindigkeit
- keine echte Distanzmessung
- deutlich stabiler als die fruehere rohe Integration, aber keine Referenzmessung

## Sektorvergleich

Der Comparison Screen erzeugt zusaetzlich kompakte Sektor-Deltas:

- `S1: +0.12s`
- `S2: -0.08s`
- `S3: +0.30s`

Bedeutung:

- positives Delta: Lap A ist in diesem Sektor langsamer
- negatives Delta: Lap A ist in diesem Sektor schneller

## Ideal Lap

`IdealLapCalculator` erzeugt eine Referenzrunde aus den besten Sektoren gueltiger Laps.

Eingang:

- Laps des aktuellen Tracks aus aktueller und gespeicherter Session-Historie

Gueltige Laps:

- `lapPhase == NORMAL`
- nicht `isDisturbed`
- `confidenceScore >= 0.75`
- vorhandene `sectorTimesMs`

Berechnung:

1. haeufigste Sektoranzahl ueber die gueltigen Laps bestimmen
2. nur Laps mit dieser Sektoranzahl vergleichen
3. pro Sektor den kleinsten gemessenen Sektorwert nehmen
4. alle Bestzeiten summieren

Ausgabe:

- `IdealLap.sectorBestTimes`
- `IdealLap.totalTimeMs`

Nutzung in der UI:

- Comparison Screen zeigt `Ideal Lap: XX.XX`
- darunter die besten Einzel-Sektoren
- die Berechnung laeuft trackweit ueber gespeicherte Sessions, nicht nur ueber die aktuell geladene Session

## Driving Insights

`DrivingCoachAnalyzer` nutzt eine leichte Telemetrie-Analyse:

- Referenzlap-Auswahl aus stabilen Normal-Laps
- Normierung auf 251 Punkte
- Segmentierung ueber Sektorgrenzen oder Fallback-Splits
- Metriken pro Segment fuer Entry-, Mid-, Exit-Speed, Brems- und Yaw-Verhalten
- Zeitverlust-Ursachenklassifikation und Priorisierung
- Theoretical-Best-Lap-Berechnung ueber Best-Segmente
- Segmentmarker fuer die Chart-Darstellung

Wichtig:

- die Analyse bleibt heuristisch und deterministisch
- sie ist bewusst leichtgewichtig und nutzt kein ML
- sie priorisiert die groessten Zeitverluste statt allgemeiner Kurztexte

## Persistenz

## SessionStorageManager

Sessions werden als JSON-Dateien in app-spezifischem Speicher abgelegt.

Speicherort:

- `context.filesDir/sessions`
- Quarantaene fuer ungueltige Session-Dateien:
  - `context.filesDir/corrupt_sessions`

Dateiname:

- `session_<trackName>_<startTimeEpochMs>.json`
- fuer Partial-Snapshots:
  - `session_<trackName>_<startTimeEpochMs>_partial.json`

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
- Session-Quality-Metriken
- `processingVersion`
- `isPartial`

Aktueller Stand:

- `Lap` speichert explizite Segmenttypen ueber `lapPhase`
- bestehende Kompatibilitaetsfelder wie `isOutlap` bleiben erhalten
- fehlende `processingVersion`-Felder in aelteren JSON-Dateien werden beim Laden kompatibel als Version `1` behandelt
- fehlende `isPartial`-Felder in aelteren JSON-Dateien werden kompatibel als `false` behandelt
- Schreibvorgaenge erfolgen ueber eine temporaere Datei und werden danach atomar ersetzt, um halb geschriebene JSON-Dateien zu vermeiden
- leere, unlesbare, implausible oder uebergrosse Session-Dateien werden aus dem aktiven Session-Ordner in `corrupt_sessions` verschoben

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
- zentrale Reprocessing-Pipeline fuer gespeicherte Sessions
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

Zentrale Reprocessing-Logik:

- `CURRENT_PROCESSING_VERSION = 2`
- `processSessionInternal(session)` fuehrt die komplette deterministische Verarbeitung erneut aus:
  - `LapDetector`
  - `PeakDetector`
  - `SectorDetector`
  - Disturbed-Klassifikation
  - `SessionQualityEvaluator`
- `reprocessSession(session)` speichert das Ergebnis wieder als JSON und aktualisiert bei Bedarf auch das zugehoerige `TrackProfile`
- `reprocessSessionAsync(session)` startet dieselbe Verarbeitung auf `repositoryScope`, damit der UI-Pfad nicht blockiert

## Autosave

Waehrend Recording:

- alle 5 Sekunden wird ein Snapshot geschrieben
- Partial-Snapshots werden in eine getrennte Datei mit Suffix `_partial.json` geschrieben
- finale Sessions und Partial-Snapshots koennen daher nicht mehr gegenseitig ueberschrieben werden
- die Datei ist ueber `trackName + startTimeEpochMs` stabil identifizierbar

Finalisierung:

- bei `stopSession()` wird die Session voll verarbeitet und als nicht-partielle Finaldatei gespeichert
- eine vorhandene Partial-Datei desselben Recordings wird danach entfernt

Recovery:

- wenn eine gespeicherte Session Samples, aber noch keine Laps enthaelt, wird sie beim Laden vollstaendig reprocessiert
- wenn eine gespeicherte Session bereits Laps, aber noch keine Sektor-Metadaten oder `quality` enthaelt, wird sie ebenfalls vollstaendig reprocessiert
- wenn `processingVersion < CURRENT_PROCESSING_VERSION` ist, wird die Session beim Laden automatisch mit der aktuellen Verarbeitungslogik neu analysiert
- Reprocessing nutzt weiterhin die gespeicherten Rohsamples als Quelle und erzeugt neue Laps, Peaks, Sektoren, Confidence-Werte und Session-Quality
- automatisches Reprocessing beim Laden laeuft asynchron auf `repositoryScope`
- der Foreground Service reduziert Session-Verlust bei Hintergrundbetrieb deutlich
- defekte JSON-Dateien werden vor erneutem Laden quarantainisiert, damit sie nicht bei jedem App-Start denselben Fehler erneut ausloesen

Grenze:

- Autosave reduziert Datenverlust
- es ersetzt keine vollstaendige Recovery nach Prozess-Tod und keine OEM-unabhaengige Hintergrundgarantie

## Track Management

`TrackManager` speichert Tracks in `SharedPreferences`.

Aktuelles Verhalten:

- Tracks werden als String-Set gehalten
- Tracknamen werden vor Speicherung normalisiert:
  - `trim()`
  - mehrere Whitespaces werden zu einem Leerzeichen zusammengefasst
- neue Tracks werden ueber `addTrackSafe(...)` case-insensitive gegen Duplikate geprueft
- `getTracksList()` liefert eine sortierte Liste fuer die Dropdown-UI
- der zuletzt ausgewaehlte Track wird gespeichert und beim App-Start wiederhergestellt
- es gibt keinen impliziten Default wie `General Track`

Aktuell nicht vorhanden:

- Track-Loeschen
- Umbenennen
- Reihenfolge/Sortierung nach echter letzter Nutzung ausserhalb der aktuellen Letztwahl-Priorisierung
- Streckenprofile oder Metadaten

## Track-spezifisches Lernen

`TrackProfileManager` speichert Profile als JSON unter:

- `context.filesDir/track_profiles/track_<trackName>.json`

Profilaufbau:

1. Sessions des Tracks laden
2. Nur Sessions mit ausreichender `SessionQuality` verwenden:
   - bei jungen Profilen:
     - `overallScore >= 0.65`
     - `validLapRatio >= 0.55`
   - bei reifen Profilen:
     - `overallScore >= 0.75`
     - `validLapRatio >= 0.6`
   - mindestens 3 gueltige Laps
3. Bei reifen Profilen mit hoher `confidenceScore` werden die Schwellwerte weiter verschaerft
4. Nur normale, nicht gestoerte Laps werden beruecksichtigt
5. Lap-Ausreisser innerhalb einer Session verwerfen, wenn:
   - `lapTimeMs` ausserhalb `mean +- 2 * stddev`
   - oder `confidenceScore < 0.75`
   - oder zu wenige Peaks vorhanden sind
6. `totalAcceleration` und `yawRateAbs` der verbleibenden Laps auf 101 Punkte normalisieren
7. Lap-Beitraege confidence-gewichtet aggregieren:
   - Gewicht pro Lap = `confidenceScore^2`
8. Session-Mittelkurven bilden und ueber Sessions qualitaetsgewichtet aggregieren
9. `INLAP`- und `INTERRUPTED`-Segmente generell vom Profil-Update ausschliessen
10. typische Brems- und Cornering-Zonen als Minima/Maxima extrahieren
11. typische Sektorgrenzen aus historischen Laps ableiten
12. Mittelwert und Standardabweichung der Lap-Time speichern

Verbesserung der Sektorgrenzen:

- vorhandene `typicalSectorBoundaries` werden nicht hart ueberschrieben
- neue Grenzen werden mit qualitaetsabhaengigem Einfluss geglaettet:
  - `new = (1 - 0.2 * quality) * old + (0.2 * quality) * detected`
- wenn neue Grenzen mehr als `15` Prozentpunkte vom Profil abweichen:
  - wird ihr Einfluss halbiert
- wenn neue Grenzen mehr als `30` Prozentpunkte abweichen:
  - wird das Boundary-Update verworfen
- inkonsistente oder unbrauchbare Profilgrenzen werden im Runtime-Pfad nicht erzwungen

Profil-Reife:

- `TrackProfile.confidenceScore` steigt pro gutem Update mit:
  - `min(1.0, old + 0.1 * sessionQuality)`
- hohe Profil-Reife macht kuenftige Updates restriktiver
- junge Profile bleiben lernfaehiger, aber schlechte Sessions duerfen weiterhin nicht unter die Basisschwellwerte fallen

Nutzung in neuer Session:

- falls Profil vorhanden, wird die Lap-Detection frueh und enger um die erwartete Rundenzeit gesucht
- falls `typicalSectorBoundaries.size >= 2`, werden diese festen Sektorgrenzen fuer alle Laps wiederverwendet
- falls die Profilgrenzen inkonsistent wirken, faellt das System auf lap-spezifische Sektor-Erkennung zurueck
- Profile mit niedriger `confidenceScore` sind lernfaehiger, reife Profile stabiler

UI:

- Main Screen zeigt an, ob fuer den gewaehlten Track bereits ein Profil benutzt wird

## UI und Navigation

## Main Screen

Aktuelle Funktionen:

- Track-Dropdown mit bestehender Trackliste
- letzter Eintrag `+ Add new track`
- Dialog fuer neuen Track mit Validierung und Duplicate-Schutz
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
- Notification-Permission-Request auf Android 13+

Hinweis:

- die Live-Werte zeigen nur die kompatiblen Richtungswerte, nicht `totalAcceleration` oder `yawRateAbs`
- die Track-Auswahl wird waehrend Kalibrierung und Recording deaktiviert, damit der Session-Track stabil bleibt
- Start Recording bleibt deaktiviert, solange kein gueltiger Track ausgewaehlt ist
- die freie Texteingabe fuer Tracknamen wurde durch eine dropdown-basierte Auswahl ersetzt

## Lap Screen

Aktuelle Darstellung pro Lap:

- Lap-Nummer
- Outlap-Markierung
- Inlap-Markierung
- Interrupted-Markierung
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
- Debug-Aktion zum manuellen Reprocess einer Session

## Laden gespeicherter Sessions

Beim Laden einer Session:

- `SessionRepository.loadSession(...)` setzt `currentSession`
- falls die Session alt oder unvollstaendig verarbeitet ist, wird `reprocessSessionAsync(...)` gestartet
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
| Datenmodell | `SessionQuality` | Erfuellt | wird pro verarbeiteter Session gespeichert |
| Datenmodell | `TrackProfile` | Erfuellt | gespeichert als JSON pro Track |
| Lap Detection | Globale Segmentierung ueber ganze Session | Erfuellt | `LapDetector2`, `BoundaryGenerator`, `GlobalSegmenter` |
| Lap Detection | Event-Erkennung | Erfuellt | braking/cornering checks |
| Lap Detection | Confidence | Erfuellt | kalibriertes Modell aus Duration-, Similarity-, Template-, Event- und Boundary-Scores |
| Lap Detection | Outlap-Erkennung | Erfuellt | explizite Phase `OUTLAP` |
| Lap Detection | Inlap-Erkennung | Erfuellt | explizite Phase `INLAP` |
| Lap Detection | Unterbrechungs-Segmentierung | Erfuellt | explizite Phase `INTERRUPTED` |
| Lap Detection | kalibriertes Confidence-Modell | Erfuellt | gewichtetes geometrisches Mittel plus Phasen-/Profil-Anpassung |
| Lap Detection | Disturbed-Lap-Klassifikation | Erfuellt | spaete, unplausible oder peak-arme Laps werden markiert |
| Lap Detection | Sektor-Erkennung | Erfuellt | `SectorDetector` findet interne Grenzpunkte aus Brems- und Cornering-Zonen |
| Lap Detection | Fallback | Erfuellt | einzelne Lap bei instabiler Segmentierung |
| Persistenz | JSON-Speicherung | Erfuellt | `SessionStorageManager` |
| Persistenz | Autosave | Erfuellt | getrennte Partial-Snapshots alle 5 Sekunden |
| Persistenz | Session-Laden | Erfuellt | alle Sessions, Track-spezifisch, letzte Session |
| Persistenz | Reprocessing alter Sessions | Erfuellt | `processingVersion` + `SessionRepository.reprocessSession(...)` / `reprocessSessionAsync(...)` |
| Betrieb | Foreground Service | Erfuellt | `RecordingForegroundService` mit Notification und Wake-Lock |
| Track Learning | Session-Quality-Guard | Erfuellt | schlechte Sessions duerfen das Profil nicht updaten |
| Track Learning | gewichtete Profil-Updates | Erfuellt | hohe Session-Qualitaet beeinflusst das Profil staerker |
| Track Learning | Sector-Deviation-Schutz | Erfuellt | starke Boundary-Abweichungen werden gedrosselt oder verworfen |
| Track Learning | Profil pro Track speichern | Erfuellt | `TrackProfileManager` |
| Track Learning | Profil in Lap-Detection nutzen | Erfuellt | engerer Shift-Suchraum und Profil-Bias |
| Track Learning | Sektorgrenzen wiederverwenden | Erfuellt | feste Nutzung von `typicalSectorBoundaries` bei ausreichender Profilstaerke |
| Track Management | Track auswaehlen/anlegen | Erfuellt | Dropdown im Main Screen + Dialog + `TrackManager` |
| Session Browsing | Liste, Filter, Laden | Erfuellt | `SessionListFragment` |
| Visualisierung | Lap-Overlay | Erfuellt | Comparison Screen |
| Visualisierung | Time-Loss-Graph | Erfuellt | `TimeLossCalculator` + Comparison Screen |
| Visualisierung | Sektorvergleich | Erfuellt | Sektor-Deltas im Comparison Screen |
| Visualisierung | Sektorzeiten pro Lap | Erfuellt | Lap-Liste zeigt Abschnittszeiten |
| Visualisierung | Ideal Lap | Erfuellt | `IdealLapCalculator` + Comparison Screen |
| Visualisierung | Peak-Marker | Erfuellt | Bremsen und Cornering |
| Insights | Text-Feedback | Erfuellt | `DrivingCoachAnalyzer` |
| Robustheit | Orientation-unabhaengige Yaw-Erkennung | Erfuellt | Gyro-Magnitude |
| Robustheit | Voll orientierungsfreie Richtungsdiagramme | Nicht erfuellt | Charts nutzen weiterhin angenaeherten Richtungsbezug |
| Export | CSV/Share | Nicht erfuellt | derzeit nicht vorhanden |
| Qualitaet | Automatisierte Tests | Teilweise erfuellt | `ReliabilityWorkflowTest` fuer 3 simulierte Sessions (8/12/15 min) vorhanden |

## Bekannte Grenzen

- keine garantierte Hintergrundaufnahme bei aggressivem Android-App-Management
- keine vollstaendige Pose-/Orientierungsrekonstruktion
- `longitudinalAccel` und `lateralAccel` bleiben bei wechselnder Telefonlage nur angenaehert interpretierbar
- Insights sind heuristisch
- Lap-Detection ist heuristisch und nicht gegen Referenz-Transponder validiert
- Time-Loss ist eine Approximation aus Beschleunigung, nicht echte Fahrzeugzeitmessung
- Sektorgrenzen sind heuristisch aus Musterpunkten abgeleitet, nicht physisch vermessen
- auch mit Schutzlogik bleibt Track-Learning heuristisch und datenabhaengig
- ein bereits laufendes Recording kann nach Prozess-Tod nicht nahtlos live fortgesetzt oder automatisch per Sticky-Restart wiederaufgenommen werden
- keine Exportfunktion
- noch keine vollstaendige Testabdeckung; ein Reliability-Workflow-Unit-Test ist vorhanden

## Was noch offen ist

## Fachlich offen

- streckenspezifische Nutzung historischer Sessions fuer bessere Lap-Detection
- genauere Zeitverlustanalyse ueber echte Distanz- oder Geschwindigkeitsreferenzen
- moegliche alternative Vergleichsmodi auf Basis von `totalAcceleration` und `yawRateAbs`

## Technisch offen

- Exportfunktion
- Loeschen und Umbenennen von Tracks
- Loeschen oder Archivieren alter Sessions
- detailliertere Service-Recovery nach Prozess-Tod
- Tests fuer:
  - `LapDetector`
  - `SessionRepository`
  - `SessionStorageManager`
  - `TrackManager`
  - `LapNormalizer`
  - Zuverlaessigkeits-Workflow erweitert (`ReliabilityWorkflowTest`)
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
3. Schwellenwerte der globalen Segmentierung und Confidence-Berechnung anhand echter Fahrdaten feinjustieren.
4. Exportfunktion einfuehren.
5. Service-Recovery und OEM-Verhalten im Feldtest pruefen.
6. Persistenz- und Lap-Detection-Tests weiter ausbauen (aufbauend auf `ReliabilityWorkflowTest`).

## Relevante Dateien

- `app/src/main/java/com/kartingtracker/data/SensorSample.kt`
- `app/src/main/java/com/kartingtracker/data/Lap.kt`
- `app/src/main/java/com/kartingtracker/data/LapPhase.kt`
- `app/src/main/java/com/kartingtracker/data/Session.kt`
- `app/src/main/java/com/kartingtracker/data/SessionQuality.kt`
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
- `app/src/main/java/com/kartingtracker/service/RecordingForegroundService.kt`
- `app/src/main/java/com/kartingtracker/service/RecordingNotificationHelper.kt`
- `app/src/main/java/com/kartingtracker/domain/LapDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/LapDetector2.kt`
- `app/src/main/java/com/kartingtracker/domain/BoundaryGenerator.kt`
- `app/src/main/java/com/kartingtracker/domain/GlobalSegmenter.kt`
- `app/src/main/java/com/kartingtracker/domain/SectorDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/PeakDetector.kt`
- `app/src/main/java/com/kartingtracker/domain/LapNormalizer.kt`
- `app/src/main/java/com/kartingtracker/domain/TimeLossCalculator.kt`
- `app/src/main/java/com/kartingtracker/domain/IdealLapCalculator.kt`
- `app/src/main/java/com/kartingtracker/domain/SessionQualityEvaluator.kt`
- `app/src/main/java/com/kartingtracker/domain/DrivingCoachAnalyzer.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionViewModel.kt`
- `app/src/main/java/com/kartingtracker/ui/SessionUiModels.kt`
- `app/src/main/java/com/kartingtracker/ui/main/MainFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/laps/LapsFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/comparison/ComparisonFragment.kt`
- `app/src/main/java/com/kartingtracker/ui/sessions/SessionListFragment.kt`

## Hinweis zur Build-Validierung

Die Dokumentation wurde gegen den vorhandenen Quellcode abgeglichen. Build/Test-Ausfuehrung haengt von Netzwerkkonnektivitaet fuer den Gradle-Wrapper (`8.7`) und Abhaengigkeiten ab.
