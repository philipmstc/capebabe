# Hosting capebabe for friends

A plan for running a world your friends can join, cheaply or for free, without opening your home network to
the internet. Written 2026-10-19 against the current code. Prices and free-tier terms change often, so check
the provider's page before you commit to one.

## TL;DR

1. **Now, free, about 30 minutes:** keep hosting from your own PC (`--host`) and put it on **Tailscale**. You and your
   friends install Tailscale and you share your PC with them. They connect to its Tailscale address. You open no
   router ports and change no code.
2. **When you want it always on:** move the world to a **small cloud VM running Tailscale, with no public game
   port** (Hetzner about €4/month, an AWS `t4g.nano` about $7/month all-in, or Oracle's Always Free tier if you
   can get capacity). Your Terraform experience fits this well.
3. **Before anything is reachable from the public internet:** add a version handshake, logins and a bind address
   (see "Code changes"). Until then, keep the server inside Tailscale.

## What "opening your network" actually means

- Your router does **NAT**: devices on your Wi-Fi share one public address, and the router drops unsolicited
  incoming connections. That's why `--host` on your PC is only reachable inside your house today.
- **Port forwarding** (telling the router to send port 43594 to your PC) makes your game server reachable by
  *anyone* on the internet. Port scanners find open ports within hours. Anything that connects then talks to code
  with no login, running on your personal machine. That's the risk you want to avoid.
