package com.yucareux.tellus.nz.tiles;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.yucareux.tellus.platform.TellusPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * All tile sets found in the tiles folder, merged, with a memory cache of decoded tiles.
 *
 * <p>Merging: for each layer and pixel, the first set (lowest priority) with data there wins. Layers without nodata
 * can't be merged by pixel, so there the first set with the tile wins.
 *
 * <p>One store serves every world in the JVM, like Tellus's data sources ({@link #global()}). It is rebuilt at each
 * server start, so tiles changed between two sessions are picked up.
 */
public final class NzTiles {
   private static final Logger LOGGER = LoggerFactory.getLogger("tellus/nz");
   private static final long EMPTY_ENTRY_BYTES = 64;
   private static volatile NzTiles global;

   private final Path dir;
   private final List<NzTileSet> sets;
   private final List<String> problems;
   private final LoadingCache<Key, Optional<NzLayerData>> cache;
   private final Set<Path> loggedBadTiles = ConcurrentHashMap.newKeySet();

   private NzTiles(Path dir, List<NzTileSet> sets, List<String> problems, long cacheBytes) {
      this.dir = dir;
      this.sets = List.copyOf(sets);
      this.problems = List.copyOf(problems);
      this.cache = CacheBuilder.newBuilder()
         .maximumWeight(cacheBytes)
         .weigher((Key key, Optional<NzLayerData> value) -> (int) Math.min(Integer.MAX_VALUE, value.map(NzLayerData::sizeBytes).orElse(EMPTY_ENTRY_BYTES)))
         .build(CacheLoader.from(this::load));
   }

   /** The store for the configured tiles folder ({@link NzTilesConfig}), opened on first use. */
   public static NzTiles global() {
      NzTiles tiles = global;
      if (tiles == null) {
         synchronized (NzTiles.class) {
            tiles = global;
            if (tiles == null) {
               tiles = openConfigured();
               global = tiles;
            }
         }
      }
      return tiles;
   }

   /** Re-reads the config and the tiles folder and drops the cache. Called at each server start. */
   public static synchronized NzTiles reloadGlobal() {
      NzTiles tiles = openConfigured();
      global = tiles;
      return tiles;
   }

   /**
    * The data version of each tile set in the configured folder right now, read fresh from the manifests. Used when
    * a world is created, to save the versions it was made from.
    */
   public static Map<String, String> currentDataVersions() {
      try {
         NzTilesConfig config = NzTilesConfig.load(TellusPlatform.gameDir(), TellusPlatform.configDir());
         return scan(config.tilesDir(), new ArrayList<>()).stream()
            .collect(LinkedHashMap::new, (map, set) -> map.put(set.name(), set.dataVersion()), Map::putAll);
      } catch (RuntimeException e) {
         // Called while the world presets load: throwing here would break every world type, not just NZ.
         LOGGER.error("Can't read the NZ tile data versions; the world records none", e);
         return Map.of();
      }
   }

   /** Opens every tile set in {@code dir} (each subfolder with a {@code tileset.json}); refused sets are logged. */
   public static NzTiles open(Path dir, long cacheBytes) {
      List<String> problems = new ArrayList<>();
      List<NzTileSet> sets = scan(dir, problems);
      for (String problem : problems) {
         LOGGER.error("NZ tile set refused: {}", problem);
      }
      for (NzTileSet set : sets) {
         LOGGER.info("NZ tile set: {}", set);
      }
      if (sets.isEmpty()) {
         LOGGER.warn("No NZ tile sets found in {}", dir);
      }
      return new NzTiles(dir, sets, problems, cacheBytes);
   }

   private static NzTiles openConfigured() {
      NzTilesConfig config = NzTilesConfig.load(TellusPlatform.gameDir(), TellusPlatform.configDir());
      return open(config.tilesDir(), config.cacheBytes());
   }

