package com.yucareux.tellus.nz.tiles;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;

/** Writes small tile sets for tests, following the format like the pipeline's {@code tiles.py} writer. */
public final class NzTileTestFiles {
   private NzTileTestFiles() {
   }

   /** The pipeline fixture copied from {@code nz-data-pipeline/tests/fixtures/tiles/} (the folder holding {@code nz-main/}). */
   public static Path fixtureDir() {
      URL url = NzTileTestFiles.class.getResource("/nz-tiles-fixture/expected.json");
      if (url == null) {
         throw new IllegalStateException("tile fixture missing from the test resources");
      }
      try {
         return Path.of(url.toURI()).getParent();
      } catch (URISyntaxException e) {
         throw new IllegalStateException(e);
      }
   }

   public static int[] filled(int value) {
      int[] values = new int[NzTileFormat.TILE_AREA];
      Arrays.fill(values, value);
      return values;
   }

   public static void writeManifest(Path setDir, String name, String dataVersion, int priority) throws IOException {
      writeManifest(setDir, """
         {"format": "nz-tiles", "format_version": 1, "name": "%s", "data_version": "%s", "priority": %d,
          "crs": "EPSG:2193", "e0": 1600000, "n0": 5470000, "metres_per_block": 3}
         """.formatted(name, dataVersion, priority));
   }

   public static void writeManifest(Path setDir, String json) throws IOException {
      Files.createDirectories(setDir);
      Files.writeString(setDir.resolve(NzTileFormat.MANIFEST_FILE), json, StandardCharsets.UTF_8);
   }

   /** Writes tile (level, tx, tz) of a set with the given layers (512 × 512 values each, in file order). */
   public static Path writeTile(Path setDir, int level, int tx, int tz, Map<NzTileLayer, int[]> layers) throws IOException {
      Path path = NzTileFormat.tilePath(setDir, level, tx, tz);
      Files.createDirectories(path.getParent());
      Files.write(path, encodeTile(level, tx, tz, layers));
      return path;
   }

   static byte[] encodeTile(int level, int tx, int tz, Map<NzTileLayer, int[]> layers) {
      List<byte[]> blobs = new ArrayList<>();
      int dataStart = NzTileFormat.HEADER_BYTES + NzTileFormat.ENTRY_BYTES * layers.size();
      ByteBuffer head = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN);
      head.put(NzTileFormat.MAGIC).putShort((short) NzTileFormat.FORMAT_VERSION).put((byte) level).put((byte) layers.size())
         .putShort((short) 0).putInt(tx).putInt(tz);
      int offset = dataStart;
      for (Map.Entry<NzTileLayer, int[]> entry : layers.entrySet()) {
         NzTileLayer layer = entry.getKey();
         boolean plane = layer.type() != NzTileLayer.DataType.UINT8;
         byte[] blob = deflate(raw(layer, plane ? planeEncode(entry.getValue()) : entry.getValue()));
         byte[] name = Arrays.copyOf(layer.fileName().getBytes(StandardCharsets.US_ASCII), NzTileFormat.NAME_BYTES);
         head.put(name).put((byte) layer.type().code()).put((byte) (plane ? NzTileFormat.FILTER_PLANE : NzTileFormat.FILTER_NONE))
            .put((byte) NzTileFormat.COMPRESSION_ZLIB).put((byte) 0).putInt(offset).putInt(blob.length).putInt(0);
         blobs.add(blob);
         offset += blob.length;
      }
      ByteBuffer file = ByteBuffer.allocate(offset);
      file.put(head.array());
      blobs.forEach(file::put);
      return file.array();
   }

   /** {@code v − (left + up − upleft)}, neighbours outside the tile counted as 0, kept to 16 bits. */
   static int[] planeEncode(int[] values) {
      int size = NzTileFormat.TILE_PIXELS;
      int[] stored = new int[values.length];
      for (int pz = 0; pz < size; pz++) {
         for (int px = 0; px < size; px++) {
            int i = pz * size + px;
            int left = px > 0 ? values[i - 1] : 0;
            int up = pz > 0 ? values[i - size] : 0;
            int upLeft = px > 0 && pz > 0 ? values[i - size - 1] : 0;
            stored[i] = (values[i] - left - up + upLeft) & 0xFFFF;
         }
      }
      return stored;
   }

   private static byte[] raw(NzTileLayer layer, int[] values) {
      ByteBuffer buffer = ByteBuffer.allocate(values.length * layer.type().bytes()).order(ByteOrder.LITTLE_ENDIAN);
      for (int value : values) {
         if (layer.type() == NzTileLayer.DataType.UINT8) {
            buffer.put((byte) value);
         } else {
            buffer.putShort((short) value);
         }
      }
      return buffer.array();
   }

   private static byte[] deflate(byte[] raw) {
      Deflater deflater = new Deflater(6);
      try {
         deflater.setInput(raw);
         deflater.finish();
         byte[] out = new byte[raw.length + 1024];
         int length = 0;
         while (!deflater.finished()) {
            length += deflater.deflate(out, length, out.length - length);
         }
         return Arrays.copyOf(out, length);
      } finally {
         deflater.end();
      }
   }
}
