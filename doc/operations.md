# Operations

How to install, configure, back up, and upgrade a Chequer server.


## Installation

Chequer is a single binary that keeps all of its state in one SQLite file. It
needs these shared libraries on the host at runtime:

- `libsqlite3` (required)
- `libssl` and `libcrypto` (OpenSSL 3 or 1.1, required for OIDC mode)
- `libz` (optional)
- `libbrotlienc` (optional, compresses live-update streams)

On Debian or Ubuntu, `apt-get install libsqlite3-0 libssl3 zlib1g libbrotli1`
covers them.
The static assets and database migrations are embedded in the binary, so it
runs from any directory.

### Binary

Build the binary with `bin/build release` (see
[development.md](development.md)), then install it with a config file:

```sh
install -m 755 target/release/chequer /usr/local/bin/chequer
cp config.example.ini /etc/chequer/config.ini
chequer --config /etc/chequer/config.ini server
```

The binary takes a subcommand:

- `server` runs the server until it gets `SIGINT` or `SIGTERM`, then shuts down
  cleanly.
- `validate` checks that the server is ready to run, printing the outcome of
  each check and exiting with status 1 if any fail. It checks that:
  - the config file loads and is valid;
  - the database is a writable file, or can be created, in a writable directory
    (SQLite keeps its WAL files there, and migration backups go there too);
  - an existing database isn't newer than the binary, and no leftover
    migration backup would stop its pending migrations;
  - `time-zone` is a known time zone;
  - in proxy mode, every `trusted-proxies` entry is an IP or CIDR;
  - in OIDC mode, the `session-key` is usable and the issuer's discovery
    document can be fetched.
- `migrate` applies pending migrations and lists the ones it applied. The
  server does this on startup too, so running it separately is optional.
- `backup DEST` writes a copy of the database to the file `DEST`.

The config file comes from `--config`, then the `CHEQUER_CONFIG` environment
variable, and finally `config.ini` in the working directory.

### Container

The `Containerfile` builds an image that runs the server as an unprivileged
user (uid 10001):

```sh
podman build --format docker -t chequer .
podman run -d --name chequer -p 3000:3000 -v chequer-data:/data chequer
```

The image's default config listens on `0.0.0.0:3000`, keeps the database at
`/data/chequer.db`, sets `base-url` to `http://localhost:3000`, and runs in
single-user mode. To change any of that, mount your own config over
`/etc/chequer/config.ini` and keep `listen` and `database.path` as they are:

```sh
podman run -d --name chequer -p 3000:3000 \
  -v chequer-data:/data \
  -v ./config.ini:/etc/chequer/config.ini:ro \
  chequer
```

`/data` must be writable by the container user: it holds the database, its WAL
files, and the backups written before migrations. The image's `HEALTHCHECK`
polls `http://127.0.0.1:3000/healthz`. Podman only keeps it in `docker`-format
images, hence `--format docker`; Docker needs no flag.


## Configuration

The config is an ini file. Lines starting with `;` or `#` are comments, and
every key is optional. [`config.example.ini`](../config.example.ini) lists
them all with their defaults.

| Section         | Key               | Default                  | Description |
|-----------------|-------------------|--------------------------|-------------|
| `[server]`      | `listen`          | `127.0.0.1:3000`         | Address and port to listen on: `host:port`, `:port`, or `[ipv6]:port`. |
|                 | `base-url`        | `http://<listen>`        | Public URL of the app. Used for the OIDC redirect URI and the CSRF origin check. |
|                 | `time-zone`       | `UTC`                    | Time zone for display when the browser doesn't report one. |
| `[database]`    | `path`            | `chequer.db`             | Path to the SQLite database file. Created if missing. |
| `[auth]`        | `mode`            | `single`                 | One of `single`, `proxy`, or `oidc`. |
| `[auth.single]` | `name`            | `owner`                  | Handle of the owner user. |
|                 | `title`           | `Owner`                  | Display name of the owner user. |
| `[auth.proxy]`  | `header`          | (required in proxy mode) | Request header carrying the authenticated username. |
|                 | `trusted-proxies` | `127.0.0.1, ::1`         | Comma-separated IPs or CIDRs allowed to set the header. |
| `[auth.oidc]`   | `issuer`          | (required in oidc mode)  | Issuer URL, used for discovery. |
|                 | `client-id`       | (required in oidc mode)  | OIDC client id. |
|                 | `client-secret`   | (required in oidc mode)  | OIDC client secret. |
|                 | `session-key`      | (required in oidc mode)  | Hex key of at least 32 bytes that signs the login cookie. |
|                 | `scopes`          | `openid profile email`   | Scopes requested at login. |
|                 | `session-ttl`     | `14d`                    | Session lifetime, as days (`d`), hours (`h`), or minutes (`m`). |

The config is validated at startup, and the server refuses to start if it's
invalid.

