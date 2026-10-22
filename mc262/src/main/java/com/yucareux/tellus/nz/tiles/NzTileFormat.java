package com.yucareux.tellus.nz.tiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Constants and tile coordinates of the pipeline tile format, version 1. The format itself is specified in the
 * {@code mc-nz} hub, {@code .claude/context/interfaces.md}, "Pipeline → terrain: tile format".
 */
public final class NzTileFormat {
   public static final String FORMAT = "nz-tiles";
   public static final int FORMAT_VERSION = 1;
   public static final String MANIFEST_FILE = "tileset.json";
   /** Pixels along one side of a tile; a level-0 tile is one region. */
   public static final int TILE_PIXELS = 512;
   public static final int TILE_SHIFT = 9;
   public static final int TILE_AREA = TILE_PIXELS * TILE_PIXELS;
   /** Highest level a reader accepts. All of NZ needs about 9; this only guards the shifts. */
   public static final int MAX_LEVEL = 20;
   /** NZTM2000 origin of the block grid: {@code x = (E − E0) / 3}, {@code z = (N0 − N) / 3}. */
   public static final long E0 = 1_600_000L;
   public static final long N0 = 5_470_000L;
   public static final int METRES_PER_BLOCK = 3;

   static final byte[] MAGIC = "NZTILE".getBytes(StandardCharsets.US_ASCII);
   static final int HEADER_BYTES = 20;
   static final int ENTRY_BYTES = 32;
   static final int NAME_BYTES = 16;
   static final int FILTER_NONE = 0;
   static final int FILTER_PLANE = 1;
   static final int COMPRESSION_ZLIB = 1;

   private NzTileFormat() {
   }

   /** Blocks along one side of a level-{@code level} tile. */
   public static int tileBlocks(int level) {
      return TILE_PIXELS << level;
   }

   /** The tile index (tx or tz) holding block {@code block} at {@code level}. Floor division, so negative blocks work. */
   public static int tileOf(int block, int level) {
      return block >> (TILE_SHIFT + level);
   }

   /** The pixel (px or pz) inside its tile that holds block {@code block} at {@code level}. */
   public static int pixelOf(int block, int level) {
      return (block >> level) & (TILE_PIXELS - 1);
   }

   /** {@code <set>/L<level>/x<tx>/t.<tx>.<tz>.nzt}. */
   public static Path tilePath(Path setDir, int level, int tx, int tz) {
      return setDir.resolve("L" + level).resolve("x" + tx).resolve("t." + tx + "." + tz + ".nzt");
   }

   static void checkLevel(int level) {
      if (level < 0 || level > MAX_LEVEL) {
         throw new IllegalArgumentException("tile level " + level + " is outside 0.." + MAX_LEVEL);
      }
   }
}
