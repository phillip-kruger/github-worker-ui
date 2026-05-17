#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INSTALL_DIR="$HOME/.local/share/github-worker-ui"
SYSTEMD_DIR="$HOME/.config/systemd/user"
PORT=7478
HOSTNAME="github-worker.house-elves"

echo "=== GitHub Worker Dashboard — Setup ==="
echo

# --- Prerequisites ---

missing=()
command -v java &>/dev/null || missing+=("java (17+)")
[[ -f "$SCRIPT_DIR/mvnw" ]] || missing+=("mvnw (run from the project directory)")

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "Missing prerequisites: ${missing[*]}"
    exit 1
fi

if [[ ! -f "$HOME/.config/github-worker/config" ]]; then
    echo "github-worker is not installed yet."
    echo "Run install.sh in the github-worker/ directory first."
    exit 1
fi

echo "Prerequisites OK."
echo

# --- Build ---

echo "Building production jar..."
"$SCRIPT_DIR/mvnw" package -DskipTests -q -f "$SCRIPT_DIR/pom.xml"
echo "Build complete."

# --- Install ---

mkdir -p "$INSTALL_DIR"
cp -r "$SCRIPT_DIR/target/quarkus-app/"* "$INSTALL_DIR/"
echo "Installed to $INSTALL_DIR"

# --- Detect Java path ---

JAVA_BIN=$(dirname "$(command -v java)" 2>/dev/null || echo "/usr/bin")

# --- Detect OS and install service ---

install_systemd() {
    mkdir -p "$SYSTEMD_DIR"

    cat > "$SYSTEMD_DIR/github-worker-ui.service" <<EOF
[Unit]
Description=GitHub Worker Dashboard (House Elves)
After=network.target

[Service]
Type=simple
ExecStart=$JAVA_BIN/java -jar $INSTALL_DIR/quarkus-run.jar
Environment=HOME=$HOME
Environment=PATH=$JAVA_BIN:$HOME/.local/bin:/usr/bin:/bin
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

    systemctl --user daemon-reload
    systemctl --user enable --now github-worker-ui.service

    if command -v loginctl &>/dev/null; then
        loginctl enable-linger "$USER" 2>/dev/null || true
    fi

    echo "Systemd service enabled (auto-starts on boot)."
}

install_launchd() {
    local plist_dir="$HOME/Library/LaunchAgents"
    local plist_file="$plist_dir/com.house-elves.github-worker-ui.plist"
    mkdir -p "$plist_dir"

    cat > "$plist_file" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.house-elves.github-worker-ui</string>
    <key>ProgramArguments</key>
    <array>
        <string>$JAVA_BIN/java</string>
        <string>-jar</string>
        <string>$INSTALL_DIR/quarkus-run.jar</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$HOME/.config/github-worker/ui-stdout.log</string>
    <key>StandardErrorPath</key>
    <string>$HOME/.config/github-worker/ui-stderr.log</string>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>$JAVA_BIN:$HOME/.local/bin:/usr/local/bin:/usr/bin:/bin</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
</dict>
</plist>
EOF

    launchctl unload "$plist_file" 2>/dev/null || true
    launchctl load "$plist_file"
    echo "Launchd agent installed (auto-starts on login)."
}

case "$(uname -s)" in
    Linux)  install_systemd ;;
    Darwin) install_launchd ;;
    *)
        echo "Unsupported OS: $(uname -s)"
        echo "Run manually: java -jar $INSTALL_DIR/quarkus-run.jar"
        ;;
esac

# --- Local hostname ---

if ! grep -q "$HOSTNAME" /etc/hosts 2>/dev/null; then
    echo
    echo "To access the dashboard at http://$HOSTNAME:$PORT"
    echo "we need to add an entry to /etc/hosts (requires sudo)."
    read -rp "Add hostname entry? [Y/n] " add_host
    if [[ "${add_host,,}" != "n" ]]; then
        echo "127.0.0.1 $HOSTNAME" | sudo tee -a /etc/hosts > /dev/null
        echo "Added: 127.0.0.1 $HOSTNAME"
    fi
else
    echo "Hostname $HOSTNAME already in /etc/hosts."
fi

echo
echo "=== Setup complete! ==="
echo
echo "Dashboard: http://$HOSTNAME:$PORT"
echo "       or: http://localhost:$PORT"
echo
