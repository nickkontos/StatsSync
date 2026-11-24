# StatsSync

Exports player stats for my Minecraft server's web pages and scoreboards
It reads Plan's MySQL tables and Minecraft's stat and advancement files

The source is based on the plugin running on Paper 26.3. The build uses the Paper 1.21.11 API and Java 21 or later

## Build

```sh
mvn verify
```

Copy `target/StatsSync.jar` into `plugins/`, then configure `plugins/StatsSync/config.yml`
Plan must be using MySQL or MariaDB. The JDBC driver is loaded from Plan's libraries directory

Set `planDb` and `paths` for your server before enabling a job. Paths are relative to the server directory unless you give an absolute path
Older worlds may use `world/stats` and `world/advancements` instead of the defaults
Recipe unlocks are excluded from the advancement count

Jobs run every 15 minutes when enabled:

- `webstats` updates scoreboard objectives on the main server thread
- `playerStats` writes the player stats JSON
- `serverInfo` writes server status, player and plugin information
- `planExtensions` updates Plan's extension tables and needs database write access

All jobs are disabled in the example config

## Commands

```text
/statssync reload
/statssync run playerstats
/statssync run serverinfo
/statssync run webstats
/statssync run planextensions
```

These commands require `statssync.admin`, which defaults to operators

`mvn verify` also checks the JSON reader and stat calculations without connecting to a Minecraft server or database
