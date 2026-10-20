# capebabe

_(Name is subject to change)_

Java class files start with hex codes `CAFEBABE`. This is a Runescape inspired TUI multiplayer "number go up" game made in Java. 

![](assets/release/v1.0.SNAPSHOT-screenshot.png)



# Playing

You need **Java 17+** (e.g. [Eclipse Temurin](https://adoptium.net)). Everyone who plays together must build
the **same commit**: the game checks its version when you log in and tells you to update if they differ.

## Build the jar

```bash
git clone https://github.com/philipmstc/capebabe.git
cd capebabe
./gradlew shadowJar
```

On Windows without a bash shell, use `gradlew.bat shadowJar`. The jar is
`build/libs/capebabe-<version>-all.jar`, currently `capebabe-0.0.1-all.jar` (the version is in
`build.gradle.kts`). Older jars in `build/libs` are stale; run the one matching the current version.

## Run it

- **Solo:** `java -jar build/libs/capebabe-0.0.1-all.jar`
- **Host a world for friends** (you play in the same window):

  ```bash
  java -jar build/libs/capebabe-0.0.1-all.jar --host --world "Gabe's World" --world-password "something long"
  ```

  `--world` picks which world to host, by name or number. Each one is its own save, and players are
  welcomed by its name. Without it you get world 1. The default port is `43594` (`--port` to change it). Give the world password to friends privately. To be
  reachable from outside your home you'll need to forward that port; see [HOSTING.md](HOSTING.md) for the
  checklist and the safer alternatives.
- **Join a friend's world:**

  ```bash
  java -jar build/libs/capebabe-0.0.1-all.jar --connect <host's address>:43594
  ```

## Logging in

The game opens on a login screen. Pick a **name** and **password**: the first time you log in with a name,
that password becomes yours. When joining someone's world, it then asks for their **world password**.

The connection is encrypted. The server's fingerprint is on the bottom line of the login screen; the first
time you join a host it also says "first visit": check the fingerprint matches the one at the bottom of the
host's login screen. Your game remembers it, and refuses to
connect if it ever changes. One connection per address at a time, so two people in one house need the host to
add `--max-per-ip 2`.

In game, press `,` for settings and the list of controls. Your progress is saved on the host's machine (in
`~/.capebabe`), so you keep it as long as you join the same host and world.

# Change log

## 0.0.1 - Oct 19 2026

- Added togglable configuration to show product tiers as unique graphics 

Example without setting:

![](assets/release/v0.0.1-contrasting-tiers-off.png)

Example with setting:

![](assets/release/v0.0.1-contrasting-tiers.on.png)