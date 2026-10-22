package com.yucareux.tellus.nz;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Optional;

/**
 * The NZ part of a world's generator settings, saved as the {@code nz} key next to the Tellus keys.
 * A world is an NZ world when the key is present; Tellus Earth worlds don't have it.
 * Empty for now: later steps add fields here (e.g. the tile data versions), not to {@link EarthGeneratorSettings}.
 */
public record NzWorldSettings() {
   public static final Codec<NzWorldSettings> CODEC = MapCodec.unitCodec(NzWorldSettings::new);
   private static final MapCodec<Optional<NzWorldSettings>> FIELD_CODEC = CODEC.optionalFieldOf("nz");

   public static boolean isNzWorld(EarthGeneratorSettings settings) {
      return settings.nz().isPresent();
   }

   /**
    * Adds the {@code nz} key to Tellus's settings codec. The key is applied after Tellus has decoded all of its own keys,
    * because Tellus's private {@code with…} copies use the old constructor and would drop it.
    */
   public static MapCodec<EarthGeneratorSettings> withNzKey(MapCodec<EarthGeneratorSettings> tellusCodec) {
      return RecordCodecBuilder.mapCodec(
         instance -> instance.group(
               tellusCodec.forGetter(settings -> settings),
               FIELD_CODEC.forGetter(EarthGeneratorSettings::nz)
            )
            .apply(instance, NzWorldSettings::withNz)
      );
   }

   /** A copy of {@code s} with {@code nz} replaced. Needs updating whenever upstream adds a settings component. */
   public static EarthGeneratorSettings withNz(EarthGeneratorSettings s, Optional<NzWorldSettings> nz) {
      return new EarthGeneratorSettings(
         s.worldScale(), s.terrestrialHeightScale(), s.oceanicHeightScale(), s.heightOffset(),
         s.spawnLatitude(), s.spawnLongitude(), s.minAltitude(), s.maxAltitude(),
         s.riverLakeShorelineBlend(), s.oceanShorelineBlend(), s.shorelineBlendCliffLimit(), s.caveGeneration(), s.oreDistribution(),
         s.geologicalStonePatches(), s.lavaPools(),
         s.addStrongholds(), s.addVillages(), s.addMineshafts(), s.addOceanMonuments(), s.addWoodlandMansions(),
         s.addDesertTemples(), s.addJungleTemples(), s.addPillagerOutposts(), s.addRuinedPortals(), s.addShipwrecks(),
         s.addOceanRuins(), s.addBuriedTreasure(), s.addIgloos(), s.addWitchHuts(), s.addAncientCities(),
         s.addTrialChambers(), s.addTrailRuins(), s.deepDark(), s.geodes(), s.distantHorizonsWaterResolver(),
         s.distantHorizonsOsmFeatures(), s.distantHorizonsOsmRoadMaxDetail(), s.distantHorizonsOsmBuildingMaxDetail(),
         s.distantHorizonsOsmNonBlockingFetch(), s.realtimeTime(), s.realtimeWeather(), s.historicalSnow(),
         s.voxyChunkPregenEnabled(), s.voxyChunkPregenMaxRadius(), s.voxyChunkPregenChunksPerTick(),
         s.distantHorizonsRenderMode(), s.demSelection(), s.enableRoads(), s.enableBuildings(), s.enableWater(),
         s.thinShellTerrain(), s.climateBasedBuiltUpTerrain(), s.randomBiomes(), s.randomBiomeDensity(),
         s.randomBiomeSeed(), s.randomBiomeIds(), s.experimentalIncreaseHeight(), s.tellusManagedTerrainDownloads(),
         s.showTerrainDownloadOverlay(), s.cavesReachSurface(), s.undergroundDepth(), s.customTrees(),
         s.automaticHeightScaling(), s.hugeRedMushrooms(),
         nz
      );
   }
}
