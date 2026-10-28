# Chequer

Chequer is a checklist app that provides a simple way to manage and track lists of tasks that need to be completed.

- **Plans** are reusable patterns of tasks, such as a packing list or a house-cleaning routine, optionally
  arranged into sections.
- **Lists** hold the tasks you actually check off. Start one blank or from one or more plans; plans are merged
  together and duplicate tasks are skipped. A list is a copy, so later edits to a plan never change it.
- **Tasks** move between pending, completed, and skipped, with due dates and assignees.
- Every page updates live for everyone viewing it, and every change is recorded in a per-plan and per-list
  history.
- Full-text search covers plans, lists, and tasks.

Chequer runs as a single binary with a SQLite database. It's written in [Jolt](https://github.com/jolt-lang/jolt),
a dialect of Clojure, with server-rendered HTML and [Datastar](https://data-star.dev/), so there's no frontend
build.


## Quick Start

With a container:

```sh
bin/build image
podman run -d --name chequer -p 3000:3000 -v chequer-data:/data chequer:dev
```

Or build the binary (this requires Jolt) and run it with the example config:

```sh
bin/build release
cp config.example.ini config.ini
target/release/chequer server
```

Then open <http://localhost:3000>. Both start in single-user mode with no login.


## Documentation

- [Operations](doc/operations.md) covers installation, the configuration reference, the auth modes (single user,
  reverse proxy, and OIDC), backups, and upgrading.
- [Development](doc/development.md) explains how to run, test, and build from source.
- [Design](doc/design.md) describes the data model and architecture.
