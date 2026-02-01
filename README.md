# SpongeAnalyzer

SpongeAnalyzer is a **Fabric-based Minecraft analysis tool** that locates ocean monuments **and infers the number of sponge rooms inside each monument**. This tool avoids scanning blocks in the overworld.
Current existing tools such as Chunkbase locate ocean monuments and report monument coordinates, but unlike them, SpongeAnalyzer answers a more practical question:

> **Is this monument worth raiding?**

Sponge rooms are the primary source of wet sponges in survival gameplay. Sponges are essential for draining large bodies of water, building trenches, and constructing farms (e.g., squid farms). Sponges are faster to drain water than placing gravity blocks to clear it, so the only way to get them is to raid the ocean monuments. However, **ocean monuments do not guarantee sponge rooms**.

From empirical analysis of thousands of monuments:
- Out of 10000 monuments, **2266 contained zero sponge rooms**, which is roughly **22.66% of monuments with no sponge rooms at all**
- This means roughly 93.72% monuments contain **0–3 sponge rooms**
- Currently, SpongeAnalyzer identified a monument with **8 sponge rooms**, exceeding commonly assumed limits (some people asserted the max is 7, but now it is changed). 

Before this tool exists, the expected number of sponge rooms is ~1.5. Since there is ~30 wet sponges in every sponge room, this means you end up getting an average of 45 wet sponges every time you raided an ocean monument. This means you had no way of knowing whether the monument has sponge rooms, and you could get unlucky, for example, for raiding 10 ocean monuments but has no sponge rooms.

---

## Why Sponge Rooms Matter

- Elder Guardians only drop **one wet sponge each**
- Looting does **not** increase sponge drops
- Large-scale water removal requires **dozens or hundreds** of sponges
- Raiding monuments without sponge rooms is often a waste of time

SpongeAnalyzer helps players and technical users **pre-filter monuments** and focus only on high-value targets.

---

## Key Technical Discovery for counting Sponge Rooms (Core Innovation)

### Naive Approach (Too Slow)

I wanted to scan a 58x58 block area from Y-levels 40 to 63 and then count `WET_SPONGE` blocks using `getBlockState()`, but the problem with this approach is that it is computationally expensive and required loading many chunks.

### Structure-based Insight (Fast & Efficient)

Through debugging and inspection of Minecraft's structure data, I finally figured which structure is responsible for generating sponge rooms.

Ocean monuments are generated using a fixed internal structure layout composed of **33 structure pieces**. Not all pieces are available. Each available piece is a vertex, and there is an edge (an opening) that connects to another piece, effectively creating a maze.

