# Testing

## Scale test (TERR-1, not run yet)

Uses the released Tellus jar for 26.2 (not our code) to pick the vertical
scale.

- Horizontal: 7 m/block. Vertical: try 1:4 and 1:3.
- Places: the Remarkables (does it look dramatic?) and the Canterbury Plains
  (does it still look believable?).
- Record: settings used, screenshots taken, impressions, decision. The hub
  decisions are updated in HUB-1.

| Date | Vertical | Place | Result |
|---|---|---|---|

## Build check

`./gradlew -PtellusTargets=mc262 :mc262:build` (compiles and runs the
unit tests in `src/test`, the shared layers and `mc262/src/test`). Not run yet.
The first run downloads Gradle, Minecraft and mappings, so it takes a while.