**Set `base-url` whenever users reach the app at a different address than
`listen`**, for example behind a reverse proxy or in a container. Requests that
change data must come from the `base-url` origin, so if it's wrong, every edit
fails with `403 Forbidden`.


## Authentication

Chequer doesn't manage passwords. All users can see and edit everything; users
exist for attribution in history. Pick one of three modes with `[auth] mode`.

### Single user

Everyone who reaches the app acts as the owner set in `[auth.single]`. There's
no login. Use this mode for a personal instance on a trusted network, or behind
a proxy that handles auth when you don't need multiple users.

```ini
[auth]
mode = single

[auth.single]
name = alice
title = Alice
```

### Proxy auth

A reverse proxy authenticates users and passes the username in a header. Users
are created the first time they appear. The header is only trusted on requests
from an address in `trusted-proxies`; every other request, and any request
without the header, gets `403 Forbidden`.

Make sure the proxy always overwrites the header, so clients can't supply their
own, and that the app's port is reachable only through the proxy.

For example, with [Caddy](https://caddyserver.com/) using basic auth on the
same host:

```ini
[server]
listen = 127.0.0.1:3000
base-url = https://chequer.example.com

[auth]
mode = proxy

[auth.proxy]
header = X-Forwarded-User
trusted-proxies = 127.0.0.1, ::1
```

```caddy
chequer.example.com {
    basic_auth {
        # Hashes from `caddy hash-password`.
        alice $2a$14$...
        bob $2a$14$...
    }
    reverse_proxy 127.0.0.1:3000 {
        header_up X-Forwarded-User {http.auth.user.id}
    }
}
```

The same pattern works with [oauth2-proxy](https://oauth2-proxy.github.io/) or
an authenticating ingress. Point `header` at whichever header carries the
username, such as `X-Auth-Request-User` or `X-Forwarded-Preferred-Username`.

When Chequer runs in a container, the proxy's requests come from the container
network rather than loopback, so set `trusted-proxies` to that network, such as
`10.88.0.0/16` for podman's default bridge.

### OIDC

Chequer acts as an OpenID Connect client, using the authorization code flow
with PKCE. Users log in through the provider and are created on first login.
Their handle comes from the `preferred_username` claim, falling back to `sub`.
Their display name comes from `name`, falling back to the handle. Both are
refreshed at each login. These claims may come from the ID token or from the
provider's userinfo endpoint, which Chequer calls with the access token when
discovery advertises one. Some providers, Authelia among them, only send them
from userinfo. After login, the server keeps a session whose id is
the only thing stored in the browser cookie.

1. Register a confidential client with the provider. Set the redirect URI to
   `<base-url>/auth/callback`.
2. Generate a session key with `openssl rand -hex 32`.
3. Configure the app:

   ```ini
   [server]
   base-url = https://chequer.example.com

   [auth]
   mode = oidc

   [auth.oidc]
   issuer = https://sso.example.com/realms/home
   client-id = chequer
   client-secret = ...
   session-key = ...
   ```

The session cookie is `Secure`, so serve the app over HTTPS. Browsers make an
exception for `http://localhost`. Sessions last for `session-ttl`; expired
sessions are swept hourly. Logging out deletes the session and closes its open
pages' live-update streams.

Keep `client-secret` and `session-key` private, and make the config file
readable only by the user running Chequer. Changing `session-key` only
invalidates logins in progress, not existing sessions.


## Health check

`GET /healthz` needs no authentication. It returns `200` with `ok` once the
database answers, followed by the number of open live-update streams and
render cache counters.


## Backups

The database is a single SQLite file in WAL mode, so don't just copy the file
while the server is running. Use the `backup` command to make a consistent copy
while it runs:

```sh
chequer --config /etc/chequer/config.ini backup /backups/chequer-$(date +%F).db
```

In a container, run the backup in the running container and copy it out:

```sh
podman exec chequer chequer backup /data/backup.db
```

To restore, stop the server, remove any `-wal` and `-shm` files next to the
database, replace the database file with the backup, and start the server.


## Upgrading

Database migrations are compiled into the binary. On startup, and with the
`migrate` command, Chequer compares the database's `PRAGMA user_version` with
the migrations it has, and applies the pending ones in a single transaction. If
a migration fails, the transaction rolls back, nothing changes, and the server
doesn't start.

Before migrating a non-empty database, Chequer writes a copy next to it named
`<path>.pre-v<N>.bak`, where `N` is the version being migrated to, for example
`chequer.db.pre-v5.bak`. It never overwrites an existing backup file, so a
leftover file from an earlier attempt stops the migration. Move or delete it
first. These backups are never deleted automatically; remove them once you're
happy with the upgrade.

To upgrade:

1. Take a backup, as above.
2. Stop the server.
3. Replace the binary, or pull and rebuild the image.
4. Start the server. It logs any migrations it applies.

To roll back after a migration, stop the new version, restore the
`.pre-v<N>.bak` file (or your own backup) as the database, and start the old
version. Changes made after the upgrade are lost.