While community documentation (e.g.  
https://minecraft.fandom.com/wiki/Ocean_Monument/Structure) describes monument size and chambers, it does **not** document internal room *types*.

### Reverse-Engineered Insight

Through structure introspection and debugging, the following was discovered:

> **Every sponge room corresponds to a structure piece of type**  
> `OceanMonumentGenerator$SimpleRoomTop`

Therefore:
- The number of sponge rooms in a monument  
  = the number of `SimpleRoomTop` pieces in its structure layout
- No block scanning is required
- No wet sponge counting is required
- No world traversal is required beyond structure generation

This discovery enables **fast, deterministic sponge room inference** directly from structure metadata.

---

## How SpongeAnalyzer Works

1. **Start a disposable Fabric server** (development-only)
2. **Locate ocean monuments** within a configurable radius
3. **Extract structure layout data** for each monument
4. **Count `SimpleRoomTop` pieces**
5. **Output inferred sponge room counts**
6. **Fast-exit the JVM without saving the world**

World saving is intentionally skipped to avoid multi-minute shutdown delays when thousands of chunks are loaded.

---

## Requirements

- **Java:** 21
- **Minecraft:** 1.21.11
- **Fabric Loader:** 0.18.4
- **Fabric API:** 0.139.5+1.21.11
- **Fabric Loom:** 1.15.0-alpha.6
- **OS:** macOS / Linux / Windows (tested primarily on macOS)

### Java 21 and Gradle configuration

SpongeAnalyzer requires Java 21 to run correctly. To ensure Gradle uses Java 21, follow these steps:

1. **Install Java 21** on your system. You can download it from [Adoptium](https://adoptium.net/) or your preferred JDK provider.

2. **Configure Gradle to use Java 21** by setting the `org.gradle.java.home` property. You can do this in one of the following ways:

   - **Option A: Set in `gradle.properties`**

     Create or edit the `gradle.properties` file in the project root and add:

     ```
     org.gradle.java.home=/path/to/java21
     ```

     Replace `/path/to/java21` with the absolute path to your Java 21 installation directory.

   - **Option B: Set as an environment variable**

     Export the `JAVA_HOME` environment variable pointing to Java 21 before running Gradle:

     ```bash
     export JAVA_HOME=/path/to/java21
     ./gradlew ...
     ```

3. **Verify Gradle is using Java 21** by running:

   ```bash
   ./gradlew -version
   ```

   The output should indicate Java 21 as the JVM version.

Ensuring Gradle uses Java 21 avoids compatibility issues and guarantees SpongeAnalyzer runs as intended.

---

## Installation

You must install **Git** manually before running SpongeAnalyzer. If this tool is missing, your terminal may report errors such as `command not found` or fail during the native build step.

**Git**

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

---

## Running the Analyzer

SpongeAnalyzer is executed via Gradle using JVM flags.

### Basic Usage

```bash
./gradlew -Dsponge.seed=<WORLD_SEED> runServer
```

Example:

```bash
./gradlew -Dsponge.seed=-2727269088507749507 runServer
```

### Configuring Number of Monuments to Search

The number of monuments analyzed can be configured at runtime with the `-Dsponge.maxResults` flag. By default, the tool analyzes up to 100 monuments, but this can be increased to scan more monuments as needed.

You can also control the search radius and server shutdown behavior using additional flags.

Example:

```bash
./gradlew -Dsponge.seed=-2727269088507749507 -Dsponge.maxResults=1000 runServer
```

### Common Optional Flags

| Flag | Description |
|----|------------|
| `-Dsponge.seed` | **Required.** World seed to analyze |
| `-Dsponge.port` | Server port (default: 25565) |
| `-Dsponge.cleanWorldOnStart=true` | Deletes dev world before run |
| `-Dsponge.maxResults` | Maximum number of monuments to analyze (default: 100) |
| `-Dsponge.radiusBlocks` | Search radius in blocks around the center position (default: 200000) |
| `-Dsponge.stopServerAfter` | Whether the server should stop after analysis (`true` or `false`, default: `false`) |
| `-Dsponge.logSpongeRoomsOnly` | `1` = log only monuments with sponge rooms (default), `0` = log all monuments |

The `radiusBlocks` flag controls *how far* from the world center (0,0) monuments are searched, while `maxResults` controls *how many* monuments are analyzed. These two limits are independent; analysis will stop when either limit is reached, so both may constrain the results.

### Output

Results are written to:

```
results.csv
```

Format:
```
x,z,inferred_sponge_rooms
```

Example:
```
5664,-3904,8
```

---

## Notable Discovery

SpongeAnalyzer identified an **8-sponge-room monument**, exceeding commonly believed limits.

- **Minecraft Version:** 1.21.11  
- **Seed:** `53205569250527877`  
- **Coordinates:** `x = 5664, z = -3904`

![An ocean monument with 8 sponge rooms, with wet sponges highlighted in cyan via Advanced xray mod.](images/8_Sponge_Rooms.png)

This confirms that high-sponge monuments exist and can be systematically located.

---

## FAQs and Troubleshooting

1. **Does this work on Bedrock Edition:** Unfortunately, no. The code that identifies ocean monuments is completely different from Java.
2. **Why not just use Chunkbase:** Chunkbase locates monuments but does **not** analyze internal structure layouts or sponge rooms.

3. **Why not scan blocks for wet sponges:** Block scanning is extremely slow:
    * Requires loading large volumes of chunks
    * Requires iterating tens of thousands of blocks
    * Causes long shutdown times due to world saving

    Structure-based inference is orders of magnitude faster.

4. **Why does the server fast-exit instead of shutting down cleanly:**
Saving thousands of generated chunks can take **longer than the analysis itself**. The analysis world is disposable, so SpongeAnalyzer intentionally skips saving.

5. **Does this modify my real worlds:** No. SpongeAnalyzer uses a **temporary development world only**.

6. **I am getting the `Failed to load eula.txt` error:** Head to `run/eula.txt`. Replace `eula=false` to `eula=true`. Save and rerun the command.

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

Building upon offline analysis capabilities, we aim to develop a standalone Java main program that accepts a world seed and Minecraft version as input parameters and outputs a `results.csv` file containing inferred sponge room counts for all detected monuments. This mode would eliminate the need to start a Fabric server, removing overhead from server startup and shutdown while improving usability for batch processing or integration into automated pipelines. This fully offline mode will facilitate rapid, large-scale sponge room inference on any supported version.

### Version Abstraction and Multi-Version Support

Minecraft’s structure generation rules and internal constants can vary between versions. To maintain compatibility and extend SpongeAnalyzer’s utility, we plan to isolate version-specific constants, structure layouts, and placement rules behind an abstraction layer. This design will allow easy addition of support for multiple Minecraft versions by encapsulating differences in structure piece types, generation parameters, and RNG behavior. Multi-version support ensures SpongeAnalyzer remains relevant and useful across Minecraft’s evolving landscape.

### Performance and Scalability Goals

To handle tens of thousands of monument candidates efficiently, SpongeAnalyzer aims to leverage parallel, math-only computation that bypasses any chunk or world IO entirely. By avoiding expensive world loading and block scanning, the tool can scale to analyze vast areas or multiple seeds quickly. This performance-driven approach draws inspiration from the Slimefinder project, which demonstrated the power of pure math-based structure location analysis. Achieving these goals will establish SpongeAnalyzer as a fast, scalable, and practical tool for the Minecraft community.