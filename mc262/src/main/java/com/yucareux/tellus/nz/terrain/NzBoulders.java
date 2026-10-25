package com.yucareux.tellus.nz.terrain;

import com.yucareux.tellus.nz.NzWorldSettings;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Boulders in NZ worlds (TERR-14 step 4): 1:1 rocks of 1 to a few blocks on riverbeds and gravel flats, in stream
 * beds, below cliffs, on scree and moraine, mossy in the bush. Decided 24 Oct 2026 (option (a)): a pass of its own
 * after the trees, which the chunk generator calls once per chunk while decorating it, from one hook.
 *
 * <ul>
 *    <li>Where: the surface palette's {@code boulders} rules ({@link NzSurfacePalette.BoulderRule}), by the column's
 *    surface (as {@link NzSurface} picks it), slope, height, and whether it lies below a cliff: the ground rises at
 *    least {@link #CLIFF_RISE_BLOCKS} within {@link #CLIFF_RADIUS_BLOCKS} (a square), about 63° on average, which
 *    includes the foot of a gorge wall. A matching column is the middle of a boulder with the rule's chance.</li>
 *    <li>Which: every pick hashes the block position with the rule's salt, like the palette's mixes, so a column
 *    always gets the same boulder, whatever the world seed and generation order.</li>
 *    <li>Shape: a rounded dome, as wide as the rule's size (stretched a little along one axis), about half to
 *    four fifths as high, standing on the lowest ground under it; on higher ground under it only its top shows. A
 *    boulder whose ground varies by more than {@link #MAX_FOOTPRINT_DROP_BLOCKS} is left out (it would be a pillar on
 *    a step), and so is one that doesn't fit inside the chunk: a chunk's own trees are placed before the boulders,
 *    so a boulder never lands in a trunk, and a later chunk's trees never land on a boulder.</li>
 *    <li>Placing: only into air, water and plants. A boulder with anything solid in its way (a trunk, leaves,
 *    another boulder) or standing over a hole (a cave mouth, a lava pool) is left out; the chunk's boulders are
 *    placed in a fixed order, so the same ones win every time. The top block of each column above the water is the
 *    rule's {@code top} (moss in the bush).</li>
 * </ul>
 *
 * <p>The ground comes from the tiles ({@link NzWater#column}), the same terrain the chunk was filled with.
 */
public final class NzBoulders {
   private static final int CHUNK_SIDE = 16;
   /** Below a cliff: the ground rises at least {@link #CLIFF_RISE_BLOCKS} within this many blocks (a square). */
   static final int CLIFF_RADIUS_BLOCKS = 4;
   static final int CLIFF_RISE_BLOCKS = 8;
   /** A boulder whose ground varies by more than this is left out. */
   static final int MAX_FOOTPRINT_DROP_BLOCKS = 2;
   /** A boulder's height as a share of its size, picked between these. */
   static final double MIN_HEIGHT_RATIO = 0.5;
   static final double MAX_HEIGHT_RATIO = 0.8;
   /** A boulder is stretched along one axis and squeezed along the other by up to this share. */
   static final double MAX_STRETCH = 0.2;
   /** Each column's height varies by this many blocks (±) around the dome, so the top isn't a neat step pyramid. */
   static final double TOP_JITTER_BLOCKS = 0.4;
   /** No water on a column. */
   static final int NONE = Integer.MIN_VALUE;
   private static final int WINDOW = CHUNK_SIDE + 2 * CLIFF_RADIUS_BLOCKS;
   private static final BlockState AIR = Blocks.AIR.defaultBlockState();

   /** Reads and writes blocks of the world, by block position. */
   interface World {
      BlockState get(int x, int y, int z);

      void set(int x, int y, int z, BlockState state);
   }

   /**
    * One boulder: {@code size} × {@code size} columns from block (minX, minZ), row by row; for each, its ground and the
    * Y of the boulder's top ({@link #NONE} where the boulder leaves the column as it is), and the water surface.
    */
   record Boulder(int minX, int minZ, int size, int[] ground, int[] top, int[] water, NzSurfacePalette.BoulderRule rule) {
   }

   private final NzSurfacePalette palette;
   private final int chunkMinX;
   private final int chunkMinZ;
   private final NzSurface surfaces;
   /** Terrain Y (water floors) and water surface ({@link #NONE}: dry) of {@link #WINDOW} × {@link #WINDOW} columns around the chunk. */
   private final int[] ground = new int[WINDOW * WINDOW];
   private final int[] water = new int[WINDOW * WINDOW];
   private final boolean[] ocean = new boolean[WINDOW * WINDOW];

   NzBoulders(NzSurfacePalette palette, EarthGeneratorSettings settings, NzSeaFloor floor, NzLedges ledges, int chunkMinX, int chunkMinZ) {
      this.palette = palette;
      this.chunkMinX = chunkMinX;
      this.chunkMinZ = chunkMinZ;
      this.surfaces = NzSurface.columns(palette, settings, floor, ledges, chunkMinX, chunkMinZ);
      NzStreams streams = ledges.streams();
      for (int wz = 0; wz < WINDOW; wz++) {
         for (int wx = 0; wx < WINDOW; wx++) {
            int x = chunkMinX + wx - CLIFF_RADIUS_BLOCKS;
            int z = chunkMinZ + wz - CLIFF_RADIUS_BLOCKS;
            int terrain = NzTerrainSampler.sample(floor, settings, x, z, 0).terrainHeight();
            WaterSurfaceResolver.WaterColumnData column = NzWater.column(streams, x, z, 0, terrain);
            int index = wz * WINDOW + wx;
            this.ground[index] = column.terrainSurface();
            this.water[index] = column.hasWater() ? column.waterSurface() : NONE;
            this.ocean[index] = column.isOcean();
         }
      }
   }

   /** Places the boulders of one chunk of an NZ world while it is decorated; does nothing in any other world. */
   public static void place(EarthGeneratorSettings settings, WorldGenLevel level, ChunkAccess chunk) {
      if (!NzWorldSettings.isNzWorld(settings)) {
         return;
      }
      NzWater water = NzWater.forSettings(settings);
      NzBoulders boulders = new NzBoulders(
         NzSurfacePalette.global(), settings, water.seaFloor(), water.ledges(), chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ()
      );
      BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
      boulders.placeAll(new World() {
         @Override
         public BlockState get(int x, int y, int z) {
            return level.getBlockState(pos.set(x, y, z));
         }

         @Override
         public void set(int x, int y, int z, BlockState state) {
            level.setBlock(pos.set(x, y, z), state, Block.UPDATE_CLIENTS);
         }
      });
   }

   /** Places every boulder of the chunk into {@code world}; returns how many were placed. */
   int placeAll(World world) {
      int placed = 0;
      for (Boulder boulder : this.boulders()) {
         if (place(world, boulder)) {
            placed++;
         }
      }
      return placed;
   }

   /** The boulders whose middle lies in the chunk, before looking at the world's blocks. */
   List<Boulder> boulders() {
      List<Boulder> boulders = new ArrayList<>();
      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            Boulder boulder = this.boulderAt(localX, localZ);
            if (boulder != null) {
               boulders.add(boulder);
            }
         }
      }
      return boulders;
   }

   /** The boulder whose middle is column (localX, localZ) of the chunk, or null. */
   Boulder boulderAt(int localX, int localZ) {
      int x = this.chunkMinX + localX;
      int z = this.chunkMinZ + localZ;
      int index = this.window(localX, localZ);
      String surface = this.surfaces.surfaceAt(localX, localZ, this.water[index] != NONE, this.ocean[index]).name();
      NzSurfacePalette.BoulderRule rule = this.palette.boulders(
         surface, this.surfaces.slopeDegrees(localX, localZ), this.surfaces.heightMetres(localX, localZ), this.isBelowCliff(localX, localZ)
      );
      if (rule == null || NzSurfaceMix.unit(NzSurfaceMix.hash(rule.salt(), x, 0, z)) >= rule.chance()) {
         return null;
      }
      int size = rule.size(NzSurfaceMix.hash(rule.salt(), x, 1, z));
      // The middle of the boulder: the middle of this column for odd sizes, its north-west corner for even ones.
      int startX = localX - size / 2;
      int startZ = localZ - size / 2;
      if (startX < 0 || startZ < 0 || startX + size > CHUNK_SIDE || startZ + size > CHUNK_SIDE) {
         return null;
      }
      double middleX = startX + size / 2.0;
      double middleZ = startZ + size / 2.0;
      long shape = NzSurfaceMix.hash(rule.salt(), x, 2, z);
      double stretch = NzSurfaceMix.unit(shape) * MAX_STRETCH;
      double radius = size / 2.0 - 0.1;
      boolean alongX = (shape & 1L) == 0L;
      double radiusX = radius * (alongX ? 1.0 + stretch : 1.0 - stretch);
      double radiusZ = radius * (alongX ? 1.0 - stretch : 1.0 + stretch);
      double ratio = MIN_HEIGHT_RATIO + NzSurfaceMix.unit(NzSurfaceMix.hash(rule.salt(), x, 3, z)) * (MAX_HEIGHT_RATIO - MIN_HEIGHT_RATIO);
      int height = Math.max(1, (int) Math.round(size * ratio));

      int[] ground = new int[size * size];
      int[] top = new int[size * size];
      int[] water = new int[size * size];
      int lowest = Integer.MAX_VALUE;
      int highest = Integer.MIN_VALUE;
      for (int dz = 0; dz < size; dz++) {
         for (int dx = 0; dx < size; dx++) {
            int i = dz * size + dx;
            int w = this.window(startX + dx, startZ + dz);
            ground[i] = this.ground[w];
            water[i] = this.water[w];
            lowest = Math.min(lowest, ground[i]);
            highest = Math.max(highest, ground[i]);
         }
      }
      if (highest - lowest > MAX_FOOTPRINT_DROP_BLOCKS) {
         return null;
      }
      for (int dz = 0; dz < size; dz++) {
         for (int dx = 0; dx < size; dx++) {
            int i = dz * size + dx;
            double ux = (startX + dx + 0.5 - middleX) / radiusX;
            double uz = (startZ + dz + 0.5 - middleZ) / radiusZ;
            double q2 = ux * ux + uz * uz;
            top[i] = NONE;
            if (q2 > 1.0) {
               continue;
            }
            // A dome with a flattish top (a superellipse), roughened per column.
            int columnX = this.chunkMinX + startX + dx;
            int columnZ = this.chunkMinZ + startZ + dz;
            double jitter = (NzSurfaceMix.unit(NzSurfaceMix.hash(rule.salt(), columnX, 4, columnZ)) * 2.0 - 1.0) * TOP_JITTER_BLOCKS;
            int columnHeight = Math.max(1, (int) Math.round(height * Math.sqrt(1.0 - q2 * q2) + jitter));
            int columnTop = lowest + columnHeight;
            if (columnTop > ground[i]) {
               top[i] = columnTop;
            }
         }
      }
      return new Boulder(this.chunkMinX + startX, this.chunkMinZ + startZ, size, ground, top, water, rule);
   }

   /** Places one boulder unless something solid is in its way or it would stand over a hole; returns whether it did. */
   static boolean place(World world, Boulder boulder) {
      int size = boulder.size();
      for (int i = 0; i < size * size; i++) {
         if (boulder.top()[i] == NONE) {
            continue;
         }
         int x = boulder.minX() + i % size;
         int z = boulder.minZ() + i / size;
         if (!isSolid(world.get(x, boulder.ground()[i], z))) {
            return false;
         }
         for (int y = boulder.ground()[i] + 1; y <= boulder.top()[i]; y++) {
            if (!isReplaceable(world.get(x, y, z))) {
               return false;
            }
         }
      }
      NzSurfacePalette.BoulderRule rule = boulder.rule();
      for (int i = 0; i < size * size; i++) {
         int top = boulder.top()[i];
         if (top == NONE) {
            continue;
         }
         int x = boulder.minX() + i % size;
         int z = boulder.minZ() + i / size;
         for (int y = boulder.ground()[i] + 1; y <= top; y++) {
            boolean dryTop = y == top && (boulder.water()[i] == NONE || y > boulder.water()[i]);
            world.set(x, y, z, (dryTop ? rule.top() : rule.block()).pick(x, y, z));
         }
         // A plant standing on the new rock (the top half of a tall plant whose foot is now rock) goes.
         BlockState above = world.get(x, top + 1, z);
         if (!above.isAir() && above.getFluidState().isEmpty() && isReplaceable(above)) {
            world.set(x, top + 1, z, AIR);
         }
      }
      return true;
   }

   /** Whether column (localX, localZ) of the chunk lies below a cliff: see {@link #CLIFF_RISE_BLOCKS}. */
   boolean isBelowCliff(int localX, int localZ) {
      int own = this.ground[this.window(localX, localZ)];
      for (int dz = -CLIFF_RADIUS_BLOCKS; dz <= CLIFF_RADIUS_BLOCKS; dz++) {
         for (int dx = -CLIFF_RADIUS_BLOCKS; dx <= CLIFF_RADIUS_BLOCKS; dx++) {
            if (this.ground[this.window(localX + dx, localZ + dz)] - own >= CLIFF_RISE_BLOCKS) {
               return true;
            }
         }
      }
      return false;
   }

   /** Air, water and anything a player walks through (plants, snow layers): a boulder may take its place. */
   static boolean isReplaceable(BlockState state) {
      return state.canBeReplaced() || state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   /** Ground a boulder can stand on: not air, fluid or a plant. */
   private static boolean isSolid(BlockState state) {
      return !state.isAir() && state.getFluidState().isEmpty() && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
   }

   private int window(int localX, int localZ) {
      return (localZ + CLIFF_RADIUS_BLOCKS) * WINDOW + localX + CLIFF_RADIUS_BLOCKS;
   }
}
