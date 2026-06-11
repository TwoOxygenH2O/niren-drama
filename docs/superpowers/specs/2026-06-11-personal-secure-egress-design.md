# personal-secure-egress Design

## Purpose

`personal-secure-egress` is a private configuration repository for a personal, secure, rule-based network egress. It is not a proxy core implementation. It manages scripts, templates, routing rules, and documentation for using a small Ubuntu server as a private outbound network endpoint for development tools, AI tools, browsers, and selected devices.

The project must support a China-mainland daily network environment where domestic sites, domestic apps, and games can continue to use the local network directly, while selected foreign sites and development traffic can use the private server exit.

This project must not be used to bypass account registration controls, risk checks, or access policies of third-party services. It focuses on private network configuration, device routing, and operational safety.

## Target Environment

Server:

- Provider plan: CstoneCloud CUII-ISP-A or equivalent.
- CPU: 1 core E5v4 class.
- Memory: 1 GB RAM.
- Storage: 20 GB SSD.
- Bandwidth: 100 Mbps.
- Monthly traffic: 1 TB.
- Public IP: 1 IPv4 address.
- OS: Ubuntu Server 22.04 LTS.

Primary client:

- Windows PC.
- Needs rule-based routing, not always-on global proxying.
- Games such as Delta Force should use the local mainland network directly.
- Development tools, AI tools, and foreign websites should be able to use the server exit.
- Domestic websites and domestic software should use direct local networking.

Secondary clients:

- macOS laptops/desktops.
- Phones and tablets.
- Other personal computers.

## Recommended Architecture

The first version should use a lightweight `sing-box` server managed by `systemd`, plus a GUI client on Windows for rule-mode routing.

Server side:

- Install only required OS packages and `sing-box`.
- Run `sing-box` as a `systemd` service.
- Keep logs small and rotated.
- Use a firewall with only SSH and the selected proxy service port open.
- Add swap for reliability on a 1 GB RAM server.
- Keep secrets outside Git.

Windows side:

- Use Clash Verge Rev as the primary Windows GUI client.
- Use rule mode by default.
- Keep direct rules for China domains, China IP ranges, LAN/private ranges, and game-related traffic.
- Route development, AI, and selected foreign-site traffic through the server.
- Keep one-click mode switching available: rule, global, and direct.

Other devices:

- macOS can use Clash Verge Rev or a compatible sing-box client.
- iOS, Android, and tablets can use Hiddify App or sing-box-compatible clients.
- Each device should have an independent client credential or profile so access can be revoked per device.

## Repository Scope

The private GitHub repository should be named:

```text
personal-secure-egress
```

It should contain:

- Server setup scripts.
- `sing-box` service configuration templates.
- Client configuration templates.
- Rule files for direct and proxy routing.
- Documentation for Windows, macOS, mobile, security, operations, and troubleshooting.

It should not contain:

- Server passwords.
- SSH private keys.
- Real UUIDs or shared secrets.
- Real subscription URLs.
- Provider dashboard screenshots containing private data.
- Full server backups.

## Proposed Repository Structure

```text
personal-secure-egress/
  README.md
  .gitignore
  docs/
    architecture.md
    server-setup.md
    windows-client.md
    mobile-mac-client.md
    security.md
    troubleshooting.md
  server/
    install.sh
    update.sh
    restart.sh
    sing-box.server.example.json
  client/
    clash-verge.example.yaml
    sing-box.client.example.json
  rules/
    direct-cn.yaml
    direct-games.yaml
    proxy-dev-ai.yaml
  secrets/
    .gitkeep
```

## Routing Model

The default client routing mode should be rule-based:

- LAN and private IP ranges: direct.
- China domains and China IP ranges: direct.
- Domestic apps and games: direct.
- Development and AI destinations: proxy.
- Selected foreign documentation, Git, package registry, and API traffic: proxy.
- Fallback/default traffic: configurable, initially conservative.

The Windows client should avoid forcing all traffic through the server by default. Full global proxy mode should exist as a manual temporary mode only.

## Configuration Model

Configuration should be template-based:

- Example files are committed to Git.
- Real files are generated locally or on the server from examples.
- Real generated configs are ignored by Git.
- A small number of documented variables should be used, such as server host, service port, user ID, and client name.

The first implementation should favor readability over maximum automation. Scripts should be short, explicit, and easy to inspect.

## Server Operations

The server scripts should support:

- Initial package update and dependency install.
- `sing-box` binary installation or update.
- Config validation before restart.
- `systemd` service install.
- Service restart.
- Basic status checks.
- Basic firewall setup.
- Optional swap setup for 1 GB RAM reliability.

The scripts should not:

- Install heavyweight web panels.
- Install unrelated monitoring stacks.
- Store secrets in the repository.
- Enable public unauthenticated proxy access.

## Security Requirements

Minimum security baseline:

- SSH key login preferred.
- Password login disabled after keys are verified.
- Firewall enabled.
- Proxy service requires authentication or cryptographic client identity.
- Each personal device should have a separate client profile where practical.
- Logs must not capture sensitive request bodies.
- Logs must be rotated or size-limited.
- Secrets must be excluded by `.gitignore`.

## Testing And Verification

Initial verification should include:

- `sing-box` config validation succeeds.
- `systemd` service starts and restarts cleanly.
- Server port is reachable only where expected.
- Windows rule mode routes domestic sites directly.
- Windows rule mode routes selected foreign/development destinations through the server.
- A game test confirms the game still uses the local network path.
- DNS leak and route behavior are checked at a practical level.

## First Milestone

Milestone 1 should deliver a private repository with:

- README explaining the project goal and boundaries.
- Server install/update/restart scripts.
- Example server config.
- Example Windows client config.
- Three starter rule files:
  - China/direct rules.
  - Game/direct rules.
  - Development and AI/proxy rules.
- Security and troubleshooting docs.

Milestone 1 is successful when the Windows PC can run rule mode where domestic traffic and games stay direct, while selected development and foreign-site traffic uses the private server exit.
