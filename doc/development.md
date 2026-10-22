# Development

Basic instructions for working on Chequer. Requires [Jolt](https://github.com/jolt-lang) on your `PATH`; `cljstyle` and `clj-kondo` are optional but expected to be clean before committing.

The first run of any Jolt command fetches and compiles the git dependencies, which can take a few minutes.

## Dev REPL

Start a Jolt REPL:

```sh
bin/repl
```

## Dev server

Run the app on `localhost:3000` using the dev config in `dev/chequer.ini`:

```sh
bin/run
```

State is kept (the SQLite database) under `dev/state/`. Extra arguments are passed through to `chequer.main`.

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
