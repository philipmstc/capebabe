# capebabe

_(Name is subject to change)_

Java class files start with hex codes `CAFEBABE`. This is a Runescape inspired TUI multiplayer "number go up" game made in Java. 

![](assets/release/v1.0.SNAPSHOT-screenshot.png)



# Playing

## Download

Get the file for your computer from the [latest release](https://github.com/philipmstc/capebabe/releases/latest).
Java comes inside, nothing else to install. Everyone who plays together needs the **same version**: the game
checks when you log in and tells you to update if not.

- **Windows:** `capebabe-<version>-windows-x64.zip`. Unzip it anywhere and double-click `capebabe\capebabe.exe`.
  Windows may say it "protected your PC" (the app isn't signed): **More info** > **Run anyway**.
- **macOS:** `...-macos-arm64.dmg` (Apple Silicon: M1 and later) or `...-macos-x64.dmg` (Intel). Open it and drag
  capebabe into Applications. The app isn't signed, so the first time macOS refuses to open it: go to **System
  Settings > Privacy & Security** and click **Open Anyway** (on older macOS, right-click the app > **Open**).
- **Linux:** `...-linux-x64.tar.gz`. Unpack it and run `capebabe/bin/capebabe`.
- **Anything else:** `capebabe-<version>-all.jar` with Java 17+: `java -jar capebabe-<version>-all.jar`.

## Logging in

The game opens on two short pages:

1. **Name** and **Password**, then **Login**. The first time you use a name on a host, that password becomes
   yours there.
2. **Join a world:** **Host** is the host's address, e.g. `203.0.113.7` (or `203.0.113.7:43594` if they use
   another port); leave it blank to play your own world on your computer. **World Password** is the one the host
   gave you (not needed for your own world). **Continue** joins; **Switch User** goes back to page one. The game
   remembers the last host and name.

A wrong name or password sends you back to page one; a wrong world password, or a host that can't be reached,
keeps you on page two.

The connection is encrypted. The server's fingerprint is on the bottom line of the login screen; the first
time you join a host it also says "first visit": check the fingerprint matches the one at the bottom of the
host's login screen. Your game remembers it, and refuses to connect if it ever changes. One connection per
address at a time, so two people in one house need the host to add `--max-per-ip 2`.

In game, press `,` for settings and the list of controls. Your progress is saved on the host's machine (in
`~/.capebabe`), so you keep it as long as you join the same host and world.

## Hosting a world for friends

Hosting is still a command (you play in the same window):

```bash
capebabe --host --world "Gabe's World" --world-password "something long"
```

where `capebabe` is `capebabe\capebabe.exe` on Windows, `/Applications/capebabe.app/Contents/MacOS/capebabe` on
macOS, `capebabe/bin/capebabe` on Linux, or `java -jar capebabe-<version>-all.jar`. `--world` picks which world
to host, by name or number; each one is its own save, and players are welcomed by its name. The default port is
`43594` (`--port` to change it). Give the world password to friends privately. To be reachable from outside your
home you'll need to forward that port; see [HOSTING.md](HOSTING.md) for the checklist and safer alternatives.

## Building from source

Use `git clone` (a GitHub zip download loses `gradlew`'s permission to run on macOS/Linux), then:

```bash
./gradlew shadowJar      # build/libs/capebabe-<version>-all.jar, needs Java 17+
./gradlew packageApp     # build/dist/: the app for the OS you're on, Java included (needs a full JDK 17+)
```

On Windows without a bash shell, use `gradlew.bat`. To publish a release for every OS, bump `version` in
`build.gradle.kts`, then push a matching tag (`git tag v0.0.2 && git push origin v0.0.2`): GitHub Actions
(`.github/workflows/release.yml`) builds Windows, macOS and Linux and attaches them to a release.

# Change log

## 0.0.1 - Oct 19 2026

- Added togglable configuration to show product tiers as unique graphics 

Example without setting:

![](assets/release/v0.0.1-contrasting-tiers-off.png)

Example with setting:

![](assets/release/v0.0.1-contrasting-tiers.on.png)