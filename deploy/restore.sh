#!/usr/bin/env bash
# Put a backup back on the VM: sudo bash restore.sh /var/lib/capebabe/backups/world-1-2026-10-24_030000.db
# Stops the game, keeps the current file aside as *.before-restore, copies the backup in, starts the game.
set -euo pipefail
backup="${1:?usage: sudo bash restore.sh <backup file>}"
data=/var/lib/capebabe
name=$(basename "$backup" | sed -E 's/-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{6}\.db$/.db/')
if [ "$name" = "accounts.db" ]; then target="$data/accounts.db"; else target="$data/worlds/$name"; fi
systemctl stop capebabe
if [ -f "$target" ]; then cp "$target" "$target.before-restore"; fi
install -o capebabe -g capebabe -m 640 "$backup" "$target"
systemctl start capebabe
echo "Restored $backup -> $target (the old one is $target.before-restore)"
