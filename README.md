# MegaMek Alpha Strike mode

A fan-made **Alpha Strike** game mode for [MegaMek](https://megamek.org) 0.51.0. You use MegaMek's normal lobby and hex map, but the game is played with Alpha Strike: Commander's Edition rules, Alpha Strike cards and PV, against human players or AI bots.

**Status:** beta, by aranami. This is not an official MegaMek release.

## Features

- **Host Alpha Strike Game** from MegaMek's main menu.
  - Build lists in MegaMek's own lobby.
  - Units show their AS card values and PV.
  - Random Army works in PV.
- **C3 Groups** button in the lobby: build C3, C3i and Nova networks by clicking units.
- **AI bots:**
  - AI v3: looks ahead before each move.
  - AI v2.1: a trained network.
  - A simple scripted bot.
- **Deployment phase**, movement with the reachable hexes shown, and combat with the full to-hit breakdown. Click an enemy you can't shoot to see why.
- **Rules:**
  - Overheat, criticals and heat.
  - Woods and partial cover.
  - Rear and turret weapons.
  - C3 networks, ECM, Angel ECM and light ECM.
  - Stealth and mimetic armor.
  - AMS, shields, TSM and industrial TSM.
  - CR, FR, CASE and CASE II, ENE, OVL, MEL, BAR, ARM, HT, JMPS and JMPW.
- **Map overlays:** ECM halos and C3 network lines, which you can switch on and off.

See [CHANGELOG.md](CHANGELOG.md) for what changed in each version.

## Play it (Windows)

1. Install MegaMek 0.51.0 and Java 21 (e.g. [Temurin 21](https://adoptium.net)).
2. Download the latest `MegaMek_AS_Beta_x.y.z.zip` from **Releases** (on the right of this page) and unzip it anywhere.
3. Double-click `Play_Alpha_Strike.bat`.
   - The first time, it asks for your MegaMek folder.
   - It then builds `MegaMek-AS.jar` next to the .bat. Your MegaMek folder isn't changed.

`README_BETA.txt` in the zip explains how to play.

## Build from source

You need the Java 21 JDK and MegaMek 0.51.0.

```
build.bat "C:\Games\MegaMek-0.51.0"
```

This compiles `src` against MegaMek's jar and libraries and writes `megamek-as-classes.jar`.

`Play_Alpha_Strike.bat` (in the release zip) merges that jar with your `MegaMek.jar` into `MegaMek-AS.jar`, using the merge tool `megamek.common.alphaStrike.tools.MakeMegaMekAS`.

- **New files:** the Alpha Strike classes, under the `alphaStrike` packages and `client/ui/...`.
- **Patched copies:** a few MegaMek files (lobby, Random Army, board view) are patched copies of the MegaMek 0.51.0 source.

The AI bots run in a separate program, `as_ai_engine.exe`, with its trained model `as_ai_model.bin`. Both are in the release zip. Without them, only the scripted bot is available.

## Bugs and ideas

Email **aranami85@gmail.com**, or open an issue here. Say what you did and what happened, and attach `<MegaMek folder>\logs\megamek.log`.

## License

- **This code:** GNU GPL v3 (see [LICENSE](LICENSE)), the same as MegaMek.
- **MegaMek's data and art:** not included. They come from your own MegaMek install and are licensed CC BY-NC-SA 4.0 by the MegaMek team.
- **Trademarks:** BattleTech and Alpha Strike are trademarks of The Topps Company, Inc. This is a non-commercial fan project.
