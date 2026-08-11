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
- spread schematic preparation across ticks before the existing distributed paste workload begins;
- target **Paper 1.21.11 and newer only**. Older Minecraft/Paper compatibility is intentionally out of scope.

## Requirements

- Paper **1.21.11+**
- Java **21+**
- FastAsyncWorldEdit **2.14.3+**
  - For Minecraft 26.x, use a FAWE release that explicitly supports that server version.

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
