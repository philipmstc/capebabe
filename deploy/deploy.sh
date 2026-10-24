#!/usr/bin/env bash
# Build the jar and run it on the VM: bash deploy/deploy.sh user@host
# (Git Bash on Windows is fine; needs ssh access and sudo on the VM.)
set -euo pipefail
host="${1:?usage: bash deploy/deploy.sh user@host}"
cd "$(dirname "$0")/.."
./gradlew -q shadowJar
jar=$(ls -t build/libs/capebabe-*-all.jar | head -1)
echo "Deploying $jar to $host"
scp "$jar" "$host:/tmp/capebabe.jar"
# restarting saves everyone first (SIGTERM -> the shutdown hook); players reconnect afterwards
ssh -t "$host" 'sudo install -m 644 /tmp/capebabe.jar /opt/capebabe/capebabe.jar \
    && sudo systemctl restart capebabe \
    && sleep 3 && sudo journalctl -u capebabe -n 8 --no-pager'
