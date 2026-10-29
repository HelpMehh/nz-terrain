package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The surface blocks of Distant Horizons' fast LOD in an NZ world (TERR-15): the same {@link NzSurfacePalette} as the
 * chunks ({@link NzSurface}), read at the tile level of the LOD cell, so distant terrain has the colours of the near
 * terrain and reads only coarse tiles. The DH generator asks this instead of Tellus's {@code resolveLodSurface} and
 * {@code resolveUltraFastLodSurface}.
 *
 * <p>Per LOD column, from the finest tile level no finer than the cell: the water class under water (sea, lake, river;
 * a stream at level 0), else the first land rule for the LCDB class (the most common one in the pixel), the slope and
 * the height. The slope is Horn's 3 × 3 method on that level's pixels, in game degrees, so it is the slope of the
 * terrain as DH draws it: averaged over the cell, a cliff in a forest slope stays forest far away.
 *
 * <p>DH draws a column as its top block over one filler block reaching down to the floor, and the side of a column
 * standing above its neighbour shows the filler. Up close a slope is a staircase of 1-block steps whose sides hide
 * under the tops, so the filler is the top's own mix: a forested hillside stays forest-coloured from afar instead of
 * showing a stripe of dirt at each LOD step. Only where the chunks show a face (surfaces without "under" layers, and
 * slopes steep enough for {@code bare_step}) is the filler the face mix. Cliff ledges, gorge ledges and boulders are
 * too small for DH and are left out.
 */
public final class NzLodSurface {
   private final NzTerrainSampler sampler;
   /** Game blocks per metre of height, as in {@link NzSurface}. */
   private final double blocksPerMetre;

   private NzLodSurface(EarthGeneratorSettings settings) {
      this.sampler = NzTerrainSampler.forSettings(settings);
      this.blocksPerMetre = settings.effectiveTerrestrialHeightScale() / settings.effectiveVerticalWorldScale();
   }

   /** The LOD surfaces of an NZ world, or null for any other world (DH then uses Tellus's surfaces). */
   public static NzLodSurface forSettings(EarthGeneratorSettings settings) {
      return NzWorldSettings.isNzWorld(settings) ? new NzLodSurface(settings) : null;
   }

   /**
    * The top and filler block of the LOD column at block (x, z), whose top terrain block is at {@code surface}.
    *
    * @param underwater water stands on the column
    * @param resolutionMetres the LOD cell's size in metres (DH's {@code previewResolutionMeters})
    */
   public EarthChunkGenerator.LodSurface surface(int x, int z, int surface, boolean underwater, double resolutionMetres) {
      return surface(NzSurfacePalette.global(), this.sampler.seaFloor(), this.blocksPerMetre, NzTerrainSampler.levelFor(resolutionMetres), x, z, surface, underwater);
   }

   /** As {@link #surface(int, int, int, boolean, double)}, reading from level {@code minLevel} up (the tests set it directly). */
   static EarthChunkGenerator.LodSurface surface(
      NzSurfacePalette palette,
      NzSeaFloor floor,
      double blocksPerMetre,
      int minLevel,
      int x,
      int z,
      int surface,
      boolean underwater
   ) {
      NzTiles tiles = floor.tiles();
      int level = tiles.finestLevel(NzTileLayer.HEIGHT, minLevel, x, z);
      NzSurfacePalette.Surface kind;
      double slope = 0.0;
      if (level < 0) {
         // Outside the data: the open sea, whatever DH's water says.
         kind = palette.sea();
      } else if (underwater) {
         kind = switch (tiles.sample(NzTileLayer.WATER, level, x, z)) {
            case NzTerrainSampler.WATER_RIVER -> palette.river();
            case NzTerrainSampler.WATER_LAKE -> palette.lake();
            case NzTerrainSampler.WATER_LAND -> palette.stream();
            default -> palette.sea();
         };
      } else {
         int px = x >> level;
         int pz = z >> level;
         double[] metres = new double[9];
         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               metres[(dz + 1) * 3 + dx + 1] = NzTerrainSampler.pixelElevation(floor, level, px + dx, pz + dz);
            }
         }
         // A level-L pixel is 2^L blocks across, so its rise per block is the rise per pixel over 2^L.
         slope = NzSurface.slopeDegrees(metres, 4, 3, blocksPerMetre / (1 << level));
         int lcdb = tiles.sample(NzTileLayer.LANDCOVER, level, x, z);
         kind = palette.land(NzTileLayer.LANDCOVER.isNodata(lcdb) ? 0 : lcdb, slope, metres[4]);
      }

      BlockState top = kind.top().pick(x, surface, z);
      if (!underwater && kind.cover() != null) {
         // A cover (the snow on snowy rock) lies on the top block; DH has one block for both, so the cover shows.
         BlockState cover = kind.cover().pick(x, surface + 1, z);
         if (!cover.isAir()) {
            top = cover;
         }
      }
      // The chunks leave out the "under" layers on a step of bare_step blocks or more; a column's step to its
      // neighbour is the rise of one block of slope.
      boolean bare = kind.under().isEmpty() || Math.tan(Math.toRadians(slope)) >= palette.bareStep();
      BlockState filler = bare ? kind.face().pick(x, surface - 1, z) : kind.top().pick(x, surface - 1, z);
      return new EarthChunkGenerator.LodSurface(top, filler);
   }
}
