# FoCoinflip

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/focoinflip) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/focoinflip/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

Vault-backed player coinflips with animated results and persistent statistics.

*Developed by Carrotio.*

## Features

- Provides Vault-backed player coinflips through a browsable GUI.
- Supports creating, joining, and removing coinflips with animated results.
- Tracks won, lost, and total money statistics.
- Provides configurable limits, formatting, messages, sounds, and administration.
- Exposes a result event and PlaceholderAPI statistics for integrations.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/focoinflip). Check the [versions page](https://modrinth.com/plugin/focoinflip/versions) for supported Minecraft versions and server platforms.

- **Required:** `Vault`
- **Optional integrations:** `PlaceholderAPI`, `SkinsRestorer`

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/focoinflip [create <amount>|delete]` — Open or manage coinflips Aliases: `/coinflip`, `/cf`.
- `/focoinflipadmin <version|reload|editor>` — Admin tools for FoCoinflip Aliases: `/coinflipadmin`, `/cfadmin`.

### Permissions

- `focoinflip.use` — Allows using coinflip commands Default: `true`.
- `focoinflip.admin` — Allows using coinflip admin commands Default: `op`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

Requires PlaceholderAPI.

- `%focoinflip_total_won% / %focoinflip_total_won_raw%` — Raw money won.
- `%focoinflip_total_won_formatted%` — Formatted money won.
- `%focoinflip_total_lost% / %focoinflip_total_lost_raw%` — Raw money lost.
- `%focoinflip_total_lost_formatted%` — Formatted money lost.
- `%focoinflip_total_sum% / %focoinflip_total_sum_raw%` — Raw total amount wagered.
- `%focoinflip_total_sum_formatted%` — Formatted total amount wagered.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.
- `guis/*.yml` — GUI layouts, slots, labels, and lore.

Main config areas: `file-logging`, `settings`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

`CoinflipResolvedEvent` fires synchronously after payout and statistics recording. It exposes the resolution and whether the source was in-game or external.

~~~java
@EventHandler
public void onCoinflip(CoinflipResolvedEvent event) {
    CoinflipResolvedEvent.Source source = event.getSource();
}
~~~

</details>
