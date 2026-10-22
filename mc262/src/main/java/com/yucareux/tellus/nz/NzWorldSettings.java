package com.yucareux.tellus.nz;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import java.util.Map;
import java.util.Optional;

/**
 * The NZ part of a world's generator settings, saved as the {@code nz} key next to the Tellus keys.
 * A world is an NZ world when the key is present; Tellus Earth worlds don't have it.
 * New NZ settings go here, not into {@link EarthGeneratorSettings}.
 *
 * @param dataVersions tile set name → data version of the tiles the world was created with
 */
public record NzWorldSettings(Map<String, String> dataVersions) {
   /**
    * {@code data_versions} is missing only in the preset ({@code "nz": {}}) and in worlds made before it existed.
    * Decoding then fills in the tiles found now. The preset is decoded when a world is created, so the world saves
    * the versions it was made from, with no hook in world creation.
    */
   public static final Codec<NzWorldSettings> CODEC = RecordCodecBuilder.create(
      instance -> instance.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("data_versions").forGetter(settings -> Optional.of(settings.dataVersions()))
         )
         .apply(instance, versions -> new NzWorldSettings(versions.orElseGet(NzTiles::currentDataVersions)))
   );
   private static final MapCodec<Optional<NzWorldSettings>> FIELD_CODEC = CODEC.optionalFieldOf("nz");

   public NzWorldSettings {
      dataVersions = Map.copyOf(dataVersions);
   }

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
