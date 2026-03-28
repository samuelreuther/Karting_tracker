# Projektdokumentation Karting Tracker

## Zweck

Die App ist ein Android-MVP zur Analyse von Indoor-Kartfahrten mit Smartphone-Sensoren ohne GPS.
Erfasst werden Beschleunigungs- und Gyroskopdaten. Daraus werden Sessions und Runden abgeleitet,
die anschliessend visuell verglichen werden koennen.

## Zielbild laut Anforderung

- Start/Stop-Aufnahme fuer Sensoren
- Erfassung von Accelerometer und Gyroscope mit `SENSOR_DELAY_FASTEST`
- Kalibrierung vor der Aufnahme zur Schaetzung der Gravitation
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
| Recording | Kalibrierung vor Aufnahme | Erfuellt | `CalibrationManager` sammelt ca. 2 Sekunden stationaere Accelerometerdaten |
| Recording | Zeitgestempelte Daten speichern | Erfuellt | `SensorSample` und `SessionRepository.currentSamples` |
| Verarbeitung | Low-Pass-Filter | Erfuellt | `LowPassFilter` fuer Accelerometer und Gyroscope |
| Verarbeitung | Longitudinal/Lateral trennen | Erfuellt | `CalibrationManager` entfernt Gravitation und projiziert Beschleunigung auf die Fahr-Ebene |
| Lap Detection | Zeitreihe fortlaufend speichern | Erfuellt | Samples werden waehrend Recording fortlaufend in `SessionRepository` gesammelt |
| Lap Detection | Sliding Window 5-10 Sekunden | Erfuellt | In `LapDetector`: 100-ms-Resampling, Fensterlaenge 60 Punkte = ca. 6 Sekunden |
| Lap Detection | Aehnlichkeit via Dot Product/Korrelation | Erfuellt | Kosinus-Aehnlichkeit ueber dot product und Normen in `windowSimilarity()` |
| Lap Detection | Eventbasierte Ergaenzung | Erfuellt | Kandidaten muessen zusaetzlich Brems- und Cornering-Ereignisse enthalten |
| Lap Detection | Wiederholende Muster als Runden erkennen | Erfuellt | `LapDetector.detect()` sucht bestes Shift und Grenzen |
| Lap Detection | Runden als Segmente speichern | Erfuellt | `Lap`-Objekte werden in `buildLaps()` erzeugt |
| Datenmodell | `SensorSample` | Erfuellt | Vorhanden |
| Datenmodell | `Lap` | Erfuellt | Vorhanden |
| Datenmodell | `Session` | Erfuellt | Vorhanden |
| Visualisierung | Laps als Line Graphs | Erfuellt | `ComparisonFragment` mit `MPAndroidChart` |
| Visualisierung | X-Achse 0-100 Prozent | Erfuellt | `LapNormalizer` erzeugt normierte Werte und `ChartUtils` setzt X-Achse 0-100 |
| Visualisierung | Y-Achse longitudinal/lateral | Erfuellt | Zwei Charts, jeweils fuer longitudinal und lateral |
| Lap Comparison | Zwei Runden auswaehlen | Erfuellt | Zwei Spinner in `ComparisonFragment` |
| Lap Comparison | Auf gleiche Laenge normieren | Erfuellt | `LapNormalizer.normalize()` auf 251 Punkte |
| Lap Comparison | Overlays Lap A vs Lap B | Erfuellt | Beide DataSets gleichzeitig pro Chart |
| Lap Comparison | Delta Graph | Erfuellt | Dritter Chart mit longitudinalem und lateralem Delta |
| Lap Comparison | Text-Insights | Erfuellt | Heuristische Vergleichssaetze aus Bremsen, Cornering und Beschleunigung |
| UI | Main Screen | Erfuellt | `fragment_main.xml` |
| UI | Lap Screen | Erfuellt | `fragment_laps.xml` + RecyclerView |
| UI | Comparison Screen | Erfuellt | `fragment_comparison.xml` |
| Technik | Kotlin | Erfuellt | Komplettes Projekt in Kotlin |
| Technik | MVVM / clean structure | Erfuellt | Activity/Fragments + gemeinsames ViewModel + Repository + Domain |
| Technik | SensorManager korrekt nutzen | Erfuellt | Registrierung/Abmeldung in `SensorRecorder` |
| Technik | Lifecycle korrekt behandeln | Weitgehend erfuellt | `SensorRecorder` ist `DefaultLifecycleObserver` |
| Technik | Nur In-Memory | Erfuellt | Keine DB, alles im Repository gehalten |
| Bonus | Peaks hervorheben | Erfuellt | Brems- und Cornering-Peaks werden als Marker im Chart gezeichnet |
| Bonus | Farben fuer acceleration/braking/cornering | Teilweise erfuellt | Farbige Linien vorhanden, aber keine semantische Einfaerbung innerhalb derselben Runde |

## Architektur

## Schichten

- `data`
  - Datenmodelle `SensorSample`, `Lap`, `Session`
  - `SessionRepository` als In-Memory-Speicher und zentrale Session-Verwaltung
