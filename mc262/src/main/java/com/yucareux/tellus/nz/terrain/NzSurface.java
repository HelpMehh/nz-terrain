package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.nz.tiles.NzTileLayer;
import com.yucareux.tellus.nz.tiles.NzTiles;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The surface blocks of one chunk of an NZ world (TERR-11), from the {@link NzSurfacePalette}. The chunk generator
 * hands every column to {@link #apply} instead of its own surface palette and snow, so none of Tellus's surface rules
 * (biome palettes, mountain rock, beaches, snow) run in NZ worlds.
 *
 * <p>Inputs per column, from the tiles at level 0: the LCDB class, the water class, the height in metres, and the
 * slope from Horn's 3 × 3 method on those heights, the same method as the pipeline's {@code slope.tif} (hub
 * {@code interfaces.md}), but in game degrees: the world is stretched 1.5× vertically, and the palette's thresholds
 * are what a player sees. The chunk's height grid gives each column's neighbours, so the side of a step shows the
 * palette's {@code face} material instead of plain stone.
 */
public final class NzSurface {
   private static final int CHUNK_SIDE = 16;
   /** The height grid around the chunk: one block of border, for the slope. */
   private static final int GRID = CHUNK_SIDE + 2;

   /** Writes one block of the chunk. */
   @FunctionalInterface
   public interface BlockWriter {
      void set(int localX, int localZ, int y, BlockState state);
   }

   private final NzSurfacePalette palette;
   private final int chunkMinX;
   private final int chunkMinZ;
   private final int chunkMinY;
   private final int chunkMaxY;
   private final int undergroundDepth;
   private final int[] heightGrid;
   private final int gridSize;
   private final int step;
   private final BlockWriter writer;
   /** Heights in metres, {@link #GRID} × {@link #GRID} from block (chunkMinX − 1, chunkMinZ − 1). */
   private final double[] metres;
   private final int[] lcdb;
   private final int[] water;
   /** Game blocks per metre of height: the slope of a ramp rising {@code m} metres over one block is {@code m × this}. */
   private final double blocksPerMetre;

   NzSurface(
      NzSurfacePalette palette,
      EarthGeneratorSettings settings,
      NzSeaFloor floor,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      int[] heightGrid,
      int gridSize,
      int step,
      BlockWriter writer
   ) {
      this.palette = palette;
      this.chunkMinX = chunkMinX;
      this.chunkMinZ = chunkMinZ;
      this.chunkMinY = chunkMinY;
      this.chunkMaxY = chunkMaxY;
      this.undergroundDepth = settings.undergroundDepth();
      this.heightGrid = heightGrid;
      this.gridSize = gridSize;
      this.step = step;
      this.writer = writer;
      this.blocksPerMetre = settings.effectiveTerrestrialHeightScale() / settings.effectiveVerticalWorldScale();
      this.metres = new double[GRID * GRID];
      for (int gz = 0; gz < GRID; gz++) {
         for (int gx = 0; gx < GRID; gx++) {
            this.metres[gz * GRID + gx] = NzTerrainSampler.pixelElevation(floor, 0, chunkMinX + gx - 1, chunkMinZ + gz - 1);
         }
      }
      NzTiles tiles = floor.tiles();
      this.lcdb = new int[CHUNK_SIDE * CHUNK_SIDE];
      this.water = new int[CHUNK_SIDE * CHUNK_SIDE];
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = localZ * CHUNK_SIDE + localX;
            int cover = tiles.sample(NzTileLayer.LANDCOVER, 0, chunkMinX + localX, chunkMinZ + localZ);
            // Nodata (outside the data) counts as "no LCDB polygon", as the tiles store it on land.
            this.lcdb[index] = NzTileLayer.LANDCOVER.isNodata(cover) ? 0 : cover;
            this.water[index] = tiles.sample(NzTileLayer.WATER, 0, chunkMinX + localX, chunkMinZ + localZ);
         }
      }
   }

   /**
    * The surface writer for one chunk of an NZ world, or null for any other world (the generator then uses its own
    * surfaces).
    *
    * @param heightGrid the generator's terrain Y around the chunk: {@code gridSize} × {@code gridSize}, the chunk's
    *    columns starting {@code step} in from the north-west corner, after water floors are applied
    * @param chunkMaxY one above the chunk's highest Y
    */
   public static NzSurface forChunk(
      EarthGeneratorSettings settings,
      int chunkMinX,
      int chunkMinZ,
      int chunkMinY,
      int chunkMaxY,
      int[] heightGrid,
      int gridSize,
      int step,
      BlockWriter writer
   ) {
      if (!NzWorldSettings.isNzWorld(settings)) {
         return null;
      }
      NzSeaFloor floor = NzTerrainSampler.forSettings(settings).seaFloor();
      return new NzSurface(
         NzSurfacePalette.global(), settings, floor, chunkMinX, chunkMinZ, chunkMinY, chunkMaxY, heightGrid, gridSize, step, writer
      );
   }

   /**
    * Writes the surface of one column whose top terrain block is at {@code surface}: the top, the layers under it, the
    * step face, and the cover on top on dry land. Never below the shell's stone ({@code underground_depth} blocks).
    *
    * @param underwater water stands on this column
    * @param ocean the water is the sea
    */
   public void apply(int localX, int localZ, int surface, boolean underwater, boolean ocean) {
      int x = this.chunkMinX + localX;
      int z = this.chunkMinZ + localZ;
      NzSurfacePalette.Surface kind = this.surfaceAt(localX, localZ, underwater, ocean);
      int bottom = Math.max(this.chunkMinY, surface - this.undergroundDepth + 1);
      int y = surface;
      this.set(localX, localZ, y--, kind.top().pick(x, surface, z));
      for (NzSurfacePalette.Layer layer : kind.under()) {
         for (int depth = layer.depth(x, z); depth > 0 && y >= bottom; depth--, y--) {
            this.set(localX, localZ, y, layer.block().pick(x, y, z));
         }
      }
      int lowestNeighbour = this.lowestNeighbour(localX, localZ);
      for (; y > lowestNeighbour && y >= bottom; y--) {
         this.set(localX, localZ, y, kind.face().pick(x, y, z));
      }
      if (!underwater && kind.cover() != null) {
         BlockState cover = kind.cover().pick(x, surface + 1, z);
         if (!cover.isAir()) {
            this.set(localX, localZ, surface + 1, cover);
         }
      }
   }

   /** Which surface a column gets: the water floor under water, else the first matching land rule. */
   NzSurfacePalette.Surface surfaceAt(int localX, int localZ, boolean underwater, boolean ocean) {
      int index = localZ * CHUNK_SIDE + localX;
      if (underwater) {
         if (ocean) {
            return this.palette.sea();
         }
         return this.water[index] == NzTerrainSampler.WATER_RIVER ? this.palette.river() : this.palette.lake();
      }
      return this.palette.land(this.lcdb[index], this.slopeDegrees(localX, localZ), this.metres[(localZ + 1) * GRID + localX + 1]);
   }

   /** The game slope at a column in degrees: Horn's method on the tile heights, scaled to block heights. */
   double slopeDegrees(int localX, int localZ) {
      int centre = (localZ + 1) * GRID + localX + 1;
      double a = this.metres[centre - GRID - 1];
      double b = this.metres[centre - GRID];
      double c = this.metres[centre - GRID + 1];
      double d = this.metres[centre - 1];
      double f = this.metres[centre + 1];
      double g = this.metres[centre + GRID - 1];
      double h = this.metres[centre + GRID];
      double i = this.metres[centre + GRID + 1];
      // Rise in metres per block, then in blocks per block.
      double dx = ((c + 2.0 * f + i) - (a + 2.0 * d + g)) / 8.0 * this.blocksPerMetre;
      double dz = ((g + 2.0 * h + i) - (a + 2.0 * b + c)) / 8.0 * this.blocksPerMetre;
      return Math.toDegrees(Math.atan(Math.sqrt(dx * dx + dz * dz)));
   }

   /** The lowest terrain Y of the four columns beside this one. */
   private int lowestNeighbour(int localX, int localZ) {
      int index = (localZ + this.step) * this.gridSize + localX + this.step;
      return Math.min(
         Math.min(this.heightGrid[index - 1], this.heightGrid[index + 1]),
         Math.min(this.heightGrid[index - this.gridSize], this.heightGrid[index + this.gridSize])
      );
   }

   private void set(int localX, int localZ, int y, BlockState state) {
      if (y >= this.chunkMinY && y < this.chunkMaxY) {
         this.writer.set(localX, localZ, y, state);
      }
   }
}
