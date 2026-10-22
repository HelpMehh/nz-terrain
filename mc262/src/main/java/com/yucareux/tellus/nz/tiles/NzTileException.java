package com.yucareux.tellus.nz.tiles;

/** A tile file or tile set that doesn't follow the tile format. The message names the file and what is wrong. */
public class NzTileException extends Exception {
   public NzTileException(String message) {
      super(message);
   }

   public NzTileException(String message, Throwable cause) {
      super(message, cause);
   }
}
