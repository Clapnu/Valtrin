#!/usr/bin/env bash
# Makes the Valtrin server start by itself (and restart if it ever stops). Run on the server as the valtrin user:
#     bash install-service.sh
# Needs sudo (it writes /etc/systemd/system/valtrin-server.service). Does NOT start the server unless you say "start".
set -euo pipefail
U="$(id -un)"
[ "$U" != "root" ] || { echo "Run this as the valtrin user, not root."; exit 1; }
JAR="/home/$U/valtrin-server/valtrin-server.jar"
[ -f "$JAR" ] || { echo "Upload valtrin-server.jar to /home/$U/valtrin-server/ first."; exit 1; }
JAVA="$(command -v java)" || { echo "Java is not installed."; exit 1; }
mkdir -p "/home/$U/valtrin-data"
sudo tee /etc/systemd/system/valtrin-server.service >/dev/null <<EOF
[Unit]
Description=Valtrin server
After=network-online.target
Wants=network-online.target

[Service]
User=$U
WorkingDirectory=/home/$U/valtrin-server
ExecStart=$JAVA -Xmx1200m -XX:+UseSerialGC -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:+ExitOnOutOfMemoryError -Duser.home=/home/$U/valtrin-data -jar $JAR
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/home/$U/valtrin-data
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
SystemCallArchitectures=native

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable valtrin-server
if [ "${1:-}" = "start" ]; then sudo systemctl restart valtrin-server; echo "started"; else echo "installed, not started. Start with: sudo systemctl start valtrin-server"; fi
echo "Logs:    journalctl -u valtrin-server -f"
echo "Status:  systemctl status valtrin-server"
