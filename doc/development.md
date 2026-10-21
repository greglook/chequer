# Development

Basic instructions for working on Chequer. Requires [Jolt](https://github.com/jolt-lang) on your `PATH`; `cljstyle` and `clj-kondo` are optional but expected to be clean before committing.

The first run of any Jolt command fetches and compiles the git dependencies, which can take a few minutes.

## Dev REPL

```sh
bin/repl
```

Starts a Jolt REPL with the project's `src` and `resources` on the classpath.

## Dev server

```sh
bin/run
```

Runs the app on `localhost:3000` using the dev config in `dev/chequer.ini`, keeping state (the SQLite database) under `dev/state/`. Extra arguments are passed through to `chequer.main`.

## Unit tests

```sh
bin/test
```

Runs the tests under `test/` with the Cognitect test runner. Arguments are passed through to the runner, for example to run a single namespace or test:

```sh
bin/test -n chequer.auth-test
bin/test -v chequer.auth-test/single-mode-resolves-the-owner
```

## Code style

```sh
cljstyle check
```

Reports files that don't match the project's formatting rules (configured in `.cljstyle`). Run `cljstyle fix` to reformat them in place.

## Linting

```sh
clj-kondo --lint src test
```

Runs static analysis using the configuration in `.clj-kondo/config.edn`.
