# BetterStructures Performance / Albion

An **AlbionMC.com-only performance and maintenance fork** of BetterStructures, based on upstream **BetterStructures 2.6.3**.

## Homage and upstream credit

BetterStructures was created by **MagmaGuy**. The original concept, structure system, content format, and the overwhelming majority of this codebase are his work and the work of upstream contributors. This fork exists because AlbionMC depends heavily on BetterStructures and needs a performance profile tailored to a large, plugin-heavy survival server and frequently regenerated resource worlds.

This project is **not intended to replace, rebrand, or compete with the original BetterStructures project**. Credit for the plugin belongs with MagmaGuy and the upstream contributors. The original GPLv3 license is retained.

## Purpose of this fork

This branch is maintained specifically for **AlbionMC.com** with these goals:

- preserve the gameplay, structures, content packages, and visual identity of BetterStructures 2.6.3;
- reduce MSPT/TPS spikes while players explore and generate new chunks, especially in resource worlds;
- use **FastAsyncWorldEdit (FAWE)** as the required WorldEdit backend;
- queue structure-generation work instead of allowing bursts of expensive fitting work during new-chunk events;
- pause ordinary player-driven structure generation when server MSPT/TPS indicates the main thread is under pressure;
- serialize the expensive fit/load/paste path so several large structures do not compete at once;
- prepare required structure chunks through Paper's async chunk API in small batches;
- perform the normal structure block loop in an asynchronous FAWE `EditSession`, while returning Bukkit-only preparation and completion work to the main thread;
- prevent BetterStructures' own internal chunk loads from recursively generating more BetterStructures structures;
- target **Paper 1.21.11 and newer only**. Older Minecraft/Paper compatibility is intentionally out of scope.

## Performance defaults

Player-driven structure generation is guarded by conservative load thresholds for new configs:

- pause around **42 MSPT** or **18.5 TPS**;
- resume after recovery around **32 MSPT** and **19.5 TPS**;
- admit expensive fit jobs separately instead of allowing a burst from fast resource-world exploration;
- load structure chunks in small batches before starting the FAWE edit.

Existing configuration values are preserved when upgrading. These defaults are intended as a safe starting point and can be tuned from real AlbionMC spark profiles after runtime testing.

## Requirements

- Paper **1.21.11+**
- Java **21+**
- FastAsyncWorldEdit **2.14.3+**
  - For newer Minecraft versions, use a FAWE release that explicitly supports that server version.

FastAsyncWorldEdit provides the WorldEdit API used by BetterStructures and is intentionally a hard dependency in this fork.

## Fork versioning

Albion fork releases use their own version line beginning at **1.1.0**. The upstream source baseline remains BetterStructures **2.6.3**.

Release JARs are named:

```text
BetterStructures-1.1.x.jar
```

## Building

```bash
./gradlew clean test shadowJar
```

The shaded plugin JAR is written to:

```text
build/libs/BetterStructures-1.1.x.jar
```

GitHub tagged releases (`v1.1.x`) publish that JAR directly as a **raw release asset**, rather than requiring server owners to download an Actions ZIP.

## Upstream updates

The upstream BetterStructures self-update path is intentionally disabled in this fork so an Albion performance build cannot be silently replaced by an upstream plugin JAR. Upstream changes can still be reviewed and selectively merged into this fork.

## License

GPLv3, inherited from BetterStructures. See [LICENSE](LICENSE).