   private static List<NzTileSet> scan(Path dir, List<String> problems) {
      List<NzTileSet> sets = new ArrayList<>();
      if (!Files.isDirectory(dir)) {
         return sets;
      }
      List<Path> folders;
      try (Stream<Path> children = Files.list(dir)) {
         folders = children.filter(child -> Files.isRegularFile(child.resolve(NzTileFormat.MANIFEST_FILE))).sorted().toList();
      } catch (IOException e) {
         problems.add(dir + ": can't list the folder (" + e + ")");
         return sets;
      }
      Set<String> names = new HashSet<>();
      for (Path folder : folders) {
         try {
            NzTileSet set = NzTileSet.open(folder);
            if (!names.add(set.name())) {
               problems.add(folder + ": another set is also named \"" + set.name() + "\"");
               continue;
            }
            sets.add(set);
         } catch (NzTileException e) {
            problems.add(e.getMessage());
         }
      }
      sets.sort(Comparator.comparingInt(NzTileSet::priority).thenComparing(NzTileSet::name));
      return sets;
   }

   /** The folder searched for tile sets. */
   public Path dir() {
      return this.dir;
   }

   /** The usable sets, in merge order (lowest priority first). */
   public List<NzTileSet> sets() {
      return this.sets;
   }

   public boolean isEmpty() {
      return this.sets.isEmpty();
   }

   /** Why sets in the folder were refused, one message each. */
   public List<String> problems() {
      return this.problems;
   }

   /** Set name → data version, for every usable set. */
   public Map<String, String> dataVersions() {
      Map<String, String> versions = new LinkedHashMap<>();
      for (NzTileSet set : this.sets) {
         versions.put(set.name(), set.dataVersion());
      }
      return versions;
   }

   /**
    * One layer of tile ({@code level}, {@code tx}, {@code tz}), merged over all sets; null where no set has data.
    * Pixels no set covers hold the layer's nodata.
    */
   public NzLayerData layer(NzTileLayer layer, int level, int tx, int tz) {
      NzTileFormat.checkLevel(level);
      return this.cache.getUnchecked(new Key(layer, level, tx, tz)).orElse(null);
   }

   /** The stored value at block (x, z) from the level-{@code level} tiles, or {@link NzTileLayer#fill()} where there is no data. */
   public int sample(NzTileLayer layer, int level, int x, int z) {
      NzLayerData data = this.layer(layer, level, NzTileFormat.tileOf(x, level), NzTileFormat.tileOf(z, level));
      return data == null ? layer.fill() : data.get(NzTileFormat.pixelOf(x, level), NzTileFormat.pixelOf(z, level));
   }

   private Optional<NzLayerData> load(Key key) {
      NzLayerData merged = null;
      for (NzTileSet set : this.sets) {
         NzLayerData data = this.readLogged(set, key);
         if (data == null) {
            continue;
         }
         merged = merged == null ? data : merged.fillNodataFrom(data);
         if (!merged.hasNodata()) {
            break;
         }
      }
      return Optional.ofNullable(merged);
   }

   /** A broken tile is logged once and treated as missing, so one bad download doesn't stop the server. */
   private NzLayerData readLogged(NzTileSet set, Key key) {
      try {
         return set.read(key.layer(), key.level(), key.tx(), key.tz());
      } catch (IOException | NzTileException e) {
         Path file = NzTileFormat.tilePath(set.dir(), key.level(), key.tx(), key.tz());
         if (this.loggedBadTiles.add(file)) {
            LOGGER.error("Can't read NZ tile, treating it as missing: {}", e.getMessage());
         }
         return null;
      }
   }

   /**
    * What changed between the tile sets a world was created with and the ones found now, one line per set, empty
    * when nothing did. Chunks made from different data can show seams where they meet.
    */
   public static List<String> describeDataVersionChanges(Map<String, String> world, Map<String, String> now) {
      List<String> changes = new ArrayList<>();
      Set<String> names = new TreeSet<>(world.keySet());
      names.addAll(now.keySet());
      for (String name : names) {
         String before = world.get(name);
         String after = now.get(name);
         if (before == null) {
            changes.add(name + ": new since the world was created (data " + after + ")");
         } else if (after == null) {
            changes.add(name + ": the world was created with it (data " + before + "), but it is missing now");
         } else if (!before.equals(after)) {
            changes.add(name + ": world created with data " + before + ", the tiles are now " + after);
         }
      }
      return changes;
   }

   private record Key(NzTileLayer layer, int level, int tx, int tz) {
   }
}
