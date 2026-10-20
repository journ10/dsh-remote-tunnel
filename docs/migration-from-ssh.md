# Migrating from the SSH/nginx setup

An older deployment may use an SSH reverse tunnel to a Linux host
and a Docker nginx server block. The relay path replaces both the transport and
the server-side reverse proxy.

## What changes

| Old | New |
|---|---|
| DSH opens `ssh -R` to the server on port 22 | DSH opens one outbound WSS on 443 |
| sshd needs `GatewayPorts clientspecified` | no sshd changes |
| Docker nginx proxies to a remote bind address | Caddy + relay proxy to the tunnel |
| one public domain, one DSH | relay host plus per-instance subdomains |
| DSH launch token/cookie protects the public URL | pairing code + per-device cookie + DSH cookie injection |

Keep the old setup running until the new path is verified. Both can coexist on
different ports/domains, but two reverse proxies cannot both own 443 on the
same host.

## Reuse the server

The old nginx container may own 443. The relay installer starts Caddy on 80/443,
so stop or move nginx first:

```sh
# Example only: inspect your own compose project before changing it.
docker ps --filter publish=443
docker stop <nginx-container>
```

If you want to keep the old nginx, put Caddy on another host or move nginx to a
non-443 port and route the relay domain through Caddy. The supported deployment
is Caddy as the only public listener.

## Cut over

1. Point DNS at the server:
   - `relay.example.com`
   - `*.relay.example.com`
2. Run `server/install.sh` with your domain.
3. Install the new plugin and configure `mode: relay` with the new public host.
4. Write the instance token file and restart `dsh web`.
5. Pair the phone and verify:
   - `https://relay.example.com/healthz` shows `online: 1`;
   - the pairing URL loads the DSH UI;
   - sessions, streaming, approvals, and file upload/download work.
6. Revoke the old public URL:
   - remove the old `connection.trustedHosts` entry and `web-runtime.publicUrl`;
   - stop the old SSH tunnel/plugin;
   - remove the old nginx server block or firewall rule.

## Rollback

Set `mode: ssh` and the old `sshHost` / `remoteBind` values, restore the old
domain in `cordis.patch.yml`, and restart `dsh web`. The plugin still includes
the SSH supervisor; keep your own server-side SSH/nginx configuration backup.

## Keeping the SSH path

`mode: ssh` is supported for migration, but it requires:

- outbound port 22 from DSH to the server;
- `GatewayPorts clientspecified` and `AllowTcpForwarding yes` in sshd;
- a reverse proxy on the server that can reach the remote bind address.

Those are exactly the operational constraints the relay path removes. Prefer
the relay for new deployments.
