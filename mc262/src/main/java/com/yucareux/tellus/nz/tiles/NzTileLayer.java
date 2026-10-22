package com.yucareux.tellus.nz.tiles;

import java.util.Optional;

/**
 * The layers this reader knows, from the layer table of the tile format (hub {@code interfaces.md}). Values are the
 * stored integers (e.g. height in ⅛ m), not converted units. Layers a file has but this enum lacks are ignored.
 */
public enum NzTileLayer {
   HEIGHT("height", DataType.INT16, -32768),
   HEIGHT_SOURCE("height_source", DataType.UINT8, 255),
   WATER("water", DataType.UINT8, 255),
   WATER_LEVEL("water_level", DataType.INT16, -32768),
   BED("bed", DataType.INT16, -32768),
   LANDCOVER("landcover", DataType.UINT8, 255),
   VEGETATION("vegetation", DataType.UINT8, 255),
   CANOPY("canopy", DataType.UINT8, 255),
   STREAMS("streams", DataType.UINT8, null),
   ROCK("rock", DataType.UINT8, 255),
   COAST_DISTANCE("coast_distance", DataType.UINT16, null);

   private final String fileName;
   private final DataType type;
   private final Integer nodata;

   NzTileLayer(String fileName, DataType type, Integer nodata) {
      this.fileName = fileName;
      this.type = type;
      this.nodata = nodata;
   }

   /** The layer name as written in tile files and manifests. */
   public String fileName() {
      return this.fileName;
   }

   public DataType type() {
      return this.type;
   }

   /** False for layers where every value is data ({@code streams}, {@code coast_distance}). */
   public boolean hasNodata() {
      return this.nodata != null;
   }

   public boolean isNodata(int value) {
      return this.nodata != null && this.nodata == value;
   }

   /** The value where there is no data: nodata, or 0 for layers without one (as the pipeline's {@code fill_value}). */
   public int fill() {
      return this.nodata == null ? 0 : this.nodata;
   }

   public static Optional<NzTileLayer> byFileName(String name) {
      for (NzTileLayer layer : values()) {
         if (layer.fileName.equals(name)) {
            return Optional.of(layer);
         }
      }
      return Optional.empty();
   }

   public enum DataType {
      UINT8(1, 1),
      INT16(2, 2),
      UINT16(3, 2);

      private final int code;
      private final int bytes;

      DataType(int code, int bytes) {
         this.code = code;
         this.bytes = bytes;
      }

      /** The type code in a tile's layer entry. */
      public int code() {
         return this.code;
      }

      public int bytes() {
         return this.bytes;
      }
   }
}
