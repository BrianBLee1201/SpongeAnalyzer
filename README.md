# SpongeAnalyzer

SpongeAnalyzer is a **Fabric-based Minecraft analysis tool** that locates ocean monuments **and infers the number of sponge rooms inside each monument**. This tool avoids scanning blocks in the overworld.
Current existing tools such as Chunkbase locate ocean monuments and report monument coordinates, but unlike them, SpongeAnalyzer answers a more practical question:

> **Is this monument worth raiding?**

Sponge rooms are the primary source of wet sponges in survival gameplay. Sponges are essential for draining large bodies of water, building trenches, and constructing farms (e.g., squid farms). Sponges are faster to drain water than placing gravity blocks to clear it, so the only way to get them is to raid the ocean monuments. In very niche situations, you may need a lot of wet and dry sponges for creative builds with unique palettes. However, **ocean monuments do not guarantee sponge rooms**.

From empirical analysis of thousands of monuments:
- Out of 50000 monuments, **~11299 contained zero sponge rooms**, which is roughly **22.598% of monuments with no sponge rooms at all**
- This means roughly 93.534% monuments contain **0–3 sponge rooms**. This also means **getting 4+ sponge rooms are rarer.**
- The most common number of sponge rooms that I calculated is **1, with an experimental probability of 32.975%, followed by 2 (25.220%), 0 (22.598%), and then 3 (12.741%).**
- Currently, SpongeAnalyzer identified a monument with **10 sponge rooms**, exceeding commonly assumed limits (some people asserted the max is 7, but now it is changed). 

Before this tool exists, you had no way of knowing whether the monument has sponge rooms. Chances are you could get unlucky, for example, for raiding 10 ocean monuments that has no sponge rooms, only getting 30 wet sponges in return.

---

## Why Sponge Rooms Matter

- There is exactly 3 Elder Guardians in each monument, and each Elder Guardian only drops **one wet sponge each for player kill**
- Looting enchantment does **not** increase sponge drops
- Large-scale water removal requires **dozens or hundreds** of sponges. Clearing with sand/gravel and then digging them would also take more time.
- Raiding monuments without sponge rooms is not only unlucky but also often a waste of time

**As of 3 February 2026: while a structure piece I identified was responsible for producing sponge rooms, it is not known if such code exists, so the only way to know is to load a chunk via ticket and then count the number of sponge rooms in a monument.**

---

## Key Technical Discovery for counting Sponge Rooms (Core Innovation)

### Naive Approach (Too Slow)

Initially, I wanted to scan a 58x58 block area from Y-levels 40 to 63 and then count `WET_SPONGE` blocks using `getBlockState()`, but the problem with this approach is that it is computationally expensive and requires loading many chunks.

### Structure-based Insight (Fast & Efficient)

Through debugging and inspection of Minecraft's structure data, I finally figured which structure is responsible for generating sponge rooms.

Ocean monuments are generated using a fixed internal structure layout composed of **33 structure pieces**. However, not all pieces are available. Each available piece is a vertex, and there is an edge (an opening) that connects to another piece, effectively creating a maze.

