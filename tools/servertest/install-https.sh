#!/usr/bin/env bash
# Adds automatic HTTPS (Caddy) in front of the Valtrin server. Run on the server as the valtrin user:
#     bash install-https.sh api.valtrin.app
# The domain's A record must already point at this server. Changes nothing else on the server.
set -euo pipefail
DOMAIN="${1:-}"
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]] || { echo "Usage: bash install-https.sh api.example.com"; exit 1; }
[ "$(id -un)" != "root" ] || { echo "Run this as the valtrin user, not root."; exit 1; }
MYIP="$(curl -fsS https://api.ipify.org || true)"
DNSIP="$(getent hosts "$DOMAIN" | awk '{print $1; exit}')"
echo "This server: ${MYIP:-unknown}   $DOMAIN points to: ${DNSIP:-nothing}"
[ -n "$DNSIP" ] && [ "$DNSIP" = "$MYIP" ] || { echo "The domain does not point at this server yet. Fix the DNS record and try again."; exit 1; }

if ! command -v caddy >/dev/null; then
  echo "== installing Caddy"
  sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https curl gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor --yes -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
  sudo apt-get update -y
  sudo apt-get install -y caddy
fi
sudo install -d -o caddy -g caddy /var/log/caddy
sudo tee /etc/caddy/Caddyfile >/dev/null <<EOF
$DOMAIN {
    encode zstd gzip
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
    }
    header {
        Strict-Transport-Security "max-age=31536000"
        X-Content-Type-Options nosniff
    }
    # never log request headers: they carry user keys
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
sudo caddy validate --config /etc/caddy/Caddyfile
# the check above runs as root and may create the log file: give it back to Caddy
sudo chown -R caddy:caddy /var/log/caddy
sudo systemctl enable --now caddy
sudo systemctl reload caddy || sudo systemctl restart caddy
echo "== done. Test from your PC:  curl https://$DOMAIN/ping   (should answer 401 'wrong key': that means HTTPS works)"
