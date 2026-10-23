package com.yucareux.tellus.nz.terrain;

import net.minecraft.world.level.block.state.BlockState;

/**
 * A weighted mix of blocks from the surface palette, and the pattern that picks one for each block position. The pick
 * depends only on the position and the mix's salt, so a column always gets the same blocks, whatever generates it and
 * in whatever order.
 */
final class NzSurfaceMix {
   /** How a mix spreads its blocks. */
   enum Pattern {
      /** Each block on its own: salt and pepper. */
      NOISE,
      /** One block per patch about {@code size} blocks across: jittered cells, so the patches have irregular edges. */
      PATCHES,
      /** One block per horizontal band {@code size} blocks thick, the bands gently tilted and wavy (strata). */
      BANDS
   }

   /** Width of the waves the bands follow, in blocks. */
   private static final int BAND_WAVE_PERIOD = 64;
   /** How far the bands rise and fall over a wave, in band thicknesses. */
   private static final double BAND_WAVE_AMPLITUDE = 1.5;

   private final BlockState[] blocks;
   /** Running totals of the weights: block i is picked for rolls from {@code cumulative[i - 1]} to below {@code cumulative[i]}. */
   private final int[] cumulative;
   private final Pattern pattern;
   private final int size;
   private final long salt;

   NzSurfaceMix(BlockState[] blocks, int[] weights, Pattern pattern, int size, long salt) {
      if (blocks.length == 0 || blocks.length != weights.length) {
         throw new IllegalArgumentException("a mix needs at least one block, each with a weight");
      }
      this.blocks = blocks.clone();
      this.cumulative = new int[weights.length];
      int total = 0;
      for (int i = 0; i < weights.length; i++) {
         if (weights[i] <= 0) {
            throw new IllegalArgumentException("weights must be above 0");
         }
         total = Math.addExact(total, weights[i]);
         this.cumulative[i] = total;
      }
      this.pattern = pattern;
      this.size = Math.max(1, size);
      this.salt = salt;
   }

   /** A "mix" of one block. */
   static NzSurfaceMix single(BlockState block) {
      return new NzSurfaceMix(new BlockState[]{block}, new int[]{1}, Pattern.NOISE, 1, 0L);
   }

   /** The block at (x, y, z). */
   BlockState pick(int x, int y, int z) {
      if (this.blocks.length == 1) {
         return this.blocks[0];
      }
      long roll = switch (this.pattern) {
         case NOISE -> hash(this.salt, x, y, z);
         case PATCHES -> this.patchHash(x, z);
         case BANDS -> this.bandHash(x, y, z);
      };
      return this.byRoll(roll);
   }

   /** The blocks of this mix, in the order of the file. */
   BlockState[] blocks() {
      return this.blocks.clone();
   }

   private BlockState byRoll(long roll) {
      int total = this.cumulative[this.cumulative.length - 1];
      int value = (int) Long.remainderUnsigned(roll, total);
      for (int i = 0; i < this.cumulative.length; i++) {
         if (value < this.cumulative[i]) {
            return this.blocks[i];
         }
      }
      return this.blocks[this.blocks.length - 1];
   }

   /** The hash of the patch holding column (x, z): the cell whose jittered centre is nearest. */
   private long patchHash(int x, int z) {
      int cellX = Math.floorDiv(x, this.size);
      int cellZ = Math.floorDiv(z, this.size);
      double px = x + 0.5;
      double pz = z + 0.5;
      double best = Double.MAX_VALUE;
      long bestHash = 0L;
      for (int dz = -1; dz <= 1; dz++) {
         for (int dx = -1; dx <= 1; dx++) {
            int cx = cellX + dx;
            int cz = cellZ + dz;
            long cell = hash(this.salt, cx, Integer.MIN_VALUE, cz);
            double centreX = (cx + unit(cell)) * this.size;
            double centreZ = (cz + unit(cell >>> 24)) * this.size;
            double distance = (px - centreX) * (px - centreX) + (pz - centreZ) * (pz - centreZ);
            if (distance < best) {
               best = distance;
               bestHash = cell;
            }
         }
      }
      return mix(bestHash);
   }

   /** The hash of the band holding block (x, y, z); the bands rise and fall smoothly across x and z. */
   private long bandHash(int x, int y, int z) {
      double shift = smoothNoise(this.salt, x, z, BAND_WAVE_PERIOD) * BAND_WAVE_AMPLITUDE * this.size;
      int band = Math.floorDiv(y + (int) Math.floor(shift), this.size);
      return hash(this.salt, 0, band, 0);
   }

   /** Smooth value noise in [-1, 1] over (x, z), with lattice points {@code period} blocks apart. */
   static double smoothNoise(long salt, int x, int z, int period) {
      double u = (double) x / period;
      double v = (double) z / period;
      int x0 = (int) Math.floor(u);
      int z0 = (int) Math.floor(v);
      double fx = smoothstep(u - x0);
      double fz = smoothstep(v - z0);
      double a = lattice(salt, x0, z0);
      double b = lattice(salt, x0 + 1, z0);
      double c = lattice(salt, x0, z0 + 1);
      double d = lattice(salt, x0 + 1, z0 + 1);
      double north = a + (b - a) * fx;
      double south = c + (d - c) * fx;
      return north + (south - north) * fz;
   }

   private static double lattice(long salt, int x, int z) {
      return unit(hash(salt, x, Integer.MAX_VALUE, z)) * 2.0 - 1.0;
   }

   private static double smoothstep(double t) {
      return t * t * (3.0 - 2.0 * t);
   }

   /** The top 24 bits of a hash as a number in [0, 1). */
   static double unit(long hash) {
      return (hash >>> 40) / (double) (1L << 24);
   }

   /** A well-mixed 64-bit hash of a position and a salt. */
   static long hash(long salt, int x, int y, int z) {
      long h = salt;
      h = mix(h ^ x * 0x9E3779B97F4A7C15L);
      h = mix(h ^ y * 0xC2B2AE3D27D4EB4FL);
      h = mix(h ^ z * 0x165667B19E3779F9L);
      return h;
   }

   /** The finaliser of MurmurHash3's 64-bit hash: every input bit changes about half the output bits. */
   static long mix(long h) {
      h ^= h >>> 33;
      h *= 0xFF51AFD7ED558CCDL;
      h ^= h >>> 33;
      h *= 0xC4CEB9FE1A85EC53L;
      h ^= h >>> 33;
      return h;
   }
}
