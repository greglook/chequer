# Development

Basic instructions for working on Chequer. Requires [Jolt](https://github.com/jolt-lang) on your `PATH`; `cljstyle` and `clj-kondo` are optional but expected to be clean before committing.

The first run of any Jolt command fetches and compiles the git dependencies, which can take a few minutes.

## Dev REPL

Start a Jolt REPL:

```sh
bin/repl
```

## Dev server

Run the app on `localhost:3000` using the dev config in `dev/config.ini`:

```sh
bin/run server
```

State is kept (the SQLite database) under `dev/state/`. Arguments are passed through to `chequer.main`, which
takes a subcommand:

- `server` runs the server.
- `validate` checks that the config, database and auth settings are ready for the server, exiting with status 1 if not.
- `migrate` applies pending migrations to the configured database and lists the ones applied.
- `backup DEST` writes a copy of the configured database to the file `DEST`, refusing to overwrite it.

```sh
bin/run migrate
bin/run backup dev/state/backup.db
```

## Unit tests

Run the unit tests with the Cognitect test runner:

```sh
bin/test
```

Arguments are passed through to the runner, for example to run a single namespace or test:

```sh
bin/test -n chequer.auth-test
bin/test -v chequer.auth-test/single-mode-resolves-the-owner
```

Slow tests tagged `^:load` are skipped unless the arguments choose what to include. The load test opens 50
SSE streams against a real server and checks every stream sees every update:

```sh
bin/test -i :load
```

## Code style

Validate code style with `cljstyle`:

```sh
# print non-conforming files and diffs
cljstyle check

# rewrite files to correct issues
cljstyle fix
```

The project's formatting rules are configured in `.cljstyle`.

## Linting

Run static analysis using `clj-kondo`:


```sh
clj-kondo --lint src test
```

The linting rules are configured in `.clj-kondo/config.edn`.

## Building

Compile the project into a stand-alone binary:

```sh
# compile project
bin/build release

# remove build files
bin/build clean
```

The binary is written to `target/release/chequer`. Static assets and migrations are embedded in it, so it
runs from any directory. To build the container image instead:

```sh
bin/build image [--push]
```

See [operations.md](operations.md) for running and configuring either one.
