# VibeEventSnow

VibeEventSnow is a small Paper plugin for running a snow-themed Minecraft server event. It provides admin setup commands for lobbies, team spawns, arenas, and basic match flow.

## Features

- Admin command for snow event setup.
- Red and blue team lobby/spawn configuration.
- Cuboid zones for event areas.
- Basic match and lobby listeners.
- English and Russian message files.
- Paper API based build for Minecraft 1.21.x.

## Status

MVP / learning project. This is a public source snapshot of a small event plugin, useful as a reference or starting point, not a polished production event framework.

Known risks:

- Arena setup is server-specific and should be tested carefully before a public event.
- Balance, rewards, and edge cases may need additional work.
- The package name is generic because the plugin started as a quick server utility.

## Build

Requirements:

- Java 21
- Maven

```powershell
mvn package
```

If Maven is not installed globally, use a local Maven installation outside the public repository. Do not publish bundled `.tools` folders or generated jars.

## Configuration

Resources are in `src/main/resources/`:

- `config.yml`
- `plugin.yml`
- `messages/en.yml`
- `messages/ru.yml`

Review defaults before installing the plugin on a live server.

## License

MIT. See `LICENSE`.

