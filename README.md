# github-worker-ui

Web dashboard for monitoring and controlling the [github-worker](../github-worker/) House Elf. Built with Quarkus.

## Install

Requires the `github-worker` to be installed first.

```bash
./install.sh
```

The installer will:

1. Build the Quarkus production jar
2. Install it to `~/.local/share/github-worker-ui/`
3. Set up an auto-starting service (systemd on Linux, launchd on macOS)
4. Optionally add `github-worker.house-elves` to `/etc/hosts`

## Access

- **http://github-worker.house-elves:7478** (if hostname was added)
- **http://localhost:7478** (always works)

## Features

- **Issues panel** — tracked issues with current state, title, PR link, last updated. Click a row to see the state machine flow diagram with the current step highlighted.
- **Reviews panel** — tracked review requests with state badges
- **Logs panel** — recent systemd journal output from the worker
- **Config panel** — view and edit all configuration values (secrets are masked)
- **Trigger Now** — run the worker immediately from the UI
- **Preview** — dry-run to see what the worker would pick up
- **Live updates** — WebSocket pushes state changes to the browser every 5 seconds

## Development

```bash
./mvnw quarkus:dev
```

The dev UI is available at http://localhost:7478/q/dev/

## API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/state` | GET | Current worker state (issues + reviews) |
| `/api/config` | GET | Config values (secrets redacted) |
| `/api/config` | PUT | Update config values |
| `/api/logs?lines=100` | GET | Recent worker logs |
| `/api/trigger` | POST | Trigger a worker run |
| `/api/preview` | POST | Run worker in preview mode |
| `/api/live` | WebSocket | Live state updates |
