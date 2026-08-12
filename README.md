# BetterStructures — Albion Fork

This repository is a maintenance and performance fork of **BetterStructures**, originally created by **MagmaGuy**.

BetterStructures and its original design, structure system, content format, and project foundation exist because of MagmaGuy's work. This fork is maintained with respect for that project and its creator.

## Purpose of this fork

This fork exists specifically for **AlbionMC.com** and is focused on keeping BetterStructures usable on current Minecraft server software while preserving the behavior and content style of the original plugin.

Primary goals:

- Target **Paper 26.2** and current Java runtimes.
- Remain usable on **Purpur** and conventional Paper-compatible server forks.
- Use **FastAsyncWorldEdit (FAWE)** as the structure-placement backend for better large-structure performance.
- Reduce version-specific/NMS coupling where a supported Paper/WorldEdit API path exists.
- Preserve compatibility with existing BetterStructures content packages and configurations where practical.
- Keep optional integrations optional rather than making unrelated plugins hard dependencies.
- Add Albion-specific compatibility improvements where they can be implemented without changing the core identity of BetterStructures.

## FastAsyncWorldEdit

The Albion fork uses **FastAsyncWorldEdit** as its required structure engine. Structure placement uses the WorldEdit API surface supplied by FAWE instead of BetterStructures maintaining a separate version-specific native block-placement path.

## Slimefun loot integration

Slimefun is **optional**. When Slimefun is installed, treasure configurations may reference registered Slimefun item IDs, including items supplied by Slimefun addons.

Example:

```yaml
items:
  rare:
    weight: 25
    items:
      - slimefunItem: DAMASCUS_STEEL_INGOT
        amount: 1-2
        weight: 6
      - slimefunItem: BANDAGE
        amount: 1-2
        weight: 5
```

An opt-in `treasure_slimefun.yml` example table is included with dusts, alloys/ingots, medical supplies, and talismans. Slimefun loot is not forced into existing structure loot tables.

## Builds

The CI pipeline compiles against Paper 26.2 on Java 25, runs the BetterStructures test suite against the official MockBukkit 26.2 codebase, creates the shaded plugin JAR, and verifies the distributable file.

Master builds publish a directly downloadable raw JAR release named:

`BetterStructures-2.6.3-Albion.jar`

The release asset is a normal `.jar` and does not need to be extracted from a `.jar.zip` archive before use.

## Compatibility scope

Primary target:

- Paper 26.2
- Java 25
- FastAsyncWorldEdit

Also targeted through Paper compatibility:

- Purpur
- Other conventional Paper-compatible forks

Optional integrations include Slimefun, EliteMobs, WorldGuard, and supported world-generation plugins already recognized by BetterStructures.

This fork does **not** currently claim Folia-safe scheduling. Folia compatibility should be treated as a separate compatibility pass.

## Upstream

For the original BetterStructures project, releases, documentation, and creator-maintained ecosystem, refer to MagmaGuy's BetterStructures/Nightbreak project.

This Albion fork is not an official MagmaGuy or Nightbreak release.