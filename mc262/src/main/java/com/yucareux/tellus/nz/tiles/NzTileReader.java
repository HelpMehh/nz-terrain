package com.yucareux.tellus.nz.tiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads one layer of one {@code .nzt} tile file. Only the header, the layer entries and the wanted layer's bytes are
 * read, so decoding {@code water} doesn't pay for {@code height}.
 */
public final class NzTileReader {
   private NzTileReader() {
   }

   /**
    * Decodes {@code layer} from the tile file at {@code file}, which must be tile ({@code level}, {@code tx},
    * {@code tz}). Returns null if the file doesn't have that layer.
    *
    * @throws java.nio.file.NoSuchFileException if there is no such file (no data there)
    * @throws NzTileException if the file doesn't follow the format or is corrupt
    */
   public static NzLayerData readLayer(Path file, int level, int tx, int tz, NzTileLayer layer) throws IOException, NzTileException {
      try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
         long size = channel.size();
         ByteBuffer header = read(channel, file, size, 0, NzTileFormat.HEADER_BYTES, "the header");
         byte[] magic = new byte[NzTileFormat.MAGIC.length];
         header.get(magic);
         if (!Arrays.equals(magic, NzTileFormat.MAGIC)) {
            throw new NzTileException(file + ": not a tile file (no NZTILE magic)");
         }
         int version = Short.toUnsignedInt(header.getShort());
         if (version != NzTileFormat.FORMAT_VERSION) {
            throw new NzTileException(file + ": format version " + version + "; this reader knows only " + NzTileFormat.FORMAT_VERSION);
         }
         int fileLevel = Byte.toUnsignedInt(header.get());
         int count = Byte.toUnsignedInt(header.get());
         header.getShort();
         int fileTx = header.getInt();
         int fileTz = header.getInt();
         if (fileLevel != level || fileTx != tx || fileTz != tz) {
            throw new NzTileException(
               file + ": header says level " + fileLevel + ", tile " + fileTx + ", " + fileTz
                  + "; the path says level " + level + ", tile " + tx + ", " + tz
            );
         }

         ByteBuffer entries = read(channel, file, size, NzTileFormat.HEADER_BYTES, NzTileFormat.ENTRY_BYTES * count, count + " layer entries");
         for (int i = 0; i < count; i++) {
            byte[] rawName = new byte[NzTileFormat.NAME_BYTES];
            entries.get(rawName);
            int type = Byte.toUnsignedInt(entries.get());
            int filter = Byte.toUnsignedInt(entries.get());
            int compression = Byte.toUnsignedInt(entries.get());
            entries.get();
            long offset = Integer.toUnsignedLong(entries.getInt());
            int length = entries.getInt();
            entries.getInt();
            if (!layer.fileName().equals(layerName(rawName))) {
               continue;
            }
            if (length < 0) {
               throw new NzTileException(file + ": layer " + layer.fileName() + " is too long");
            }
            ByteBuffer compressed = read(channel, file, size, offset, length, "layer " + layer.fileName());
            return decode(file, layer, type, filter, compression, compressed);
         }
         return null;
      }
   }

   private static NzLayerData decode(Path file, NzTileLayer layer, int type, int filter, int compression, ByteBuffer compressed)
      throws NzTileException {
      String where = file + ": layer " + layer.fileName();
      if (type != layer.type().code()) {
         throw new NzTileException(where + ": data type " + type + ", the format says " + layer.type().code());
      }
      if (compression != NzTileFormat.COMPRESSION_ZLIB) {
         throw new NzTileException(where + ": unknown compression " + compression);
      }
      if (filter != NzTileFormat.FILTER_NONE && filter != NzTileFormat.FILTER_PLANE) {
         throw new NzTileException(where + ": unknown filter " + filter);
      }
      if (filter == NzTileFormat.FILTER_PLANE && layer.type() == NzTileLayer.DataType.UINT8) {
         throw new NzTileException(where + ": the plane filter is for 16-bit layers only");
      }

      byte[] raw = inflate(where, compressed, NzTileFormat.TILE_AREA * layer.type().bytes());
      if (layer.type() == NzTileLayer.DataType.UINT8) {
         return NzLayerData.ofBytes(layer, raw);
      }
      short[] values = new short[NzTileFormat.TILE_AREA];
      ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(values);
      if (filter == NzTileFormat.FILTER_PLANE) {
         planeDecode(values);
      }
      return NzLayerData.ofShorts(layer, values);
   }

   /**
    * Undoes the plane filter in place: {@code v = stored + left + up − upleft}, with neighbours outside the tile counted
    * as 0. The cast to short wraps modulo 2^16, as the format asks.
    */
   static void planeDecode(short[] values) {
      int size = NzTileFormat.TILE_PIXELS;
      for (int pz = 0; pz < size; pz++) {
         int row = pz * size;
         for (int px = 0; px < size; px++) {
            int i = row + px;
            int left = px > 0 ? values[i - 1] : 0;
            int up = pz > 0 ? values[i - size] : 0;
            int upLeft = px > 0 && pz > 0 ? values[i - size - 1] : 0;
            values[i] = (short) (values[i] + left + up - upLeft);
         }
      }
   }

   /** zlib-decompresses exactly {@code expected} bytes. The Inflater checks zlib's Adler-32 checksum. */
   private static byte[] inflate(String where, ByteBuffer compressed, int expected) throws NzTileException {
      Inflater inflater = new Inflater();
      try {
         inflater.setInput(compressed);
         byte[] raw = new byte[expected];
         int filled = 0;
         while (filled < expected && !inflater.finished()) {
            int n = inflater.inflate(raw, filled, expected - filled);
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
               break;
            }
            filled += n;
         }
         if (filled == expected && !inflater.finished() && inflater.inflate(new byte[1]) > 0) {
            throw new NzTileException(where + ": more than 512 × 512 values");
         }
         if (filled != expected || !inflater.finished()) {
            throw new NzTileException(where + ": " + filled + " bytes, not 512 × 512 values (truncated or corrupt)");
         }
         return raw;
      } catch (DataFormatException e) {
         throw new NzTileException(where + ": corrupt data (" + e.getMessage() + ")", e);
      } finally {
         inflater.end();
      }
   }

   private static ByteBuffer read(FileChannel channel, Path file, long size, long position, int length, String what)
      throws IOException, NzTileException {
      if (position + length > size) {
         throw new NzTileException(file + ": file is " + size + " bytes, too short for " + what);
      }
      ByteBuffer buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
      while (buffer.hasRemaining()) {
         if (channel.read(buffer, position + buffer.position()) < 0) {
            throw new NzTileException(file + ": unexpected end of file in " + what);
         }
      }
      return buffer.flip();
   }

   private static String layerName(byte[] raw) {
      int end = 0;
      while (end < raw.length && raw[end] != 0) {
         end++;
      }
      return new String(raw, 0, end, StandardCharsets.US_ASCII);
   }
}
