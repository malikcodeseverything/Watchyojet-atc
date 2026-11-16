# WatchyoJet ATC system documentation

## Traffic sources and state

`Main` runs the engine on a background thread. Each cycle advances the
simulation by two seconds. Live mode queries OpenSky about every twelve seconds
for airborne traffic in a rectangular region around Philadelphia. Requests
run on the same background loop, so request latency can delay a cycle.

`OpenSkyFetcher` reads true track and ground speed, and prefers barometric
altitude over geometric altitude when both are available. `AircraftManager`
keys live aircraft by their ICAO24 addresses. Every successful snapshot
replaces the tracked set and updates reported coordinates, altitude, track,
and speed. In live mode the engine never edits these tracks, so every alert
reflects where OpenSky reports the aircraft. [OpenSky state-vector reference](https://openskynetwork.github.io/opensky-api/rest.html)

If a poll fails, the app temporarily uses its latest successful snapshot and
labels the source stale. If no usable snapshot is available or the snapshot is
older than 60 seconds after a poll failure, it loads scripted traffic. On HTTP
429 the app waits for the retry interval supplied by OpenSky, or one minute
when no usable interval is supplied. Cached traffic can expire during that
wait. Explicit demo mode loads the scenario immediately and resets it every
60 seconds.

## Prediction and maneuver search

1. `MovementEngine` projects each aircraft's position forward using its
   current ground speed and track.
2. `ConflictDetector` uses relative horizontal velocity to calculate time
   and distance at closest point of approach. Parallel and diverging pairs
   are checked at their current distance. Within the ten-minute lookahead,
   distances below 5 NM and altitude differences below 1,000 ft trigger an
   alert. Prediction assumes constant altitude.
3. `ATCEngine` tracks each alert by its aircraft pair, so an encounter keeps
   its first-seen time across refreshes. It groups conflicts that share
   aircraft. `ResolutionEngine` searches for altitude changes, then track
   changes, then speed reductions. Candidate checks use simplified aircraft
   performance, separation buffers, and changes already accepted in the cycle.
4. Each group is tested on its own trial copy of the tracks. A group is
   rejected if its maneuvers create a new predicted conflict. Rejecting one
   group does not affect the others.
5. The cycle mode decides what happens next:
   - `ADVISE` (live OpenSky traffic): nothing is applied. Each alert carries
     its proposed instruction and whether it clears in simulation. An alert
     is reported cleared only when the observed tracks stop triggering it.
   - `SIMULATE` (scripted demo): accepted maneuvers are applied to the demo
     tracks, and pairs they separate are reported cleared with the
     instruction that cleared them.
   - `DETECT_ONLY` (first eight seconds of the demo): alerts are shown and
     no maneuvers are evaluated.
6. Every cycle produces a `CycleReport` with active alerts, cleared alerts,
   and counters (alerts, new alerts, groups, proposals, accepted and rejected
   groups, pairs cleared in trial, pairs without a safe maneuver). The
   counters are logged and shown above the conflict list. The engine hands
   the report to a `CycleListener`, so it has no dependency on the display.

## Desktop display

`WYJApp` loads the JavaFX layout and starts the engine after its embedded
WebView loads. `WYJAppController` serializes aircraft snapshots and forwards
them on the JavaFX application thread. The map replaces the displayed feed
tracks with each snapshot and clears source-specific tracks and alerts when
switching between OpenSky and the demo.

The map distinguishes live OpenSky, cached, and demo tracks. Live aircraft
types are shown as unknown. The engine uses estimates based on speed and
altitude when evaluating maneuvers.

## ATC context and limitations

WatchyoJet ATC models a subset of airborne conflict detection around an
airport. Approach and departure controllers provide terminal-area traffic
services. Tower and ground positions coordinate runway occupancy, taxi
movements, crossings, takeoff, and landing. The simulator's airport drawings
provide geographic context for those facilities. [FAA facility classifications](https://aspmhelp.faa.gov/index/OPSNET__Facility_Classification.html) · [FAA airport operations](https://www.faa.gov/air_traffic/publications/aim_html/chap4_section_3.html)

The simulation's alert thresholds are fixed. FAA radar minima depend on
operating conditions, including surveillance mode and wake turbulence.
WatchyoJet uses public state vectors and simplified trajectories. Maneuvers
are instant changes in simulated state, and the model does not account for
vertical rate, turn rate, or controller and pilot coordination. [FAA radar separation](https://www.faa.gov/air_traffic/publications/atpubs/atc_html/chap5_section_5.html)
