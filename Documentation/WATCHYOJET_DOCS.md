# WatchyoJet ATC system documentation

## Traffic sources and state

`Main` runs the engine on a background thread. Each cycle advances the
simulation by two seconds. Live mode queries OpenSky about every twelve seconds
for airborne traffic in a rectangular region around Philadelphia. Requests
run on the same background loop, so request latency can delay a cycle.

`OpenSkyFetcher` reads true track, ground speed, vertical rate, transponder
code and ADS-B emitter category, and prefers barometric altitude over
geometric altitude when both are available. The emitter category (light,
small, large, heavy, rotorcraft) sets the aircraft's performance class when
it is reported; otherwise the class is estimated from speed and altitude. `AircraftManager`
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

1. `MovementEngine` projects each aircraft forward using its current
   ground speed, track and reported vertical rate.
2. `ConflictDetector` finds the time window in which a pair is inside the
   lateral minimum and the window in which it is inside 1,000 ft
   vertically. A conflict is flagged only when the two windows overlap
   within the lookahead, so a climbing or descending aircraft is flagged
   only if it reaches the other aircraft's level while they are laterally
   close. `SeparationStandards` sets the thresholds: 3 NM when both
   aircraft are below FL180 and within 40 NM of an approach-control airport
   covered by the query box (PHL, EWR, ACY, ABE or BWI), 5 NM otherwise, with
   a 2 minute lookahead in that terminal area and 5 minutes elsewhere. The
   lookaheads are this project's choices: straight-line predictions age
   quickly in terminal airspace, where aircraft turn and change level often.
   Pairs involving a VFR aircraft (transponder code 1200) or a helicopter
   (ADS-B emitter category 8) are counted as traffic advisories and are not
   resolved. OpenSky does not report flight rules, so a US-registered
   aircraft using its registration as callsign, slower than 150 kt and below
   10,000 ft, is estimated to be VFR as well: many such pilots use flight
   following and squawk a discrete code while still receiving traffic
   information rather than separation. The estimate can misclassify a slow
   IFR private flight. These pairs are not resolved, since ATC gives VFR
   traffic information rather than separation outside Class B airspace, and helicopters around Philadelphia (police,
   news, medical) mostly fly VFR at low level. An aircraft
   squawking 7500, 7600 or 7700 is treated as an emergency and is never the
   one asked to maneuver. Pairs with both aircraft inside tower airspace
   (within 5 NM of PHL, PNE, TTN or ILG and below 2,500 ft) are counted
   but not alerted, as are pairs in which either aircraft is below 1,000 ft
   or slower than 40 kt (departing, landing, taxiing or hovering): near the
   runways the tower separates traffic visually
   and parallel approaches legally run much closer than radar minima, so
   terminal radar systems inhibit conflict alerts there too. Once an alert
   is active, it is held until the pair
   clears the minima by 0.5 NM or 200 ft, which keeps pairs hovering at the
   threshold from flickering between new and cleared.
3. `ATCEngine` tracks each alert by its aircraft pair, so an encounter keeps
   its first-seen time across refreshes. It groups conflicts that share
   aircraft. `ResolutionEngine` searches for altitude changes, then track
   changes, then speed reductions. Candidate checks use simplified aircraft
   performance, separation buffers, and changes already accepted in the cycle.
4. Each group is tested on its own trial copy of the tracks. A group is
   rejected if its maneuvers create a new predicted conflict. Rejecting one
   group does not affect the others.
5. A pair that is already inside both minima and has no maneuver that
   separates it in the trial is reported as separation already lost, the
   situation in which a controller issues a safety alert.
6. The cycle mode decides what happens next:
   - `ADVISE` (live OpenSky traffic): nothing is applied. Each alert carries
     its proposed instruction and whether it clears in simulation. An alert
     is reported cleared only when the observed tracks stop triggering it.
   - `SIMULATE` (scripted demo): accepted maneuvers are assigned to the demo
     tracks, and pairs they separate are reported cleared with the
     instruction that cleared them. `MovementEngine` then flies each
     assignment: the type's climb rate until level-off, a standard-rate turn
     of 3 degrees per second, and speed changes of 1 knot per second.
     Prediction uses the assigned altitude, heading and speed, the way a
     conflict probe uses cleared intent, and trials use the same climb
     profile, so a climb through an occupied level is rejected.
   - `DETECT_ONLY` (first eight seconds of the demo): alerts are shown and
     no maneuvers are evaluated.
7. Every cycle produces a `CycleReport` with active alerts, cleared alerts,
   and counters (alerts, new alerts, groups, proposals, accepted and rejected
   groups, pairs cleared in trial, pairs without a safe maneuver, pairs
   already inside the minima, held alerts and VFR traffic advisories). The
   counters are logged and shown above the conflict list. The engine hands
   the report to a `CycleListener`, so it has no dependency on the display.

## Desktop display

`WYJApp` loads the JavaFX layout and starts the engine after its embedded
WebView loads. `WYJAppController` serializes aircraft snapshots and forwards
them on the JavaFX application thread. The map replaces the displayed feed
tracks with each snapshot and clears source-specific tracks and alerts when
switching between OpenSky and the demo.

The map distinguishes live OpenSky, cached, and demo tracks. Live aircraft
types are shown as unknown. The engine uses the reported emitter category,
or an estimate from speed and altitude, when evaluating maneuvers.

## ATC context and limitations

WatchyoJet ATC models a subset of airborne conflict detection around an
airport. Approach and departure controllers provide terminal-area traffic
services. Tower and ground positions coordinate runway occupancy, taxi
movements, crossings, takeoff, and landing. The simulator's airport drawings
provide geographic context for those facilities. [FAA facility classifications](https://aspmhelp.faa.gov/index/OPSNET__Facility_Classification.html) · [FAA airport operations](https://www.faa.gov/air_traffic/publications/aim_html/chap4_section_3.html)

The simulation's alert thresholds are a simplified version of the FAA
radar minima, which also depend on surveillance mode, wake turbulence and
the airspace class. Separation of VFR aircraft inside Class B airspace is
not modeled. WatchyoJet uses public state vectors and straight-line
trajectories. Observed traffic is assumed to hold its reported vertical rate,
since OpenSky does not report assigned altitudes, and turns in progress are
not predicted. The model does not account for wind, pilot response time or
controller and pilot coordination. [FAA radar separation](https://www.faa.gov/air_traffic/publications/atpubs/atc_html/chap5_section_5.html)