- `sensor`
  - `SensorRecorder` kapselt Android-Sensorzugriff
  - `CalibrationManager` bestimmt Gravitation und Fahr-Ebene
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
- Kalibrierung:
  - etwa 2 Sekunden stationaere Accelerometerdaten vor Session-Start
- Threading:
  - eigener `HandlerThread("karting-sensor-thread")`
- Start/Stop:
  - Start beginnt zunaechst mit Kalibrierung
  - Session startet erst nach erfolgreicher Kalibrierung
  - Stop beendet Listener und startet Verarbeitung
- Live-Status:
  - Recording-Status, Sample-Anzahl, Live-Beschleunigungen, erkannte Runden

### Technische Umsetzung

`SensorRecorder` registriert beide Sensoren und verarbeitet Events in `onSensorChanged()`.

- Gyro-Events aktualisieren den zuletzt bekannten Gyro-Zustand.
- Accelerometer-Events erzeugen einen vollstaendigen `SensorSample`.
- `timestampNs` wird aus `event.timestamp` uebernommen.

### Wichtige Annahme

Die App nimmt weiterhin an, dass das Smartphone grob in Fahrtrichtung montiert ist.
Die feste Achsen-Zuordnung wurde jedoch durch eine Kalibrierung ersetzt.

## 2. Datenverarbeitung

### Low-Pass-Filter

Der Filter ist in `LowPassFilter` implementiert.

- Alpha: `0.18f`
- Zustand: 3-dimensional fuer X, Y, Z
- Einsatz:
  - einmal fuer Accelerometer
  - einmal fuer Gyroscope

### Ableitung der Fahrdynamik

Es gibt in diesem MVP keine vollstaendige Orientierungsschaetzung mit Fusion mehrerer Sensoren.
Stattdessen wird eine einfache, robuste Kalibrierung verwendet:

1. Waehrend der ersten ca. 2 Sekunden wird bei stehendem Kart der mittlere Gravitationsvektor gemessen.
2. Der Vektor wird normalisiert.
3. Der Gravitationsanteil wird aus allen folgenden Accelerometerwerten entfernt.
4. Die verbleibende Beschleunigung wird auf die Fahr-Ebene projiziert.
5. Die Vorwaertsachse wird aus der Geraeteorientierung auf diese Ebene projiziert.
6. Daraus werden longitudinale und laterale Komponenten berechnet.

### Einschraenkung

Die neue Kalibrierung macht das Signal deutlich robuster gegen Pitch und Roll.
Trotzdem bleibt die Montageposition relevant, insbesondere wenn das Telefon stark verdreht oder instabil befestigt ist.

## 3. Rundenerkennung

### Ziel

Wiederkehrende Muster in Beschleunigungs- und Gyrodaten sollen genutzt werden, um Rundenenden zu finden.

### Implementierte Methode

`LapDetector` arbeitet jetzt hybrid in mehreren Schritten:

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
9. Ein Kandidat muss zusaetzlich mindestens ein Bremsereignis und ein Cornering-Ereignis enthalten.
10. Doppelte Kandidaten werden gefiltert, die staerksten bleiben erhalten.
11. Daraus werden `Lap`-Segmente geschnitten.

### Aehnlichkeitsmass

Verwendet wird eine Kosinus-Aehnlichkeit auf Basis von dot product:

- Signal A:
  - longitudinal
  - lateral
  - `yawRate * 0.5`
- Signal B:
  - dieselben Groessen

Damit werden Formaehnlichkeiten zwischen zwei Fenstern verglichen.

### Event-Erkennung

Zusatzbedingungen fuer einen gueltigen Kandidaten:

- Brems-Peak:
  - longitudinale Beschleunigung kleiner als ca. `-2.5 m/s^2`
- Cornering:
  - Betrag der lateralen Beschleunigung groesser als ca. `2.0 m/s^2`

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

- Drei getrennte Charts:
  - longitudinal
  - lateral
  - delta
- X-Achse:
  - 0 bis 100 Prozent
- Pro Chart:
  - Lap A
  - Lap B

### Normierung

`LapNormalizer.normalize()` interpoliert jede Runde linear auf 251 Stuetzpunkte.

- 0 = Rundenstart
- 100 = Rundenende

Das erlaubt den direkten Vergleich auch bei unterschiedlichen Rundenzeiten.

## 6. Rundenvergleich

### Implementiert

- Auswahl von zwei Runden per Spinner
- Normierung beider Runden
- Overlay in zwei Charts
- Delta-Chart fuer Longitudinal- und Lateral-Differenz
- Marker fuer Brems- und Cornering-Peaks
- Vergleichstext:
  - welche Runde schneller ist
  - Zeitdifferenz
  - Anzahl Brems-Peaks
  - Anzahl Cornering-Peaks
- einfache Driving-Style-Insights

### Noch nicht implementiert

- Abschnittsweise Zeitdifferenz pro Sektor
- echte Zeitverlustschaetzung statt reiner Beschleunigungsdifferenz
- feinere Markerlogik fuer mehrere Kurvenkomplexe

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
- Delta-Chart
- Peak-Marker
- Insight-Textblock
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
