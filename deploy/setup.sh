#!/usr/bin/env bash
# One-time setup on a fresh Ubuntu/Debian VM, from this folder: sudo bash setup.sh
# Tailscale is separate (see HOSTING.md, Phase 2): install it and `tailscale up` first.
set -euo pipefail
cd "$(dirname "$0")"
apt-get update
# keytool (for the server's TLS key) comes with the JRE
apt-get install -y openjdk-17-jre-headless sqlite3
id capebabe >/dev/null 2>&1 || useradd --system --home-dir /var/lib/capebabe --shell /usr/sbin/nologin capebabe
install -d -o capebabe -g capebabe -m 750 /var/lib/capebabe
install -d -m 755 /opt/capebabe
install -m 644 capebabe.service /etc/systemd/system/capebabe.service
if [ ! -f /etc/capebabe.env ]; then
    install -m 600 capebabe.env.example /etc/capebabe.env
fi
systemctl daemon-reload
systemctl enable capebabe
echo
echo "Next:"
echo "  1. sudo nano /etc/capebabe.env   (world password; --bind \$(tailscale ip -4))"
echo "  2. from your machine: bash deploy/deploy.sh <user>@<this vm>"
echo "  3. optional, to bring your world: stop the game at home, copy ~/.capebabe/accounts.db and"
echo "     ~/.capebabe/worlds/world-1.db to /var/lib/capebabe/ (worlds/ for the world), chown -R capebabe:capebabe"
