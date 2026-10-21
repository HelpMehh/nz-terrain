# Testing

## Scale test (TERR-1, not run yet)

Uses the released Tellus jar for 26.2 (not our code) to check the chosen
scale: 3 m/block horizontal, 2 m/block vertical, sea level Y 63, world
Y −272 to 2,031.

- Places: the Remarkables (does it look dramatic?), the Canterbury Plains
  (does it still look believable?) and Milford Sound.
- Record: settings used, screenshots taken, impressions, which DH build
  works, DH distance vs frame rate, DH database and world folder sizes. The
  hub decisions are updated in HUB-1.

| Date | Settings | Place | Result |
|---|---|---|---|

## Build check

`./gradlew -PtellusTargets=mc262 :mc262:build` (compiles and runs the
unit tests in `src/test`, the shared layers and `mc262/src/test`). Not run yet.
The first run downloads Gradle, Minecraft and mappings, so it takes a while.
