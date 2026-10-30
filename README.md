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

FoCoinflip provides `me.foesio.foCoinflip.api.CoinflipApi` through Bukkit's
`ServicesManager`. A consumer compiles against the **matching FoCoinflip jar**
as a provided dependency (`compileOnly` in Gradle); do not bundle FoCoinflip's
classes into the consumer. Install a local jar into Maven first if needed:
`mvn install:install-file -Dfile=FoCoinflip-2.5.jar -DgroupId=me.foesio -DartifactId=FoCoinflip -Dversion=2.5 -Dpackaging=jar`.
Those coordinates describe a locally installed jar, not a published API repository.
In the consumer's `plugin.yml`, add `softdepend: [FoCoinflip]` (or `depend`
when required).

~~~java
if (Bukkit.getPluginManager().isPluginEnabled("FoCoinflip")) {
    RegisteredServiceProvider<CoinflipApi> registration =
            Bukkit.getServicesManager().getRegistration(CoinflipApi.class);
    if (registration != null) {
        CoinflipApi api = registration.getProvider();
        List<CoinflipSnapshot> open = api.openFlips();
        CoinflipApi.CreateOutcome paid = api.createForPlayer(player, 100);
        CoinflipApi.CreateOutcome npc = api.createForNpc("Dealer", 100);
    }
}
~~~

Keep direct FoCoinflip class references in an integration class loaded only
after the provider is present. Reacquire the service after FoCoinflip is re-enabled;
it is unregistered on disable. Calling a retained service while disabled throws
`IllegalStateException`. API calls must run on the primary server thread
or, on Folia, the global tick thread; off-thread calls throw `IllegalStateException`.
FoCoinflip does not move calls to another scheduler. API results report validation failures,
event cancellation, unavailable flips and Vault errors. A successful join has
settled and paid any real-player winner; no animation runs for external joins. Open flips exist
only in memory. Paid player flips are refunded on creator quit or shutdown.

`createForPlayer(OfflinePlayer, stake)` immediately withdraws the selected
player's stake from their Vault account, even when that player did not issue a
command. This is a trusted plugin API: the calling plugin must decide who may
trigger it. The existing `create(UUID, name, stake)` has the same paid-player
behavior for account-linked integrations. Both paths apply the configured bet
limits, one-open-flip rule, and pre-create event.

`createForNpc(name, stake)` opens a server-funded flip for a virtual NPC. Names
are trimmed, case-insensitive for identity, and must be 1–32 characters using
letters, digits, spaces, `_`, `.`, or `-`. The returned snapshot has
`virtualCreator() == true`; its `creatorId()` is a stable virtual UUID, not a
player account. The NPC has no Vault account or stats row. When a player joins,
their stake is withdrawn as usual. A player win pays the normal after-tax pot
from the server economy; an NPC win removes the player's stake from circulation.
The resolution's `payout()` remains the calculated after-tax prize when the
NPC wins, but no Vault account receives it.
Cancelling or shutting down an NPC flip needs no refund; the closed event reports
`isRefundSucceeded() == true` for that no-refund case, with `NPC_CANCEL` as
the cancellation reason. Use `cancel(npc.flip().creatorId())`
to remove an open NPC flip. One NPC name can have one open flip at a time.

`openFlips()`, `openFlip(creatorId)` and `stats(playerId)` return snapshots.
Stats are current cached **net** won, lost and difference. A missing player's
stats are zero. SQLite writes happen asynchronously after a result.

Events live in `me.foesio.foCoinflip.api`:

- `CoinflipPreCreateEvent`: cancellable after bet validation, before any Vault transfer. `getSource()` identifies in-game or external requests; `isVirtualCreator()` identifies NPC flips.
- `CoinflipOpenedEvent`: after stake withdrawal and insertion into the open list.
- `CoinflipPreJoinEvent`: cancellable after a flip is claimed, before joiner withdrawal. It also exposes the request source.
- `CoinflipResolvedEvent`: after payout and cached stats update; before the in-game animation or announcement. Use `getSnapshot()` for immutable values; `getResolution()` remains for existing consumers. `getSource()` identifies `IN_GAME` or `EXTERNAL`. `suppressCoreAnnouncement()` affects only the in-game broadcast.
- `CoinflipClosedEvent`: after an open flip is removed due to cancellation, creator quit, shutdown or failed settlement. Its reason and `isRefundSucceeded()` report what happened; failed refunds still close the flip.

Events are emitted on the mutation's calling thread; `isAsynchronous()` reflects
whether Bukkit considers that thread primary. A listener on a region or async
thread must schedule access to unrelated player/world state appropriately.
Cancelled pre events leave the flip and balances unchanged. A resolved event
means cached stats changed, not that their SQLite write has completed.

~~~java
@EventHandler
public void onCoinflip(CoinflipResolvedEvent event) {
    CoinflipResolutionSnapshot result = event.getSnapshot();
    // Publish result.winnerId() and result.payout() to an external service.
}
~~~

</details>
