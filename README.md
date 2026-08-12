# BetterStructures Performance / Albion

An **AlbionMC.com-only performance and maintenance fork** of BetterStructures, based on upstream **BetterStructures 2.6.3**.

## Homage and upstream credit

BetterStructures was created by **MagmaGuy**. The original concept, structure system, content format, and the overwhelming majority of this codebase are his work and the work of upstream contributors. This fork exists because AlbionMC depends heavily on BetterStructures and needs a performance profile tailored to a large, plugin-heavy survival server and frequently regenerated resource worlds.

This project is **not intended to replace, rebrand, or compete with the original BetterStructures project**. Credit for the plugin belongs with MagmaGuy and the upstream contributors. The original GPLv3 license is retained.

## Albion 1.1.1 — FAWE Native

**FastAsyncWorldEdit is the required world-edit engine for this fork.** Standard WorldEdit is not a supported runtime backend.

FAWE intentionally implements the WorldEdit API, so source imports such as `com.sk89q.worldedit.*` remain normal and expected. Those packages are the API surface used by FAWE; Albion builds require FastAsyncWorldEdit at runtime.

The 1.1.1 goal is simple: **BetterStructures itself does not perform direct Bukkit/NMS block mutations.** Structure, module, cleanup, marker, and debug block writes are routed through FAWE.

### FAWE-native block paths

- natural surface, underground, sky, liquid, and other schematic structures;
- schematic air carving and NBT-rich `BaseBlock` placement;
- BetterStructures bedrock/pedestal filler behavior;
- pedestal construction below natural structures;
- tree/foliage cleanup above surface structures;
- vanilla, EliteMobs, and MythicMobs marker-block removal;
- modular/WFC dungeon batches;
- module directional/light blocks and NBT blocks without a Bukkit slow path;
- modular chest/barrel block placement;
- WFC debug lattice block placement;
- component/elevator schematic pastes.

CI enforces this rule by scanning production Java sources and failing if direct Bukkit `setType`, `setBlockData`, or the old native-palette block-write path is reintroduced.

## Resource-world performance design

This fork is maintained specifically for **AlbionMC.com** with these goals:

- preserve the gameplay, structures, content packages, and visual identity of BetterStructures 2.6.3;
- reduce MSPT/TPS spikes while players explore and generate new chunks, especially in resource worlds;
- queue structure-generation work instead of allowing bursts of expensive fitting work during new-chunk events;
- pause ordinary player-driven structure generation when server MSPT/TPS indicates the main thread is under pressure;
- serialize heavy BetterStructures FAWE edits through one global edit lane instead of allowing several large structures or dungeon batches to compete at once;
- prepare required natural-structure chunks through Paper's async chunk API in small batches;
- keep structure chunk tickets until the FAWE structure and FAWE cleanup phases are complete;
- prevent BetterStructures' own internal chunk loads from recursively generating more BetterStructures structures;
- keep Bukkit primary-thread work for APIs that require it, such as terrain sampling reads, inventory/loot handling, plugin events, and entity spawning;
- target **Paper 1.21.11 and newer only**. Older Minecraft/Paper compatibility is intentionally out of scope.

## Performance defaults

Player-driven structure generation is guarded by conservative load thresholds for new configs:

- pause around **42 MSPT** or **18.5 TPS**;
- resume after recovery around **32 MSPT** and **19.5 TPS**;
- admit expensive fit jobs separately instead of allowing a burst from fast resource-world exploration;
- load structure chunks in small batches before starting the FAWE edit;
- allow only one heavy BetterStructures FAWE edit at a time.

Existing configuration values are preserved when upgrading. These defaults are intended as a safe starting point and can be tuned from real AlbionMC spark profiles after runtime testing.

## Requirements

- Paper **1.21.11+**
- Java **21+**
- FastAsyncWorldEdit **2.14.3+**
  - For newer Minecraft versions, use a FAWE release that explicitly supports that server version.

Do **not** install a separate WorldEdit JAR alongside FAWE for this fork. FastAsyncWorldEdit supplies the WorldEdit API/runtime provider BetterStructures uses.

## Fork versioning

Albion fork releases use their own version line beginning at **1.1.0**. The current FAWE-native milestone is **1.1.1**. The upstream source baseline remains BetterStructures **2.6.3**.

Release JARs are named:

```text
BetterStructures-1.1.x.jar
```

The 1.1.1 test build is:

```text
BetterStructures-1.1.1.jar
```

## Building

```bash
./gradlew clean test shadowJar
```

The shaded plugin JAR is written to:

```text
build/libs/BetterStructures-1.1.x.jar
```

GitHub branch test builds publish the JAR directly as a prerelease asset. Tagged releases (`v1.1.x`) also publish the JAR directly as a **raw release asset**, rather than requiring server owners to download an Actions ZIP.

## Upstream updates

The upstream BetterStructures self-update path is intentionally disabled in this fork so an Albion performance build cannot be silently replaced by an upstream plugin JAR. Upstream changes can still be reviewed and selectively merged into this fork.

## License

GPLv3, inherited from BetterStructures. See [LICENSE](LICENSE).
