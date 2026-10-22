package com.yucareux.tellus.nz.tiles;

/**
 * One decoded layer of one tile: 512 × 512 stored values in row order (north to south, west to east). Read-only once
 * it leaves this package; the tile cache hands the same instance to every caller.
 */
public final class NzLayerData {
   private final NzTileLayer layer;
   private final byte[] bytes;
   private final short[] shorts;

   private NzLayerData(NzTileLayer layer, byte[] bytes, short[] shorts) {
      this.layer = layer;
      this.bytes = bytes;
      this.shorts = shorts;
   }

   static NzLayerData ofBytes(NzTileLayer layer, byte[] values) {
      return new NzLayerData(layer, values, null);
   }

   static NzLayerData ofShorts(NzTileLayer layer, short[] values) {
      return new NzLayerData(layer, null, values);
   }

   public NzTileLayer layer() {
      return this.layer;
   }

   /** The stored value of pixel (px, pz), as an unsigned number for uint8 and uint16 layers. */
   public int get(int px, int pz) {
      return this.get(pz * NzTileFormat.TILE_PIXELS + px);
   }

   /** The stored value at row-order index {@code pz * 512 + px}. */
   public int get(int index) {
      return switch (this.layer.type()) {
         case UINT8 -> this.bytes[index] & 0xFF;
         case INT16 -> this.shorts[index];
         case UINT16 -> this.shorts[index] & 0xFFFF;
      };
   }

   /** Approximate memory use, for the tile cache. */
   long sizeBytes() {
      return (long) NzTileFormat.TILE_AREA * this.layer.type().bytes() + 64;
   }

   boolean hasNodata() {
      if (!this.layer.hasNodata()) {
         return false;
      }
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         if (this.layer.isNodata(this.get(i))) {
            return true;
         }
      }
      return false;
   }

   /**
    * A copy in which every nodata pixel of this layer takes the value of {@code lower} (a set with lower priority);
    * returns this layer itself when there is nothing to fill.
    */
   NzLayerData fillNodataFrom(NzLayerData lower) {
      if (!this.hasNodata()) {
         return this;
      }
      byte[] mergedBytes = this.bytes == null ? null : this.bytes.clone();
      short[] mergedShorts = this.shorts == null ? null : this.shorts.clone();
      for (int i = 0; i < NzTileFormat.TILE_AREA; i++) {
         if (this.layer.isNodata(this.get(i))) {
            if (mergedBytes != null) {
               mergedBytes[i] = lower.bytes[i];
            } else {
               mergedShorts[i] = lower.shorts[i];
            }
         }
      }
      return new NzLayerData(this.layer, mergedBytes, mergedShorts);
   }
}
