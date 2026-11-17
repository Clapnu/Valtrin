#!/usr/bin/env bash
# Valtrin server first-time setup for a fresh Ubuntu 24.04 VPS. READ IT BEFORE YOU RUN IT.
#
# What it does:
#   1. Updates the system and turns on automatic security updates
#   2. Creates a normal user (not root) with sudo, and installs YOUR public SSH key for it
#   3. Turns off root login and password login (only after confirming your key is in place, so you cannot lock yourself out)
#   4. Firewall: only SSH (22), HTTP (80) and HTTPS (443) are open
#   5. fail2ban blocks repeated bad logins
#   6. Installs Caddy (automatic HTTPS) if you give a domain
#   7. Creates an empty systemd service template for the Valtrin server program (not started)
#
# Run as root, once, from the provider's console or your first SSH login:
#   NEWUSER=valtrin PUBKEY='ssh-ed25519 AAAA... you@pc' DOMAIN=api.example.com bash setup-server.sh
# DOMAIN is optional (leave it out and Caddy is skipped).
#
# It does not read, store or send any secrets, API keys or passwords.
set -euo pipefail

NEWUSER="${NEWUSER:-valtrin}"
PUBKEY="${PUBKEY:-}"
DOMAIN="${DOMAIN:-}"

die() { echo "ERROR: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root"
[[ "$NEWUSER" =~ ^[a-z][a-z0-9_-]{1,30}$ ]] || die "NEWUSER must be lowercase letters, digits, - or _"
[[ "$PUBKEY" =~ ^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp256)\ [A-Za-z0-9+/=]+ ]] || die "set PUBKEY to your PUBLIC key (the .pub file contents). Never your private key."
if [ -n "$DOMAIN" ]; then
  [[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || die "DOMAIN looks wrong"
fi

export DEBIAN_FRONTEND=noninteractive

echo "== 1. system update and automatic security updates"
apt-get update -y
apt-get upgrade -y
apt-get install -y ufw fail2ban unattended-upgrades curl ca-certificates gnupg debian-keyring debian-archive-keyring apt-transport-https
dpkg-reconfigure -f noninteractive unattended-upgrades

echo "== 2. user '$NEWUSER' with your key"
if ! id "$NEWUSER" >/dev/null 2>&1; then
  adduser --disabled-password --gecos "" "$NEWUSER"
fi
usermod -aG sudo "$NEWUSER"
install -d -m 700 -o "$NEWUSER" -g "$NEWUSER" "/home/$NEWUSER/.ssh"
AK="/home/$NEWUSER/.ssh/authorized_keys"
touch "$AK"
grep -qxF "$PUBKEY" "$AK" || echo "$PUBKEY" >> "$AK"
chown "$NEWUSER:$NEWUSER" "$AK"
chmod 600 "$AK"
# sudo without a password is needed because the account has no password (key login only)
echo "$NEWUSER ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/90-$NEWUSER"
chmod 440 "/etc/sudoers.d/90-$NEWUSER"
visudo -cf "/etc/sudoers.d/90-$NEWUSER" >/dev/null

echo "== 3. SSH: key only, no root"
grep -qxF "$PUBKEY" "$AK" || die "your key did not install; not touching SSH settings"
# SSH uses the FIRST value it finds for each setting, reading files in name order. Cloud providers often ship a file
# (for example 50-cloud-init.conf) that turns passwords on, so ours must sort BEFORE theirs: 00-valtrin.conf.
rm -f /etc/ssh/sshd_config.d/99-valtrin.conf
cat > /etc/ssh/sshd_config.d/00-valtrin.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
X11Forwarding no
MaxAuthTries 3
EOF
sshd -t || die "sshd config test failed; leaving SSH as it was"
systemctl reload ssh || systemctl reload sshd
# Check what SSH will REALLY do, not just what we wrote.
EFF="$(sshd -T)"
echo "$EFF" | grep -qix 'passwordauthentication no' || die "password login is still enabled; check /etc/ssh/sshd_config.d/"
echo "$EFF" | grep -qix 'permitrootlogin no' || die "root login is still enabled; check /etc/ssh/sshd_config.d/"
echo "$EFF" | grep -qix 'kbdinteractiveauthentication no' || die "keyboard-interactive login is still enabled; check /etc/ssh/sshd_config.d/"

echo "== 4. firewall"
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "== 5. fail2ban"
cat > /etc/fail2ban/jail.d/valtrin.local <<'EOF'
[sshd]
enabled = true
maxretry = 4
findtime = 10m
bantime = 1h
EOF
systemctl enable --now fail2ban
systemctl restart fail2ban

if [ -n "$DOMAIN" ]; then
  echo "== 6. Caddy for $DOMAIN (point the domain's A record at this server first)"
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update -y
  apt-get install -y caddy
  install -d -o caddy -g caddy /var/log/caddy
  cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080
    header {
        Strict-Transport-Security "max-age=31536000"
        X-Content-Type-Options nosniff
    }
    # never log request headers: they will carry user keys
    log {
        output file /var/log/caddy/access.log
        format filter {
            wrap console
            fields {
                request>headers delete
            }
        }
    }
}
EOF
  systemctl enable --now caddy
  systemctl reload caddy || systemctl restart caddy
else
  echo "== 6. skipped (no DOMAIN given)"
fi

echo "== 7. service template (not started)"
install -d -o "$NEWUSER" -g "$NEWUSER" "/home/$NEWUSER/valtrin-server" "/home/$NEWUSER/valtrin-data"
cat > /etc/systemd/system/valtrin-server.service <<EOF
[Unit]
Description=Valtrin server
After=network-online.target
Wants=network-online.target

[Service]
User=$NEWUSER
WorkingDirectory=/home/$NEWUSER/valtrin-server
# Replace this line when the server program exists:
ExecStart=/usr/bin/env echo "valtrin server program not installed yet"
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=read-only
ReadWritePaths=/home/$NEWUSER/valtrin-data
# keys live in a file only this user can read, not on the command line
EnvironmentFile=-/home/$NEWUSER/valtrin-server/.env

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload

echo
echo "DONE. BEFORE closing this session, open a SECOND terminal and check you can log in:"
echo "    ssh $NEWUSER@THIS_SERVER_IP"
echo "Only after that works, close this one. Root login is now disabled."
