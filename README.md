<div align="center">

# 🏰 BetterStructures — Paper Focused Fork

### Beautiful structures, dungeons, ruins, and discoveries throughout your Minecraft world.

[![Original Project](https://img.shields.io/badge/Original-MagmaGuy%2FBetterStructures-orange?style=for-the-badge)](https://github.com/MagmaGuy/BetterStructures)
[![Paper](https://img.shields.io/badge/Focused%20Platform-Paper-blue?style=for-the-badge)](https://papermc.io/)
[![License](https://img.shields.io/badge/License-GPLv3-green?style=for-the-badge)](LICENSE)

**This is an unofficial maintenance fork of BetterStructures created for a more focused modern Paper server environment.**

**Maintained specifically for [AlbionMC.com](https://albionmc.com/) while preserving the identity and work of the original BetterStructures project.**

</div>

---

## ❤️ Homage to MagmaGuy

**BetterStructures was created by MagmaGuy.**

The concept, architecture, gameplay experience, structure-placement system, integrations, commands, configuration system, and years of development behind BetterStructures belong to the original project and its contributors.

This fork exists because BetterStructures is an excellent plugin worth preserving and continuing to use on modern servers. It is not an attempt to replace, rebrand, or take credit for MagmaGuy's work.

If you enjoy BetterStructures, **please support the original developer first**. Continued support helps MagmaGuy maintain BetterStructures and create other excellent Minecraft projects.

### Support MagmaGuy

- ❤️ **Patreon:** https://www.patreon.com/magmaguy
- 🎮 **MagmaGuy on itch.io:** https://magmaguy.itch.io/
- 🌐 **Website:** https://magmaguy.com/
- 💻 **GitHub:** https://github.com/MagmaGuy
- 🏰 **Original BetterStructures repository:** https://github.com/MagmaGuy/BetterStructures
- 📦 **Official BetterStructures Spigot page:** https://www.spigotmc.org/resources/betterstructures.103241/
- 📖 **Official BetterStructures Wiki:** https://github.com/MagmaGuy/BetterStructures/wiki

**Please consider downloading the official releases and structure packs from MagmaGuy's pages and supporting the original project whenever possible.**

---

## 🏞️ What is BetterStructures?

BetterStructures brings naturally generated custom structures into Minecraft worlds without requiring a custom world generator.

Depending on the installed structure packs and configuration, it can add things such as:

- 🏰 castles, ruins, towers, houses, temples, and landmarks
- 🕳️ underground structures and caves
- ⚔️ adventure locations and modular dungeons
- 💰 randomized treasure and loot containers
- 👹 structures populated with mobs
- 🌲 biome- and terrain-aware structure placement
- 🗺️ custom server-made schematics and structure packs
- 🧩 integrations with other server plugins and content systems

BetterStructures analyzes terrain and chooses appropriate placement locations, allowing structures to blend into many different world-generation environments instead of requiring a specific terrain generator.

---

## 📄 About this fork

This repository is a **downstream, unofficial maintenance fork** of **MagmaGuy/BetterStructures**. The current branch incorporates the upstream **BetterStructures 2.7.0** codebase and then layers focused Paper 26.2 compatibility, build, stability, and optional integration work on top.

This fork is maintained specifically for **AlbionMC.com**. Fork-specific changes should remain focused on compatibility, performance, and integrations useful to that environment rather than changing BetterStructures into a different project.

### Fork goals

- Keep the original BetterStructures gameplay and identity intact.
- Preserve compatibility with existing BetterStructures configurations and structure packs wherever practical.
- Incorporate useful upstream BetterStructures updates rather than freezing an old codebase.
- Maintain support for current Paper server versions used by this branch.
- Focus testing on Paper rather than attempting to support every Bukkit-derived server implementation.
- Apply targeted performance and compatibility improvements without unnecessarily redesigning MagmaGuy's plugin.
- Keep upstream attribution and project history clearly visible.

---

## ⚡ AlbionMC build focus

The current Albion fork is built around:

- **BetterStructures 2.7.0** upstream foundation
- **Paper 26.2** as the primary compile/test baseline
- **Java 25**
- **FastAsyncWorldEdit (FAWE)** as the required WorldEdit implementation and structure-placement backend
- **Purpur** and conventional Paper-compatible forks through Paper API compatibility
- optional integrations remaining optional rather than becoming hard dependencies
- compatibility repair for obsolete pre-26.2 schematic data

### Legacy bed repair

Minecraft 26.2 no longer accepts the old `minecraft:bed` block-entity representation. When an old schematic is loaded, this fork repairs that legacy data in memory before WorldEdit/FAWE parses it:

- obsolete `minecraft:bed` block-entity records are removed;
- obsolete `minecraft:bed` palette entries become `minecraft:red_bed`;
- facing, head/foot, occupied, and other block-state properties are preserved;
- already-valid modern beds such as blue, white, black, or existing red beds are not recolored;
- the original schematic file on disk is not modified.

### Optional Slimefun loot

Slimefun is optional. When installed, treasure configurations can reference registered Slimefun item IDs, including addon items.

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

An opt-in `treasure_slimefun.yml` table is included. It is not forced into existing BetterStructures loot tables.

### Build output

Validated builds produce a directly usable raw JAR named:

`BetterStructures-2.7.0-Albion.jar`

The release asset is a normal `.jar` and does not need to be extracted from a `.jar.zip` archive.

---

## 🧪 Platform focus

This branch is maintained primarily around Paper 26.2, modern Java runtimes supported by that Paper release, production-server stability, and structure-generation performance/compatibility. Other Paper-compatible forks may work, but they are not necessarily the primary development or testing target.

For broad support, official releases, documentation, structure packs, and the original development direction, use MagmaGuy's BetterStructures project.

---

## 🔀 Upstream relationship

**Original project:** https://github.com/MagmaGuy/BetterStructures

This repository should be considered a modified downstream build. Useful upstream BetterStructures fixes should continue to be incorporated, while fork-specific changes should remain clearly identifiable so the original project's work is never obscured.

### Reporting problems

If a problem occurs only while using this fork, report it to **this repository** rather than asking MagmaGuy to support modifications he did not make. If the same problem can be reproduced using the current official BetterStructures release, then the original BetterStructures project is the appropriate place to investigate it.

---

## 🙏 Credits

### Original development

- **MagmaGuy** — creator and primary developer of BetterStructures.
- **BetterStructures contributors** — everyone who contributed code, testing, reports, documentation, translations, integrations, and improvements to the original project.
- **Structure creators and pack contributors** — the builders and creators responsible for the content that makes BetterStructures exploration possible.

### This fork

Maintenance changes in this repository are downstream compatibility and server-environment work only. They do not replace or diminish the authorship of the original BetterStructures project.

**BetterStructures remains MagmaGuy's creation.**

---

## 📜 License

BetterStructures is distributed under the **GNU General Public License v3.0 (GPLv3)**, and this fork remains under that same license. See the included [`LICENSE`](LICENSE) file for the complete license terms.

This software is provided **without warranty**, as described by the GNU GPLv3. Nothing in this README removes, replaces, or limits the rights granted by the project's GPLv3 license.

---

## ⚠️ Unofficial project notice

This repository is **not an official BetterStructures release** and is not maintained, endorsed, or supported by MagmaGuy unless he explicitly states otherwise.

BetterStructures, Minecraft, Paper, WorldEdit, FastAsyncWorldEdit, Slimefun, and other referenced project names and trademarks belong to their respective owners.

**NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**

---

<div align="center">

### ❤️ Enjoy BetterStructures? Support the developer who created it.

**[MagmaGuy on Patreon](https://www.patreon.com/magmaguy)** · **[Official BetterStructures](https://github.com/MagmaGuy/BetterStructures)** · **[MagmaGuy on itch.io](https://magmaguy.itch.io/)**

*Preserving a great plugin while keeping credit where it belongs.*

</div>