While community documentation (e.g.  
https://minecraft.fandom.com/wiki/Ocean_Monument/Structure) describes monument size and chambers, it does **not** document internal room *types*.

### Reverse-Engineered Insight

Through structure introspection and meticulous debugging, the following was discovered:

> **Every sponge room corresponds to a structure piece of type**  
> `OceanMonumentGenerator$SimpleRoomTop`

Therefore:
- The number of sponge rooms in a monument = the number of `SimpleRoomTop` pieces in its structure layout
- No block scanning is required
- No wet sponge counting is required
- No world traversal is required beyond structure generation

This discovery enables **fast, deterministic sponge room inference** directly from structure metadata.

---

## How SpongeAnalyzer Works

1. **Precompute candidate monument coordinates** within the specified radius.
2. **Divide candidates into batches** according to `batchSize`. See **Running SpongeAnalyzer** section on why we need to use `batchSize`.
3. **For each batch:**
   - Analyze each coordinate by extracting structure layout metadata.
   - Count `SimpleRoomTop` structure pieces to infer sponge rooms.
4. **Combine batch results** into a final `results.csv`.
5. **Output sponge room distribution and estimated total wet sponges**, considering elder guardian drops.
6. **[OPTIONAL] Output Xaero's waypoints**, helping you go to the location identified with number of sponge rooms.

---

## Minecraft Version Compatibility (1.18+)

Currently, this tool only works on versions 1.18+. I used the configuration below:

- **Minecraft:** 1.21.11
- **Fabric Loader:** 0.18.4
- **Fabric API:** 0.139.5+1.21.11
- **Fabric Loom:** 1.15.0-alpha.6

As long as the version you are playing on in your world is 1.18+, you will be fine.

---


## Requirements

- **Git:** required for cloning my repository, though optional if you happen to download this as a zip file and then extracting it (though not recommended, as I make changes)
   - If this tool is missing, your terminal may report errors such as `command not found` or fail during the native build step.
- **Java:** version 21

### Git Installation

- Download: https://git-scm.com/install
- Windows users: Ensure Git is added to your PATH during installation.

After installation, verify Git is available:

```bash
git --version
```

If this command fails, restart your terminal and re-check your system PATH.

Now clone the repository:

```bash
git clone https://github.com/BrianBLee1201/SpongeAnalyzer
cd SpongeAnalyzer
```

No additional dependencies are required beyond Gradle.

### Java 21 and Gradle configuration

SpongeAnalyzer requires **Java 21** to run correctly. Ensure your system has Java 21 installed and configured for Gradle.

### Configuring Java 21 for Gradle

1. **Install Java 21** from [Oracle Website](https://www.oracle.com/java/technologies/downloads/).

2. **Set Java 21 as Gradle's JVM:**

   - **Option A:** Modify `gradle.properties` in the project root (replace `/path` as your actual path to Java 21):

     ```
     org.gradle.java.home=/path/to/java21
     ```

   - **Option B:** Set as an environment variable before running Gradle (replace `/path` as your actual path to Java 21):

     ```bash
     export JAVA_HOME=/path/to/java21
     ```

3. **Verify Java 21 is used:**

   ```bash
   ./gradlew -version
   ```

   If you see something like this (Pay attention to Launcher JVM and Daemon JVM):

   ```bash
   ------------------------------------------------------------
   Gradle 9.2.1
   ------------------------------------------------------------

   Build time:    2025-11-17 13:40:48 UTC
   Revision:      30ecdc708db275e8f8769ea0620f6dd919a58f76

   Kotlin:        2.2.20
   Groovy:        4.0.28
   Ant:           Apache Ant(TM) version 1.10.15 compiled on August 25 2024
   Launcher JVM:  21.0.10 (Oracle Corporation 21.0.10+8-LTS-217)
   Daemon JVM:    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home (from org.gradle.java.home)
   OS:            Mac OS X 26.2 aarch64
   ```

   This means you have installed Java 21 as a JVM version. 

---

## Running SpongeAnalyzer

Use the `runAll` Gradle task to perform analysis (do not include []):

```bash
./gradlew -Dsponge.seed=<WORLD_SEED> \
          [-Dsponge.radiusBlocks=<RADIUS>] \
          [-Dsponge.excludeRadiusBlocks=<INNER_RADIUS>] \
          [-Dsponge.maxResults=<MAX_RESULTS>] \
          [-Dsponge.batchSize=<BATCH_SIZE>] \
          [-Dsponge.xaeroExport=<0|(any number)>] \
          [-Dsponge.xaeroMinRooms=<MIN_ROOMS>] \
          [-Dsponge.xaeroDims=<overworld|nether|both>] \
          [-Dsponge.xaeroColor=<0|1|2|3|4|5|6|7|8|9|10|11|12|13|14|15>]
          runAll
```

### Arguments

| Argument                  | Description                                                                                      | Default    |
|---------------------------|------------------------------------------------------------------------------------------------|------------|
| `-Dsponge.seed`            | **Required.** The Minecraft world seed to analyze (must be a number).                                             | N/A        |
| `-Dsponge.radiusBlocks`    | Search radius in blocks (as a square) around the world origin (0,0).                                         | 20000     |
| `-Dsponge.excludeRadiusBlocks` | Inner square radius (in blocks) to exclude from the search. Enables ring-based scans for large worlds. | 0 (full square)          |
| `-Dsponge.maxResults`      | Maximum number of ocean monuments to analyze.                                                  | 100000        |
| `-Dsponge.batchSize`       | Number of monument coordinates processed per batch to control memory usage and avoid heap errors. | 1000       |
| `-Dsponge.xaeroExport`     | Option to export Xaero's waypoints (recommended if you want to quickly get sponges via travelling). | 0 (disables export) |
|`-Dsponge.xaeroMinRooms` | **[Requires -Dsponge.xaeroExport to be enabled. Otherwise, it does nothing]** A minimum sponge rooms threshold. Anything below it will not be recorded. _Note: You can set to 0 if you want to log all rooms, though there will be too many coordinates._| 4|
|`-Dsponge.xaeroDims` | **[Requires -Dsponge.xaeroExport to be enabled. Otherwise, it does nothing]** Specifies which dimension you want to record Xaero's waypoints. | overworld |
|`-Dsponge.xaeroColor`| **[Requires -Dsponge.xaeroExport to be enabled. Otherwise, it does nothing]** Specifies which color you want for waypoints' label (0=black, 1=dark blue, 2=dark green, 3=dark aqua, 4=dark red, 5=dark purple, 6=gold, 7=gray, 8=dark gray, 9=blue, 10=green, 11=aqua, 12=red, 13=light purple, 14=yellow, 15=white).| 11 (aqua)|


Example:

```bash
./gradlew -Dsponge.seed=-1789333 -Dsponge.radiusBlocks=10000 -Dsponge.maxResults=10000 -Dsponge.batchSize=500 runAll
```

This command:
- sets seed to -1789333
- searches the square with endpoints (-10k, -10k), (-10k, 10k), (10k, -10k), and (10k, 10k).
- sets the ocean monument threshold to 10000 (so if there are more ocean monuments in the selected radius than its threshold, then it does not consider the remaining ocean monuments)
- sets the `batchSize` to 500, meaning that we partition the whole list from `candidates.csv` into batches of 500 coordinates, then analyze each batch and save into `results_part_*.csv`, where * represents the batch index.
- does not export Xaero's waypoints because `-Dsponge.xaeroExport` is set to 0 by default.

Another example:

```bash
./gradlew -Dsponge.seed=-2381971292186592288 -Dsponge.radiusBlocks=20000 -Dsponge.excludeRadiusBlocks=5000 -Dsponge.maxResults=1000 -Dsponge.batchSize=1000 -Dsponge.xaeroExport=1 -Dsponge.xaeroMinRooms=3 -Dsponge.xaeroDims=nether -Dsponge.xaeroColor=13 runAll
```

This command:
- sets seed to -2381971292186592288
- searches the square with endpoints (-20k, -20k), (-20k, 20k), (20k, -20k), and (20k, 20k).
- excludes the region square with endpoints (-5k, -5k), (-5k, 5k), (5k, -5k), and (5k, 5k).
- sets the ocean monument threshold to 1000 (so if there are more ocean monuments in the selected radius than its threshold, then it does not consider the remaining ocean monuments)
- sets the `batchSize` to 1000, meaning that we partition the whole list from `candidates.csv` into batches of 1000 coordinates, then analyze each batch and save into `results_part_*.csv`, where * represents the batch index.
- allows exporting Xaero's waypoints.
- sets minimum number of sponge rooms to 3, meaning that any ocean monuments with 2 sponge rooms or less will not be added to the waypoints.
- records Nether coordinates
- sets the waypoints' color to light purple

**Note:** You will notice that `candidates.csv` and `results_part_*.csv` are produced during the run. **Do not delete them during the run: they are needed so that once all candidates from `candidates.csv` are verified, the tool merges `results_part_*.csv` into a `result.csv`, then automatically deletes `candidates.csv` and all `results_part_*.csv`.**

### Notes on Batching and Heap Usage

- Processing large numbers of monuments at once can cause heap memory errors.
- Adjust `batchSize` to a value suitable for your system's RAM.
- Smaller batches reduce memory footprint but increase total runtime. *I highly recommend fine tuning the batch size.*

### Ring-Based Searches (excludeRadiusBlocks)

For very large searches (e.g. 100k–1M blocks), scanning the entire square at once is inefficient.
You can exclude an inner square region and effectively scan a **ring** instead.

Example:
```bash
./gradlew -Dsponge.seed=12345 \
          -Dsponge.radiusBlocks=200000 \
          -Dsponge.excludeRadiusBlocks=100000 \
          runAll
```

This scans only monuments between 100k and 200k blocks from the origin.
This approach:
- Reduces runtime per pass
- Improves cache locality
- Avoids unnecessary re-scanning

---

## Output

The analysis results are written to:

```
results.csv
```

Each line contains:

```
x,z,inferred_sponge_rooms
```

Example:

```
5664,-3904,8
```

Results are sorted by descending sponge room count, then by ascending distance from the origin.

**Tip:** I highly recommend saving the `results.csv` into a different folder (preferably outside of the root folder) or renaming it because if you rerun, it will overwrite it.

In the terminal, you will see the sponge room distribution and estimated total wet sponges, like this:

```
[SpongeMonument] ===== Sponge room distribution =====
[SpongeMonument] 10 : 1
[SpongeMonument] 8 : 8
[SpongeMonument] 7 : 40
[SpongeMonument] 6 : 187
[SpongeMonument] 5 : 846
[SpongeMonument] 4 : 2933
[SpongeMonument] 3 : 7911
[SpongeMonument] 2 : 15660
[SpongeMonument] 1 : 20475
[SpongeMonument] 0 : 14031
[SpongeMonument] Estimated total wet sponges from sponge rooms is (rooms * count * 30): 2788980
[SpongeMonument] If you taken account for killing 3 elder guardians in an ocean monument, this gives exactly 186276 wet sponges.
[SpongeMonument] Altogether, you get approximately 2975256 wet sponges.
```

---

## Biome-Filter False Positives

Due to biome filtering limitations, approximately **0.2%** of candidate coordinates may be false positives (i.e., not actual ocean monuments). This is a minor caveat and does not significantly affect overall analysis accuracy.

---

## Xaero’s Minimap Waypoint Export

SpongeAnalyzer can optionally export results as **Xaero’s Minimap / World Map waypoints**.

This is useful if you want in-game navigation to high-value monuments.

### Export Options

- Export scope:
  - `overworld` → writes `overworld_waypoints.txt` (or overwrites if it exists)
  - `nether` → writes `nether_waypoints.txt` (or overwrites if it exists)
  - `both` → writes both files (or overwrites if both exist)

- Minimum sponge rooms:
  - Use `-Dsponge.xaeroMinRooms` to export only monuments with at least N sponge rooms
  - Example: `4` is recommended if you only want efficient sponge farms

### Coordinate Details

- Overworld waypoints use **Y = 63**
- Nether waypoints use **Y = 128** (recommended for travel above the nether roof)
- Nether coordinates are automatically scaled by ÷8
- Waypoint labels are the **number of sponge rooms**, colored **aqua** to match ocean monuments

Example:
```bash
./gradlew -Dsponge.seed=-1789333 \
          -Dsponge.radiusBlocks=10000 \
          -Dsponge.xaeroExport=both \
          -Dsponge.xaeroMinRooms=4 \
          runAll
```

After the run completes, copy the generated waypoint files into:
```
.minecraft/XaeroWaypoints/
```
or
```
.minecraft/xaero/minimap/<your world>/dim%<-1|0>
```
(or the equivalent directory for your launcher/modpack).

---

## Notable Discovery

SpongeAnalyzer identified a **10-sponge-room monument**, exceeding commonly believed limits.

- **Minecraft Version:** 1.21.11  
- **Seed:** `-916397000043815854`  
- **Coordinates:** `x = -68432, z = -71968`

![An ocean monument with 10 sponge rooms, with wet sponges highlighted in cyan via Advanced xray mod.](images/10_Sponge_Rooms.png)

This confirms that high-sponge monuments exist and can be systematically located.

---

## FAQs and Troubleshooting

1. **Does this work on Bedrock Edition:** Unfortunately, no. The code that identifies ocean monuments is completely different from Java.
2. **Why not just use Chunkbase:** Chunkbase locates monuments but does **not** analyze internal structure layouts or sponge rooms.

3. **Why not scan blocks for wet sponges:** Block scanning is extremely slow:
    * Requires loading large volumes of chunks
    * Requires iterating tens of thousands of blocks
    * Causes long shutdown times due to world saving

    Structure-based inference is significantly faster.

4. **Why does the server fast-exit instead of shutting down cleanly:**
Saving thousands of generated chunks can take **longer than the analysis itself**. The analysis world is disposable, so SpongeAnalyzer intentionally skips saving.

5. **Does this modify my real worlds:** No. SpongeAnalyzer uses a **temporary development world only**.

6. **I am getting the `Failed to load eula.txt` error:** Head to `run/eula.txt`. Replace `eula=false` to `eula=true`. Save and rerun the command.

7. **Why is `runServer` no longer used:** The previous `runServer` command is deprecated for normal users. `runAll` provides a streamlined, fully automated analysis workflow. Also, `runServer` command does not work if you increase the `-Dsponge.maxResults` and `-Dsponge.radiusBlocks` threshold due to the heap error. `runServer` should only be used for development or debugging. End users should always use `runAll`.

8. **Can this tool run without internet:** yes. Even loading the Fabric server can be done offline.

---

## Technical Notes

- Ocean monuments always generate **33 structure pieces**. Each structure piece, if available, is connected to another structure piece, forming a graph.
- Sponge rooms are *not* random blocks.
- Sponge rooms are exclusively represented by:
  ```
  OceanMonumentGenerator$SimpleRoomTop
  ```
- Counting these pieces yields exact sponge room counts.
- This behavior is deterministic for a given seed and version.

---

## Long-Term Goals & Version Support

### Fully Offline Analyzer Mode

Building upon offline analysis capabilities, I aim to develop a standalone Java main program that accepts a world seed and Minecraft version as input parameters and outputs a `results.csv` file containing inferred sponge room counts for all detected monuments. This mode would eliminate the need to start a Fabric server, removing overhead from server startup and shutdown while improving usability for batch processing or integration into automated pipelines. This fully offline mode will facilitate rapid, large-scale sponge room inference on any supported version.


### Performance and Scalability Goals

To handle tens of thousands of monument candidates efficiently, SpongeAnalyzer aims to leverage parallel, math-only computation that bypasses any chunk or world IO entirely. By avoiding expensive world loading and block scanning, the tool can scale to analyze vast areas or multiple seeds quickly. This performance-driven approach draws inspiration from the Slimefinder project, which demonstrated the power of pure math-based structure location analysis. Achieving these goals will establish SpongeAnalyzer as a fast, scalable, and practical tool for the Minecraft community.