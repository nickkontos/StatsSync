# StatsSync

Exports player stats to JSON, updates WebStats scoreboards, and refreshes Plan extensions on a schedule.

## What it does
- Reads Plan data and server stats.
- Writes JSON for the public player stats page.
- Pushes scoreboard values for WebStats.
- Optionally refreshes Plan extensions to show additional columns.

## Config (`plugins/StatsSync/config.yml`)
- `planExtensions.enabled`: enable Plan extension refresh.
- `planExtensions.intervalMinutes`, `planExtensions.initialDelaySeconds`
- `playerStats.enabled`: enable JSON export.
- `playerStats.intervalMinutes`, `playerStats.initialDelaySeconds`
- `playerStats.exportPath`: path to write JSON (e.g., `/data/player-stats/data.json`).
- `playerStats.planApiUrl`: Plan API endpoint for the players table.
- `webstats.enabled`: enable scoreboard sync for WebStats.
- `webstats.intervalMinutes`, `webstats.initialDelaySeconds`

## Commands
- `/statssync reload`
- `/statssync run webstats`
- `/statssync run playerstats`
- `/statssync run planextensions`

## Notes
- Uses Plan database at `/data/plugins/Plan/database.db`.
- Reads whitelist from `/data/whitelist.json`.

## Build
- Java 21 (class file version 65)
- Paper 1.21.x

## Related
- Plan (Player Analytics): https://www.spigotmc.org/resources/plan-player-analytics.32536/
- WebStats: https://www.spigotmc.org/resources/webstats.105771/
