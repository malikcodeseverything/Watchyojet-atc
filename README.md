# WatchyoJet ATC

[![Build and test](https://github.com/malikcodeseverything/watchyojet-atc/actions/workflows/build.yml/badge.svg)](https://github.com/malikcodeseverything/watchyojet-atc/actions/workflows/build.yml)

WatchyoJet is an educational, real-time air-traffic conflict detection and
resolution simulator focused on the Philadelphia terminal area. It combines
live public OpenSky state vectors with a two-second simulation loop, closest
point of approach (CPA) prediction, and an interactive JavaFX/WebView display.

> **Safety notice:** WatchyoJet is a student-built simulation and portfolio
> project. It is not certified aviation software, must not be used for
> operational decision-making, and never transmits instructions to aircraft.

![WatchyoJet interface](NEW_WatchyojetGUI.jpg)

## Highlights

- Tracks public live traffic around Philadelphia when OpenSky is available.
- Retains the last-known snapshot during transient API failures.
- Labels cached traffic as stale and switches to demo traffic after 60 seconds.
- Loads a deterministic demo scenario when no initial live snapshot is available.
- Applies a U.S. terminal profile (3 NM, two-minute alert horizon below 10,000
  ft) and an en-route profile (5 NM, five-minute alert horizon) using CPA
  geometry.
- Projects reported climb/descent rates for up to three minutes when evaluating
  vertical separation and rejects stale state vectors older than 20 seconds.
- Keeps live OpenSky traffic strictly observational: broad separation-standard
  predictions are counted as monitored proximity advisories, while red alerts
  require a CPA within 60 seconds, 1 NM, and 500 ft. Automated maneuvering is
  available only in deterministic demo mode.
- Groups related conflicts before proposing altitude, heading, or speed changes.
- Refuses to invent a maneuver when no candidate passes the safety checks.
- Displays live aircraft, conflicts, resolutions, and an event history.
- Runs automated tests on Linux, macOS, and Windows through GitHub Actions.

## Run locally

Requirements:

- A full JDK 21 or newer
- Maven 3.8 or newer

```bash
git clone https://github.com/malikcodeseverything/watchyojet-atc.git
cd watchyojet-atc
mvn javafx:run
```

Live mode is the default. To run the deterministic offline scenario:

```bash
mvn javafx:run -Dwatchyojet.demo=true
```

The first Maven run downloads the required JavaFX and Jackson dependencies.

## Test and build

Run the complete test suite:

```bash
mvn clean verify
```

Create a platform-specific runtime image:

```bash
mvn clean verify javafx:jlink
```

Create a self-contained application image for the current operating system:

```bash
./scripts/package-app.sh
```

The result is placed in `target/dist`. Packaging must run separately on each
target operating system because JavaFX includes platform-native components.

## How it works

```text
OpenSky state vectors / demo scenario
                 |
                 v
          AircraftManager
                 |
       two-second engine cycle
                 |
     +-----------+------------+
     |           |            |
 movement   CPA detection   resolution search
     |           |            |
     +-----------+------------+
                 |
                 v
       JavaFX + embedded WebView
```

The resolution output represents hypothetical shadow-mode decisions. Every
fresh OpenSky observation restores the reported aircraft state so simulated
commands cannot be mistaken for real changes.

The post-resolution check reports any additional predicted conflicts but does
not roll back hypothetical commands. This is appropriate only for a shadow-mode
demonstrator and is another reason the project must not be used operationally.
Speed thresholds use OpenSky ground speed because indicated airspeed is not
provided by the state-vector feed.

The separation profiles are intentionally operating-zone based rather than
city based. FAA Class B and C shelves are individually tailored, while the
public state-vector feed does not identify controller jurisdiction, IFR/VFR
status, wake category, assigned procedure, or runway configuration. WatchyoJet
therefore must not claim facility-level compliance that its source data cannot
support.

## Reliability and security

- Network requests use connect and response timeouts.
- Malformed or physically implausible state vectors are rejected.
- External callsigns and UI messages are JSON-encoded at the JavaScript boundary.
- CI receives read-only repository permissions.
- Dependabot monitors Maven and GitHub Actions dependencies.
- The app does not require API keys or collect user information.

## Project structure

```text
src/main/java/com/watchyojet/
  engine/       conflict prediction and resolution
  manager/      aircraft state and OpenSky ingestion
  model/        aircraft, conflict, and resolution models
  simulation/   movement and deterministic demo traffic
src/main/resources/
  map.html      interactive traffic display
src/test/java/
  regression and geometry tests
```

## Attribution

WatchyoJet began as a Temple University CIS 3296 team project in the
[`cis3296s26`](https://github.com/cis3296s26/final-project-05-watchyojet)
organization. The preserved Git history records contributions from Hamza Malik,
Brandon Son, Dylan Vo, and the contributors represented by the
`RevengeChivalry` account. This personal fork is maintained by Hamza Malik for
portfolio hardening, testing, packaging, and deployment work.

No open-source license has been added because this originated as a team course
project. Reuse requires permission from the relevant contributors.