- An **overlay network / mesh VPN** (Tailscale, ZeroTier, Headscale) avoids it. Each device makes *outgoing*
  encrypted connections, so nothing has to be opened. Devices get private addresses (Tailscale's are `100.x.y.z`)
  that only invited devices can reach. To the game it looks exactly like a home network, so `--connect 100.x.y.z`
  just works.
- A **cloud VM** moves the server off your network entirely. If it's compromised, the damage stays on a
  throwaway box. You can still keep its game port off the public internet with the same overlay trick.

### What the game exposes today

The game protocol is TLS 1.3 over TCP on port 43594, one JSON message per line inside it (`net/NetworkServer.java`,
`net/SocketConnection.java`, `net/Tls.java`). Since 2026-10-19/20:
- **Encryption:** everything, passwords included, is encrypted. The host's key is made the first time it hosts
  (by the JDK's `keytool`) and kept in `~/.capebabe/tls/` (`server.p12` + its password file; keep both, and don't
  share them). Clients can't check a self-signed key against anyone, so they **trust the server the first time**
  and remember its fingerprint in `~/.capebabe/known-servers`, like SSH. If it ever changes they refuse to connect
  ("isn't the one you connected to before"). The host's login screen shows its fingerprint on the bottom line, and so
  does a friend's (marked "first visit" the first time): compare them once (read it out on a call) and you know nobody's in the
  middle. If you ever delete `tls/`, friends have to remove your line from their `known-servers`.
- **Connection limits:** one connection per IP at a time (`--max-per-ip 2` if two friends share a house), 32 in
  total, 30 seconds to finish logging in, and about 40 messages per second per connection (more are dropped, and
  a sustained flood is disconnected).
- **Version check:** the client sends `Intent.PROTOCOL_VERSION` when it logs in. A mismatch gets "update your game"
  (or "the server needs updating"). Clients from before this get the same message.
- **Account logins:** name + password, stored hashed in `accounts.db`. An account without a password yet (like
  every account made before logins) gets one on its first login.
- **World password:** a shared secret for network players. `--host`/`--server` won't start without
  `--world-password` (or an explicit `--no-world-password`). New accounts, and password claims on old ones, only
  happen *after* the world password passes, so strangers can't make or claim accounts.
- **Lockout:** 5 wrong passwords (account or world) from one IP within 10 minutes locks that IP out for the rest
  of the 10 minutes, and the connection is closed.
- Only known message types are accepted, and lines are capped at 64KB.

**Still missing:**
- **Admin allowlist.** With `--admin`, everyone who gets in can run `/1`-`/5`. There's no per-person list yet.

## Port forwarding with logins (what you're doing first)

For a few friends who know the world password, with the server only up while you're playing, this is a
reasonable risk. The checklist:

1. **Pick a port** and keep it for good, e.g. 43594 (the default) or something less obvious like 47123. A
   non-default port doesn't make it secure, but it cuts down random scanner noise.
2. **Give your PC a fixed LAN address.** In the router, reserve your PC's current IP (often called "DHCP
   reservation"), so the forward doesn't point at the wrong device next week.
3. **Router:** forward TCP `<port>` (external) to `<your PC's LAN IP>:<port>`. Only that one port, and TCP only.
4. **Windows firewall:** allow inbound TCP `<port>` for `java.exe` only. When Windows asks the first time,
   allow "Private" networks. If your Wi-Fi is set to *Public*, add the rule by hand (admin PowerShell):

   ```powershell
   New-NetFirewallRule -DisplayName "capebabe" -Direction Inbound -Protocol TCP -LocalPort 43594 -Action Allow -Profile Any -Program "C:\path\to\java.exe"
   ```

   The rule is for one program. If you host from the packaged app instead of `java -jar`, the game runs inside
   `capebabe\capebabe.exe`, so make a rule with `-Program` pointing at that file (in the folder you unzipped).
5. **Host** with a world password. Leave off `--admin` unless you want friends using `/1`-`/5`:

   ```bash
   java -jar build/libs/capebabe-0.0.1-all.jar --host --port 43594 --world-password "something long and random"
   ```

   Or set the `CAPEBABE_WORLD_PASSWORD` environment variable instead of the flag, so it's not in your shell
   history. Send the world password to friends privately (DM, not a group chat with others in it).
6. **Friends** download the game (see the README), start it, and type your **public** IP (search "what is my ip"
   on *your* PC) as the Host on the login's second page ("Join a world"), with the world password. Your home
   address (192.168.x.x) only works for people on your Wi-Fi. Whatever password they pick the first time becomes
   theirs. On their first connection the login screen says "first visit" under the title and
   shows your fingerprint on its bottom line: check it matches the one at the bottom of yours. Your public IP can change (most home connections), so check it each session, or
   use a free dynamic DNS name (e.g. DuckDNS) that follows it.
7. **When you stop playing:** closing the game stops the server, and nothing listens on the port. Better still,
   disable the forward in the router when you won't play for a while. Keep everyone's jar on the same build: the
   version check will tell them when they're not.
8. **Rotate the world password** if it leaks or someone leaves the group: just start with a new one. Accounts
   keep their own passwords.

## Options

| Option | Cost | Your network exposed? | Always on? | Effort | Notes |
|---|---|---|---|---|---|
| **A. Your PC + Tailscale** | Free | No | Only while your PC is on | Very low | Best first step. Friends install Tailscale; you share your machine with them |
| B. Your PC + port forward | Free | **Yes** | Only while your PC is on | Low | What you want to avoid. Not recommended until there's auth |
| C. Your PC + game tunnel (playit.gg) | Free tier | No (the tunnel's address is public) | Only while your PC is on | Low | Built for game servers. Gives a public address without router changes, but the game port is still public, so it needs auth first |
| **D. Cloud VM + Tailscale** | €0-7/month | No | Yes | Medium | The recommended "real" setup |
| E. Cloud VM, public port + firewall allowlist | €0-7/month | No (but the VM is public) | Yes | Medium | Allow only friends' home IPs. Simple, but home IPs change and you'll be editing rules |
| F. Container platform (Fly.io, Railway) | ~$0-5/month | No | Yes | Medium | Must support raw TCP and a persistent volume for SQLite. Many (Render, Cloud Run, Heroku) only do HTTP. See "Later" |
| G. Raspberry Pi / old laptop at home + Tailscale | Hardware you own | No | Yes | Medium | Always on without a bill. Same as A, on a box that stays on |

### Overlay network choices

- **Tailscale.** The easiest. The free personal plan covers a small group of users and devices. Use **machine
  sharing**: friends sign up with their own account and you share just the game machine with them, so they can't
  see anything else on your tailnet. Access rules (ACLs) can limit them to port 43594.
- **ZeroTier.** Similar, with a free tier. You create a network, friends join it by ID and you approve them.
  Slightly more manual.
- **Headscale.** A self-hosted Tailscale control server. No vendor, but you're running another service. Not worth
  it for a handful of friends.
- **WireGuard by hand.** The most control, but you manage keys and configs per friend. Skip it unless you want to
  learn it.

### Cloud VM choices

The game is tiny: one JVM, a 600ms tick, SQLite. 1 vCPU and 512MB-1GB of RAM is plenty, and ARM is fine because
it's plain Java 17.

- **Hetzner Cloud** (around €4-5/month for the smallest x86/ARM VM, public IPv4 included). Cheap, predictable,
  simple. The UI and API are nice and there's a Terraform provider. EU and US regions.
- **AWS** (`t4g.nano`/`t4g.micro`, ARM). You already know it, and Terraform makes it reproducible.
  - **Cost:** the instance is about $3/month, plus about $0.65 for an 8GB EBS disk, plus about $3.65 for a public
    IPv4 address. AWS now bills public IPv4 even on free-tier accounts, and Tailscale needs outbound internet, so
    you'll want one. That's about $7/month unless your account has free-tier hours or credits.
  - **No SSH needed:** SSM Session Manager means you can open zero inbound ports, not even SSH.
- **Oracle Cloud Always Free.** Genuinely $0, with generous ARM VMs.
  - **Capacity:** it's often "out of capacity" in popular regions when you try to create one.
  - **Account risk:** Oracle can reclaim idle free instances, and accounts are sometimes closed with little
    explanation.
  - **Verdict:** great if you can get it, and keep backups.
- **Google Cloud `e2-micro`** (always free in some US regions). Free, but small, and the free terms have region and
  network caveats.
- **DigitalOcean / Linode / Vultr** (about $4-6/month). Same idea as Hetzner, slightly pricier.

## Plan of attack

### Phase 1: play this week (option A)

1. Tailscale on your PC: install it and sign in.
2. Each friend installs Tailscale and creates their own account. In the admin console, use **Share** on your PC's
   machine and send them the invite.
3. Optional but good: in the Tailscale ACLs, allow shared users only `tcp:43594` on your PC.
4. Windows firewall: Tailscale traffic arrives on the Tailscale interface, so allow Java inbound there (or allow
   TCP 43594 for the Tailscale adapter only). Your home Wi-Fi can stay a *Public* network.
5. Build and host:

   ```bash
   ./gradlew shadowJar
   ```

   ```bash
   java -jar build/libs/capebabe-0.0.1-all.jar --host
   ```

   Drop `--admin` so friends can't run world-building commands. Leave it on if you trust them to.
6. Friends run it (see "Getting friends a client"):

   ```bash
   java -jar capebabe-0.0.1-all.jar --connect <your-pc's-100.x.y.z-address> --name theirname
   ```
7. The catch: the world is only up while you're playing. When you quit, everyone is disconnected. Closing the
   window does save and exit cleanly now.

### Phase 2: an always-on world on a small VM (option D)

1. **Pick a provider.** For "cheap and boring", go with Hetzner. For "practice my day job", go with AWS +
   Terraform. For "free if it works", try Oracle.
2. **Provision**, with Terraform if you like (see the sketch below):
   - Ubuntu LTS, smallest instance. A separate small volume for `/var/lib/capebabe` makes snapshots and rebuilds
     easier.
   - **No inbound rules for 43594.** For SSH, either none at all (AWS SSM / `tailscale ssh`) or only from your IP.
3. **Install:** Java 17 (`openjdk-17-jre-headless`) and Tailscale. Join the VM to your tailnet with a pre-auth key
   and tag it, e.g. `tag:capebabe`. Share it with friends the same way as in Phase 1.
4. **Run it as a service**, with the files in `deploy/` (they keep Unix line endings even in a Windows checkout):
   - Copy the `deploy/` folder to the VM and run `sudo bash setup.sh`. It installs Java 17 (`keytool` included,
     for the server's TLS key) and `sqlite3`, makes a `capebabe` user, `/opt/capebabe` for the jar and
     `/var/lib/capebabe` for the data, and installs and enables `capebabe.service` (systemd, locked down:
     it can only write its data folder).
   - Edit `/etc/capebabe.env` (root-only): `CAPEBABE_WORLD_PASSWORD`, and in `CAPEBABE_ARGS` put
     `--bind $(tailscale ip -4)` so the game listens on the Tailscale address only, and `--admins yourname` for
     who may use admin commands. If Tailscale isn't up yet after a boot the bind fails and systemd retries every 5s.
   - From your machine: `bash deploy/deploy.sh user@vm` builds the jar, copies it over and restarts the service
     (restarting saves everyone first), then shows the last log lines. `journalctl -u capebabe -f` for the
     live log. The world-file lock means a second copy can't open the same world by accident.
5. **Moving your existing world (optional):** stop the game at home, then copy `~/.capebabe/accounts.db` to
   `/var/lib/capebabe/` and `~/.capebabe/worlds/world-1.db` to `/var/lib/capebabe/worlds/`, and
   `chown -R capebabe:capebabe /var/lib/capebabe`. Older saves upgrade themselves on first start (and are backed
   up just before, see below). Your TLS key stays at home: the VM makes its own, so friends see a new fingerprint
   once and need to delete the old line for your home address from `known-servers` only if they reuse the address.
6. **Backups are built in:** hosting (`--server` / `--host`) copies `accounts.db` and the world into
   `<data>/backups` when it starts (before any upgrade runs) and every 24 hours, keeping the newest 7 of each
   (`--backups DIR`, `--backup-hours H` (0 = off), `--keep-backups N`). The copies are made with SQLite's
   `VACUUM INTO` on their own connection, so they're consistent while people play. Put one off the box now and
   then (`scp user@vm:/var/lib/capebabe/backups/*.db .`), and add volume snapshots if the provider makes that cheap.
   To put one back: `sudo bash deploy/restore.sh /var/lib/capebabe/backups/world-1-<date>.db` (it stops the game,
   keeps the current file as `*.before-restore`, copies the backup in and starts the game again).
7. **Updates:** `bash deploy/deploy.sh user@vm` again. Friends update their client from the GitHub release; an
   old client is told to update (the protocol version check).

### Phase 3: only if you ever want strangers / the public internet

Do the code changes below first, especially logins, and then consider option B, C or E. Even then, keep running it
on a VM, never on your PC.

## Code changes, in priority order

These are worth doing even for Phase 1-2, and they're small:

1. ~~Protocol version check~~ **done** (`Intent.PROTOCOL_VERSION`: bump it whenever an Intent/Event changes shape).
2. ~~Server password~~ **done** (`--world-password`, required when hosting).
3. ~~Per-character logins~~ **done** (hashed passwords in `accounts.db`, a login screen in the client, a lockout
   after repeated failures).
4. ~~Bind address~~ **done** (`--bind 100.x.y.z`: listen on that address only; the start-up line says where it's
   listening).
5. ~~Admin allowlist~~ **done** (`--admins phili,friend`: just those players get admin commands; `--admin` is still
   everyone).
6. ~~Rate / connection limits~~ **done** (`NetworkServer.Limits`, `--max-per-ip`).
7. ~~Encryption (TLS)~~ **done** (TLS 1.3, a self-signed key per host, clients trust it on first use).
8. ~~Configurable font / fallback~~ **done** (a fallback list of common monospace fonts, and `--font name` to pick
   one first).
9. ~~Backups~~ **done** (built in, see Phase 2 step 6).

## Getting friends a client

- ~~Releases~~ **done** (2026-10-21): pushing a `v*` tag runs `.github/workflows/release.yml`, which builds a Windows
  zip, macOS dmgs (Apple Silicon + Intel) and a Linux tarball with Java bundled (`./gradlew packageApp`, jpackage),
  plus the plain jar, and attaches them to a GitHub Release. Friends download one file and double-click; the login
  page asks for the server, so no command line. Unsigned: Windows SmartScreen ("More info > Run anyway") and macOS
  Gatekeeper ("Open Anyway" in Privacy & Security) warn the first time. Signing would need a code-signing
  certificate (Windows) / a paid Apple developer account + notarization (macOS).
- **Still possible:** `git clone` + `./gradlew shadowJar` for anyone who'd rather build it (not the GitHub zip
  download: it loses `gradlew`'s execute permission).
- **Repo visibility:** if the repo is private, add friends as collaborators or publish releases somewhere they can
  see. If it's public, the server address and any password should never be committed.

## Terraform sketch (AWS, option D)

This is the shape, not a drop-in: a VM with no inbound rules, Tailscale on boot, and a separate data volume.

```hcl
resource "aws_security_group" "capebabe" {
  name = "capebabe"
  # no ingress at all: players come in over Tailscale, you over SSM or `tailscale ssh`
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "capebabe" {
  ami                         = data.aws_ami.ubuntu_arm.id # Ubuntu 24.04 arm64
  instance_type               = "t4g.nano"
  vpc_security_group_ids      = [aws_security_group.capebabe.id]
  associate_public_ip_address = true # outbound internet for Tailscale + apt
  iam_instance_profile        = aws_iam_instance_profile.ssm.name
  user_data = templatefile("cloud-init.yaml", {
    tailscale_auth_key = var.tailscale_auth_key # tagged, reusable or one-off key; keep it out of git
  })
}

resource "aws_ebs_volume" "data" {
  availability_zone = aws_instance.capebabe.availability_zone
  size              = 2
}
```

`cloud-init.yaml` installs `openjdk-17-jre-headless` and Tailscale, runs
`tailscale up --authkey=... --advertise-tags=tag:capebabe`, mounts the data volume at `/var/lib/capebabe`, writes
the systemd unit above and enables it. The `tailscale` Terraform provider can manage ACLs and auth keys as code
too. Hetzner has an equivalent provider (`hcloud_server` + `hcloud_volume` + `hcloud_firewall` with no inbound
rules).

## Later: if a container platform or browser client ever appeals

Most cheap platforms (Render, Cloud Run, App Runner, Heroku) only route HTTP. Running the game protocol over
**WebSockets** instead of raw TCP would open all of them up, and make a browser client possible one day. The
`GameConnection` interface already separates the transport from the game, so a WebSocket version would be a new
`SocketConnection`/`NetworkServer` pair.
