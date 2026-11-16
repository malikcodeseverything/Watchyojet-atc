# WatchyoJet ATC

[![Build and test](https://github.com/malikcodeseverything/Watchyojet-atc/actions/workflows/build.yml/badge.svg)](https://github.com/malikcodeseverything/Watchyojet-atc/actions/workflows/build.yml)

WatchyoJet ATC is a Java desktop simulator for air traffic conflict detection
and resolution around Philadelphia. It predicts where two aircraft will come
closest, groups related conflicts, and tests simulated changes in altitude,
track, or speed.

![WatchyoJet ATC desktop interface from the project demo](NEW_WatchyojetGUI.jpg)

## Run the demo

Install a full JDK 21 or newer and Maven 3.8 or newer. Maven downloads the
JavaFX and Jackson dependencies on the first build.

```bash
git clone https://github.com/malikcodeseverything/Watchyojet-atc.git
cd Watchyojet-atc
mvn javafx:run -Dwatchyojet.demo=true
```

The map opens on Philadelphia. The scripted scenario starts with ten aircraft
and three predicted conflict pairs. Open the conflict panel and resolution history to follow the encounters.
The conflicts are visible for eight seconds before the simulator tries its
maneuvers. Use **Simulation → Replay demo** to restart, or wait for the
automatic one-minute reset. Demo traffic runs without an OpenSky account
or a live feed.

Live traffic is the default when you launch with `mvn javafx:run`. You can
switch modes in the app under **Simulation → Live traffic** or **Simulation →
Offline demo**. If OpenSky is temporarily unavailable, the app labels cached
tracks and falls back to scripted traffic when the cache expires. It returns
to OpenSky automatically when requests succeed again. Live coverage and
request quotas depend on OpenSky.

## How it works

```text
OpenSky or demo fixture
        ↓
AircraftManager
        ↓
Movement → closest point of approach → maneuver search → conflict recheck
        ↓
JavaFX controller → WebView traffic display
```

The simulator uses 3 NM laterally in the terminal area around PHL (below
FL180, within 40 NM) and 5 NM elsewhere, with 1,000 ft vertically. It
predicts with each aircraft's climb or descent rate, looks 2 minutes ahead in
the terminal area and 5 minutes elsewhere, treats VFR traffic (code 1200) as
traffic advisories rather than conflicts, and never moves an aircraft
squawking an emergency code. Pairs inside tower airspace (within 5 NM of PHL,
PNE, TTN or ILG and below 2,500 ft) are left to the tower, the way terminal
radar systems inhibit alerts near runways. Each group of related conflicts gets
its own trial: proposed maneuvers are tested on copies of the tracks, and a
group is rejected only if its maneuvers create a new predicted conflict.

Live traffic is advisory only. The engine never changes an OpenSky track. Each
alert keeps its identity across refreshes and shows the proposed instruction,
for example "DAL123 climb and maintain 12,000 ft · clears in simulation". An
alert is marked cleared only when the observed tracks stop triggering the
detector. The offline demo applies accepted maneuvers to its scripted tracks.
Every cycle logs how many alerts were detected, how many groups were accepted
or rejected, and how many pairs clear in simulation.

WatchyoJet ATC is an educational simulation. In the demo, maneuvers apply
instantly to the scripted tracks. It models airborne conflicts and does not issue real
clearances or transmit instructions to aircraft. See the
[system documentation](Documentation/WATCHYOJET_DOCS.md) for the data flow,
limitations, and comparison with FAA procedures.

## Build and test

```bash
mvn clean verify
mvn clean verify javafx:jlink
```

GitHub Actions runs the test suite on Linux, macOS, and Windows. The
`javafx:jlink` goal creates a platform-specific runtime in `target/watchyojet`.
On macOS or Linux, run `./scripts/package-app.sh` with a full JDK to build
an application image in `target/dist`.

## Project structure

| Path | Contents |
| --- | --- |
| `src/main/java/com/watchyojet/engine/` | Conflict prediction, maneuver search, and simulation cycle |
| `src/main/java/com/watchyojet/manager/` | Aircraft state and OpenSky ingestion |
| `src/main/java/com/watchyojet/model/` | Aircraft, conflict, and maneuver models |
| `src/main/java/com/watchyojet/simulation/` | Movement and scripted encounters |
| `src/main/resources/` | JavaFX layout, styles, and the WebView map |
| `src/test/java/` | Movement, conflict, resolution, and demo regression tests |

## Project credits

WatchyoJet began as a Temple University CIS 3296 team project. Hamza Malik
maintains this portfolio fork and led the work to stabilize the demo, test
the conflict engine, and prepare the desktop build for release. The Git
history preserves contributions from Hamza Malik, Brandon Son, Dylan Vo,
and the contributors behind the `RevengeChivalry` account. [Original course repository](https://github.com/cis3296s26/final-project-05-watchyojet)

No open-source license has been added. Reuse requires permission from the
relevant contributors.
