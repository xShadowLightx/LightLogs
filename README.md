![LightLogs banner](assets/lightlogs-logo.svg)

# LightLogs

LightLogs sends selected Paper server and supported plugin events to Discord channels through Discord webhooks. It is designed to stay lightweight: it uses Java's built-in HTTP client, performs webhook requests asynchronously, and has no required runtime plugin dependencies. Optional integrations are detected at runtime.

> **Disclaimer:** LightLogs is not a replacement for or an alternative to DiscordSRV, and it is not associated with DiscordSRV or its developers. LightLogs is a lightweight solution for sending server logs to staff logging channels in Discord.

## Support

For help with LightLogs, join the [Development Hub Discord server](https://discord.gg/FMvSubmg3M).

## Features

- Player chat, commands, joins, leaves, deaths, gamemode changes, and block/entity explosions
- NuVotifier or Votifier vote notifications
- CMI economy balance changes, including CMI when it provides economy through its Vault build or economy injector
- LuckPerms node add/remove notifications, with separate routes for user parent changes and group permission-node changes
- Towny event logs grouped by town/nation, resident, plot, permission, balance, jail, war, rank, and settings contexts
- AuraSkills level-ups, optionally routed by skill ID
- RealisticSeasons season/day changes and special event notifications; weather report events are routed if the installed version exposes them as Bukkit events
- Staff++ command and moderation event logs
- Per-category and integration-specific webhook routing, with a global fallback and an explicit `none` value

Optional plugins are not bundled or required. Install the plugin integrations you want to log separately.

## Requirements and supported builds

This project builds two separate plugin jars:

| Server | Java runtime for server | Gradle task | Output |
| --- | --- | --- | --- |
| Paper 1.21.11 | Java 21 | `gradle build` | `build/libs/LightLogs-V-1.1.1-paper-1.21.11.jar` |
| Paper 26.3 | Java 25 | `gradle paper263Jar` | `build/libs/LightLogs-V-1.1.1-paper-26.3.jar` |

To build both jars, use `gradle buildAll` with JDK 21 and JDK 25 available to Gradle. Gradle 8 or later is required. Install only the jar matching the server in that server's `plugins` folder. The jars share a plugin name, so do not install both in one server.

## Install and configure Discord

1. In Discord, open the destination text channel's **Edit Channel → Integrations → Webhooks** (or **Server Settings → Integrations → Webhooks**).
2. Create a webhook, choose the channel it should post in, and copy its URL. The webhook needs permission to view the channel and send messages.
3. Install the matching LightLogs jar in the Paper server's `plugins` folder and start the server once. LightLogs creates `plugins/LightLogs/config.yml`.
4. Stop the server or edit the config while it is running, then paste each webhook URL into the relevant setting in `plugins/LightLogs/config.yml`.
5. Save the YAML file and run `/lightlogs reload`, or restart the server.
6. Trigger an event (for example, join or leave) to confirm that the webhook posts to the intended channel.

Treat webhook URLs like passwords. Anyone who has a URL can post to its channel. Avoid posting your config file publicly.

### URL value behavior

- A valid Discord webhook URL selects a destination. A YAML list of URLs sends each log to every listed destination.
- A blank top-level category URL falls back to `webhooks.global`.
- A blank integration context URL falls back to that integration's base URL, then to `webhooks.global`.
- `none` (case-insensitive) explicitly disables that route and stops fallback. For example, `webhooks.towny-contexts.wars: 'none'` suppresses Towny war logs even if a global Towny or global webhook is set.
- Multiple categories may use the same webhook URL. A route can also contain multiple URLs.
- Only Discord webhook URLs beginning with `https://discord.com/api/webhooks/` or `https://discordapp.com/api/webhooks/` are accepted.

### Routing priority

| Log type | Destination order (first nonblank value wins) |
| --- | --- |
| Standard server category, votes, economy, staff | Specific `webhooks.<category>` → `webhooks.global` |
| AuraSkills level-up | Matching `webhooks.auraskills-skills.<skill-id>` → `webhooks.auraskills` → `webhooks.global` |
| Towny event | `webhooks.towny-events.<event-id>` → `webhooks.towny-contexts.<context>` → `webhooks.towny` → `webhooks.global` |
| LuckPerms user parent/group permission context | `webhooks.luckperms-<context>` → matching legacy `webhooks.luckperms-parents` or `webhooks.luckperms-permissions` → `webhooks.luckperms` → `webhooks.global` |
| RealisticSeasons context | `webhooks.realistic-seasons-contexts.<context>` → `webhooks.realistic-seasons` → `webhooks.global` |

If a route in this chain is set to `none`, the log is disabled there rather than continuing to a later destination. For example, `webhooks.luckperms-user-parents: 'none'` suppresses user parent changes without sending them to the legacy or global channel.

To send a route to more than one Discord channel, put the webhook URLs in a YAML list. This works for standard categories, integration routes, and context-specific routes. For example:

```yaml
webhooks:
  chat:
    - 'https://discord.com/api/webhooks/CHAT_CHANNEL_ID/CHAT_TOKEN'
    - 'https://discord.com/api/webhooks/ARCHIVE_CHANNEL_ID/ARCHIVE_TOKEN'
  towny-contexts:
    wars:
      - 'https://discord.com/api/webhooks/TOWNY_WARS_ID/TOWNY_WARS_TOKEN'
      - 'https://discord.com/api/webhooks/STAFF_AUDIT_ID/STAFF_AUDIT_TOKEN'
  towny-events:
    town-claim: 'https://discord.com/api/webhooks/TOWN_CLAIMS_ID/TOWN_CLAIMS_TOKEN'
```

The existing single-string format remains supported. An empty list behaves like a blank route and uses its normal fallback. Use the single value `'none'` to disable a route; it stops fallback.

Towny supports both the broad context routes and optional per-event routes. Event IDs are derived from the Towny event class name by removing the trailing `Event` and converting the remaining name to lowercase kebab case: for example, `TownClaimEvent` becomes `town-claim`, and `NationKingChangeEvent` becomes `nation-king-change`. Add any event ID under `webhooks.towny-events`; blank values use the existing context route. An event-specific value of `none` suppresses only that event. Per-event URLs also support lists for sending the event to multiple channels. Existing `settings.towny.contexts` toggles still apply, and individual configured event routes can be paused with `/lightlogs pause towny.events.<event-id>`.

## Configuration reference

The following options are in `plugins/LightLogs/config.yml`. The shipped config contains all supported keys and their default values.

### `webhooks`

| Key | What it logs / how it is used |
| --- | --- |
| `global` | Final fallback for any log without a more specific configured destination. Leave blank to disable logs whose entire fallback chain is blank. |
| `chat` | Player chat messages. |
| `commands` | Player-issued commands, including the command text and arguments. See `settings.ignored-commands` for commands excluded entirely. |
| `join` | Player join messages. |
| `leave` | Player quit/leave messages. |
| `deaths` | Player death messages. |
| `explosions` | Entity and block explosions, with world coordinates and affected block count. |
| `gamemode` | Player gamemode changes. |
| `votes` | NuVotifier/Votifier vote service and player name. |
| `economy` | CMI account balance changes. Includes account, old/new balance, delta, action, and source when available. This is an event from CMI's economy and works with CMI's Vault integration/injector. See the integration note below for other Vault providers. |
| `luckperms` | Global LuckPerms destination used after the specific and legacy LuckPerms routes. |
| `luckperms-user-parents` | Add/remove parent group changes on LuckPerms users. |
| `luckperms-group-permissions` | Add/remove individual permission nodes on LuckPerms groups. |
| `luckperms-permissions` | Legacy intermediate fallback for permission node changes not routed through a newer specific route, including user nodes and group nodes if the specific route is blank. |
| `luckperms-parents` | Legacy intermediate fallback for parent group changes not routed through a newer specific route. |
| `towny` | Global Towny destination used when a context URL is blank. |
| `towny-contexts.towns` | Town creation, renames, mergers, mayor changes, ruins/reclaims, and other matching Towny town events. |
| `towny-contexts.nations` | Nation creation, renames/mergers, town membership, king changes, and other matching nation events. |
| `towny-contexts.residents` | Resident membership, leave/kick, outlaw, and other matching resident events. |
| `towny-contexts.plots` | Town/plot claims, unclaims, plot type and sale/group changes, and related events. |
| `towny-contexts.permissions` | Permission and trust change events. |
| `towny-contexts.balances` | Towny transaction, tax, bankruptcy, and balance-related events. |
| `towny-contexts.jails` | Towny resident jail and unjail events. |
| `towny-contexts.wars` | Towny war, conquer, enemy, and allied-town events when exposed by the installed Towny/event plugin. |
| `towny-contexts.ranks` | Towny rank change events. |
| `towny-contexts.settings` | Town/town-block setting toggle and settings-change events. |
| `towny-contexts.other` | Catch-all for public Towny Bukkit events not matched to another context, including general API events such as Towny database load and new-day notifications. |
| `towny-events.<event-id>` | Optional route for one Towny Bukkit event. IDs are the class name in lowercase kebab case without the `Event` suffix, such as `town-claim` or `nation-king-change`. Blank routes use the event's context route; `none` disables just that event. |
| `auraskills` | Global AuraSkills destination and fallback for skill IDs without a specific URL. |
| `auraskills-skills.<skill-id>` | Optional AuraSkills destination per skill. Shipped IDs include `farming`, `foraging`, `mining`, `excavation`, `fishing`, `archery`, `defense`, `fighting`, `agility`, `endurance`, `alchemy`, and `enchanting`. Add custom skill IDs as quoted YAML keys if they contain `:`. |
| `realistic-seasons` | Global RealisticSeasons destination used when a context URL is blank. |
| `realistic-seasons-contexts.season-changes` | Season transition events, including old/new season and world when exposed by the event. |
| `realistic-seasons-contexts.special-events` | Custom season event starts/ends, including event name and world when exposed. |
| `realistic-seasons-contexts.day-changes` | RealisticSeasons calendar day changes. |
| `realistic-seasons-contexts.weather-reports` | Forecast/weather report events if the installed RealisticSeasons version exposes a matching Bukkit event. |
| `staff` | Staff++ command and moderation event destination. |

Towny event routing and RealisticSeasons event routing depend on the event classes present in the installed plugin version. For Towny, the specific webhook takes priority, then `towny`, then `global`. For RealisticSeasons, the specific context takes priority, then `realistic-seasons`, then `global`.

#### AuraSkills routing example

```yaml
webhooks:
  auraskills: 'https://discord.com/api/webhooks/GLOBAL_ID/TOKEN'
  auraskills-skills:
    farming: 'https://discord.com/api/webhooks/FARMING_ID/TOKEN'
    mining: ''
```

Farming level-ups go to the farming channel. Mining and other skills with blank/unmapped URLs go to the AuraSkills channel. A skill set to `none` is suppressed rather than sent to either fallback.

### `settings`

| Key | Default | What it does |
| --- | --- | --- |
| `suppress-mentions` | `true` | Replaces `@` in outgoing content and sets Discord's allowed mention parsing to empty, preventing log text from pinging users or roles. |
| `server-name` | `Minecraft Server` | Prefixes each Discord message with `[server-name]`, useful when several servers post into shared channels. |
| `ignored-commands` | `login`, `l`, `register`, `reg`, `changepassword`, `email` | Player command roots that LightLogs will not log. Matching is case-insensitive and checks the first word after `/`; add commands that may include passwords or private arguments. |
| `staff-commands` | List in config | Player command roots that also produce a Staff++ category log when Staff++ is installed. They still produce the regular `commands` log unless also listed in `ignored-commands`. |
| `towny.enabled` | `true` | Master switch for Towny event logs. `false` disables every Towny context. |
| `towny.contexts.towns` | `true` | Enable/disable Towny town events independently. |
| `towny.contexts.nations` | `true` | Enable/disable Towny nation events independently. |
| `towny.contexts.residents` | `true` | Enable/disable Towny resident events independently. |
| `towny.contexts.plots` | `true` | Enable/disable Towny plot events independently. |
| `towny.contexts.permissions` | `true` | Enable/disable Towny permissions and trust events independently. |
| `towny.contexts.balances` | `true` | Enable/disable Towny transaction and tax events independently. |
| `towny.contexts.jails` | `true` | Enable/disable Towny jail events independently. |
| `towny.contexts.wars` | `true` | Enable/disable Towny war-related events independently. |
| `towny.contexts.ranks` | `true` | Enable/disable Towny rank events independently. |
| `towny.contexts.settings` | `true` | Enable/disable Towny setting and toggle events independently. |
| `towny.contexts.other` | `true` | Enable/disable Towny events that do not match another configured context. |

Example to turn off just Towny plot and war events:

```yaml
settings:
  towny:
    enabled: true
    contexts:
      plots: false
      wars: false
```

All other Towny contexts stay enabled. Omitted context toggles default to enabled.

## Optional integration setup and coverage

| Integration | Requirement | Logged events / notes |
| --- | --- | --- |
| NuVotifier / Votifier | Install either plugin and configure voting as usual. | Logs vote username and service name to `votes`. |
| CMI economy | CMI installed, its economy enabled, and configured as the economy provider. The CMI Vault build or injector may be used to expose it to other plugins. | Logs CMI balance change events to `economy`. Vault itself does not define a universal Bukkit transaction event, so a different provider must expose its own events to be logged. |
| LuckPerms | Install LuckPerms. | Logs node additions/removals, routing user parent and group permission changes separately. Other node changes use the legacy route/global fallback. |
| Towny | Install Towny. | LightLogs discovers relevant Bukkit events in the installed Towny jar and assigns contexts by event class name. Messages include the event name and values available through that event's getters. Not every Towny action necessarily fires a public event. |
| AuraSkills | Install AuraSkills. | Logs skill level-ups with player, skill ID/name, and reached level. Per-skill hooks use AuraSkills IDs. |
| RealisticSeasons | Install RealisticSeasons. | Logs matching season, day, and custom-event API events. Weather reports are only available if that installed version publishes a matching Bukkit event; a forecast command alone is not a Bukkit event. |
| Staff++ | Install the current Staff++ plugin. | Logs matching moderation events available from the plugin and command roots listed in `settings.staff-commands`. |

Vault's Economy API is a provider interface, not a shared transaction event bus. The CMI integration listens for CMI's own balance event, including changes made through CMI as the Vault provider. Other Vault economy implementations need their own event integration before LightLogs can report their balance changes.

## Reload and troubleshooting

- Run `/lightlogs reload` after editing the config. The permission is `lightlogs.admin`, granted to operators by default.
- Pause and resume logging live without a restart or reload: `/lightlogs pause all` pauses all Discord output, and `/lightlogs resume all` clears the global pause and all individual pauses. Use `/lightlogs pause <target>` and `/lightlogs resume <target>` to pause or resume a single route; tab completion lists valid targets. Examples include `chat`, `join`, `economy`, `towny.wars`, `luckperms.user-parents`, `realistic-seasons.special-events`, and `auraskills.farming`.
- `/lightlogs status` shows whether the global pause is active and lists individually paused targets. Runtime pause state is held in memory and resets on server restart. Pausing an integration root such as `towny` or `luckperms` pauses all its contexts; pausing a dotted context such as `towny.wars` only pauses that context. `resume all` clears all pause state.
- Confirm the config is valid YAML. Keep indentation consistent (two spaces per level); do not use tabs.
- Check that the webhook URL is complete and copied from the intended Discord channel.
- If an event does not post, check that its category/context URL or a fallback is configured and not set to `none`.
- Check that the optional plugin is installed, enabled, and listed in the integration table above.
- Review the server console for invalid webhook URL warnings or HTTP response errors.
- Webhook network requests run asynchronously so they do not block the server tick thread.

## Discord webhook profile

By default, LightLogs sets each configured Discord webhook's persistent name to `LightLogs: <server-name>` and applies the bundled LightLogs icon when the plugin starts. `<server-name>` comes from `settings.server-name`. It updates each unique configured webhook once, including URLs in context-specific and multi-webhook routes. The configured webhook token authorizes this change; no Discord bot is needed. This replaces names and avatars you previously set on those webhooks. Set `options.webhook-profile.enabled: false` to leave them unchanged, or change `options.webhook-profile.name` to use a different webhook name. The `{server-name}` placeholder is replaced with the configured server name. Discord limits webhook names to 80 characters and rejects names containing `Discord` or `Clyde`.

The profile update uses Discord's [Modify Webhook with Token API](https://discord.com/developers/docs/resources/webhook#modify-webhook-with-token). If Discord rejects a webhook URL or the update fails, LightLogs logs a warning and continues operating normally.

## Update checker

LightLogs checks the `xShadowLightx/LightLogs` GitHub repository for a newer release in the background at startup. The check uses the latest published GitHub Release. If its tag contains a newer numeric version than the installed plugin version, LightLogs logs the release URL to console and sends online operators with `lightlogs.update-notify` a clickable link. Players joining after the check receive the same notice. The permission defaults to operators. The checker can only find versions that have been published as GitHub Releases; pushing code or creating a repository alone is not enough.

Set `options.update-checker.enabled: false` to disable checks. You can change `repository` to another public repository in `owner/repository` form. If it is blank or invalid, checking is skipped. Update requests run asynchronously with short connection/read timeouts; a network or GitHub API failure is only written at fine log level and does not affect plugin startup. The checker links to the GitHub release page so server owners can select the jar matching their Paper build. Existing servers keep their current `config.yml` when updating the plugin, so set `options.update-checker.repository: 'xShadowLightx/LightLogs'` manually in that file if it is blank.

## License

See the repository's license file, if included.
