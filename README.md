# ScoreboardDBPlugin

A Minecraft Paper plugin (1.20.1+) for storing scoreboard data in a local or remote database (SQLite, MySQL, PostgreSQL). Supports Velocity integration, periodic sync, and configurable YAML.

## Features
- Stores scoreboard data in a database (local SQLite, or remote MySQL/PostgreSQL)
- Auto-creates tables: server name, scoreboard name, string, value
- YAML config (see `config.yml`)
- Commands: save value, get value, sync-now
- Periodic sync (configurable interval)
- Velocity support for server name

## Setup
1. Place the plugin JAR in your server's `plugins` folder.
2. Configure `config.yml` in the plugin's data folder.
3. Start the server.

## Building
- Requires Java 17+
- Build with Maven: `mvn package`

## Dependencies
- Paper API 1.20.1+
- HikariCP
- SQLite JDBC
- MySQL JDBC
- PostgreSQL JDBC
- SnakeYAML

## License
MIT
