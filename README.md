# AdvancedKeepInventory

AdvancedKeepInventory is a Spigot/Paper plugin that manages player death penalties with detailed rules: item keep/drop, money loss, EXP loss, PvP rewards, and anti-abuse protection.

## Table of Contents

- [Features](#features)
- [Compatibility](#compatibility)
- [Installation](#installation)
- [Commands and Permissions](#commands-and-permissions)
- [Quick Configuration](#quick-configuration)
- [Build from Source](#build-from-source)
- [Notes](#notes)
- [Support](#support)

## Features

- World-based keep/drop management (`world`, `world_nether`, `world_the_end`, ...).
- Item rules with 3 priority levels:
  - `custom-items` (`MATERIAL:CustomModelData`)
  - `items` (specific Material)
  - `categories` (`Sword`, `Armor`, `Food`, ...)
- `KEEP` has priority over `DROP` when an item matches both.
- Blanket keep by death cause (`PLAYER`, `MOB`, `NATURAL`) + options to keep equipped armor/main hand.
- Economy penalties via `Vault` or `PlayerPoints` (`percent`, `max-loss`, `give-to-killer`).
- Full anti-abuse system:
  - PvP cooldown per killer-victim pair
  - Same-IP blocking
  - Minimum victim balance requirement
  - Rapid death protection (repeated deaths during cooldown skip money/EXP loss)
- EXP loss by percentage and by `apply-on` death causes.
- Flexible message system with MiniMessage + display types:
  - `chat`, `actionbar`, `title`, `bossbar`
- Crash-safe item recovery:
  - Backup saved to `plugins/AdvancedKeepInventory/death-cache/`
  - Automatic item recovery after restart/crash.
- Automatic migration for `config.yml` and `messages.yml` (keeps comments + creates `.bak` backups).

## Compatibility

- Server: Spigot 1.16.5+ (recommended), Paper/Purpur.
- Java: **17+**.
- `plugin.yml`: `api-version: 1.16`.
- Soft-depend:
  - `Vault`
  - `PlayerPoints`

For full compatibility details, see `compatible.md`.

## Installation

1. Build the JAR or use a release JAR.
2. Copy the file to your `plugins/` folder.
3. Restart the server.
4. Edit `config.yml` and `messages.yml` as needed.
5. Reload the plugin to apply configuration changes.

## Commands and Permissions

### Commands

- `/advancedkeepinventory reload`
- Alias: `/aki reload`

### Permissions

- `advancedkeepinventory.admin` - Allows admin command usage (`reload`).
- `advancedkeepinventory.death.keep.bypass` - Always keep inventory on death.
- `advancedkeepinventory.death.money.bypass` - Skip money loss on death.

## Quick Configuration

Main files:

- `src/main/resources/config.yml`
- `src/main/resources/messages.yml`

### Economy example

```yaml
money:
  activate: true
  type: Vault          # Vault or PlayerPoints
  percent: 0.1         # Lose 10%
  max-loss: 10000.0
  give-to-killer: true
```

### EXP example

```yaml
exp:
  activate: true
  percent-loss: 0.5
  apply-on:
    - PLAYER
    - MOB
    - NATURAL
```

### Drop/keep example

```yaml
drop:
  categories:
    - Misc
    - Food
    - Potion
  items: []
  custom-items:
    - "PAPER:1001"

keep:
  categories: []
  items: []
  custom-items: []
```

## Build from Source

Requirements:

- JDK 17+
- Maven 3.8+

```bash
mvn clean package
```

Generated artifact:

- `target/advancedkeepinventory-<version>.jar`