---
name: jolt-web-app
description: Build/extend a Jolt (Clojure on Chez Scheme) ring web app with ring-chez-adapter, hiccup and Datastar SSE; project layout, deps, testing, and Jolt quirks.
---

# Jolt web app (ring-chez-adapter + hiccup + Datastar)

## Learning Jolt APIs
- Don't probe the jolt binary (strings, all-ns) — user rejects that. Clone sources instead:
  - jolt-lang/examples repo (shallow clone to /tmp): `ring-app` (Integrant, ring-defaults, SQLite via jolt-lang/db), `glimmer-datastar` (datastar + SSE), `reactive-dashboard` (multi-ns test runner).
  - jolt-lang/ring-chez-adapter at tag v0.8.0: `src/ring_chez/adapter.clj` (`run-server` opts docstring), `src/ring_chez/sse.clj`.
- Datastar wire format: `sdk/ADR.md` in starfederation/datastar. Vendor `bundles/datastar.js` from a release tag into `resources/public/js/`.

## deps.edn shape
```clojure
{:paths ["src" "resources"]
 :deps {jolt-lang/ring-chez-adapter {:git/url "https://github.com/jolt-lang/ring-chez-adapter"
                                     :git/tag "v0.8.0"
                                     :git/sha "87e637e2ce3b6d25fee67b8bf86cd7595eea30a4"}
        weavejester/hiccup {:git/url "https://github.com/weavejester/hiccup"
                            :git/sha "b6b12097ba973141fe87c1b4d6a27ddcce5d5929"}}
 :jolt/build {:embed ["resources"]}
 :aliases {:test {:extra-paths ["test"] :main-opts ["-m" "<app>.core-test"]}}
 :tasks {serve {:doc "run the server" :main-opts ["-m" "<app>.core"]}
         test  "jolt -M:test"}}
```
- Git deps need full SHAs. First run fetches deps into ~/.jolt/gitlibs and is slow — use long timeouts (≥900s).
- `jolt run` is a builtin command, so name the server task `serve`.

## Testing with cognitect test-runner
- Add `io.github.cognitect-labs/test-runner` (git tag v0.5.1, full sha) under the :test alias :extra-deps and make bin/test `exec jolt -M:test -m cognitect.test-runner "$@"`; then `bin/test -n my.ns-test` runs one namespace. Missing src ns shows as FileNotFoundException "Could not locate x.jolt (or .clj/.cljc)" — that is the red step in TDD.
- Check the real exit status: `bin/test > log 2>&1; echo exit=$?` (not through `| tail`).
- SQLite tests: temp file path under java.io.tmpdir, delete the file and its -wal/-shm/.bak siblings afterwards.

## SQLite via jolt-lang/db
- `(:require [db.jdbc] [jdbc.core :as jdbc])` in that order. Writer connection behind `locking`, reader pool in an atom; `jdbc/atomic-apply` for tx; `{:returning true}` on insert!; migrations keyed on PRAGMA user_version with `VACUUM INTO` backup.
- `(into [:map ...])` with one arg throws in Jolt; build vectors literally.

## Adding libs / probing
- Probe a lib before building on it: write /tmp/probe.clj with `(require ...)` + printlns, run `jolt -M:test -e '(load-file "/tmp/probe.clj")'` (first run slow, then ~10s).
- Pins known to load: jolt-lang/db (SQLite via `db.jdbc` + `jdbc.core`), io.github.jolt-lang/time (tick), metosin/malli, org.clojure/tools.cli, cognitect-labs/test-runner. Copy the SHAs from /tmp clones of jolt-lang/examples `ring-app/deps.edn`; for annotated tags use the peeled (`^{}`) SHA from `git ls-remote`.
- jdbc: require `db.jdbc` before `jdbc.core`; `jdbc/connection "sqlite:path"`; `fetch` for row-returning PRAGMAs, `execute!` otherwise; `jdbc/atomic` = `atomic-apply` with rollback on throw; `VACUUM INTO ?` works with a param.
- ring-chez adapter `run-server` takes `:host` (default loopback) and `:port`.

## Server / handler
- `(adapter/run-server handler {:port p})` returns a handle and serves on background threads; binds 127.0.0.1 by default (`:host`).
- Keep -main alive: `(jolt.host/block-sigint) (jolt.host/park-until-interrupt) (adapter/stop-server server)`.
- Static file: `(slurp (io/resource "public/js/datastar.js"))`.
- SSE: response body may be a core.async channel. Use `ring-chez.sse/event-response ch` and `sse/format-event {:event "datastar-patch-elements" :data (str "elements " html)}`; close the channel to end the stream.
- Middleware is plain fn wrapping; log via `(binding [*out* *err*] (println ...))`, time with `System/nanoTime`.

## Testing
- clojure.test; call the handler directly with `{:request-method :get :uri "/" :headers {}}`. For SSE, `async/<!!` on `(:body r)`; returns nil after close.
- Test ns `-main` runs `run-tests` and throws `ex-info` when fail+error > 0 (non-zero exit).
- Capture stderr with `(binding [*err* (java.io.StringWriter.)] ...)`.
- Run `jolt test` unpiped so the exit status is real (or redirect to a log and echo the exit code).
- Smoke test: start `PORT=39xx jolt serve` as a background shell, poll the port with an HTTP client until it answers, request the SSE route without buffering, read the shell output for log lines, then kill the shell.

## Quirks
- hiccup2 escapes `'` as `&apos;` in attributes: `data-on:click="@get(&apos;/hello&apos;)"` — assert on the escaped form.
- Calling an undefined var through an ns alias fails at runtime with "Unknown class <alias>" (normal TDD red step).
- No real Java interop (shimmed java.* subset only); regex engine is irregex.

## Library landscape (verified from jolt-lang sources)
- SQLite: `jolt-lang/db` (clone github.com/jolt-lang/db; README, no src/ dir at top). `(require '[db.jdbc])` BEFORE `jdbc.core`; `(jdbc/connection "sqlite:path")`; `fetch`/`fetch-one`/`execute!`/`insert!`/`atomic` (nested savepoints). No last-insert-id: use `{:returning true}` on insert. Queries are strings/sqlvecs or honeysql (`honey.sql/format`). See examples `ring-app/src/app/db.clj`. System SQLite 3.53 has FTS5.
- Time: `io.github.jolt-lang/time` (tick-style). JSON: `clojure.data.json` (used by glimmer-datastar). Crypto: `jolt-lang/jolt-crypto` (`digest`, `hmac`, `random-bytes`, `aes-cbc`, `pkey-verify`, `parse-x509`). HTTP client: `jolt-lang/http-client` (clj-http-lite). Router: `jolt-lang/router` (reitit; needs JOLT_FEATURES=clj + many deps — Chequer chose a hand-rolled router instead).
- Markdown: examples `commonmark-app/src/app/commonmark.clj` is a dependency-free renderer to vendor (needs hardening: escape raw HTML, allow-list link schemes). markdown-clj needs shims and passes raw HTML.

## ring-chez-adapter facts (v0.8.0)
- Cloning with `-b v0.8.0` warns the tag isn't a commit but checks out 87e637e — fine.
- `:strategy :threads` (default): fixed pool of `:worker-threads` (= core count); each open SSE stream / idle keep-alive pins a worker, so a few open pages exhaust it. `:fibers` = go block per connection on a poll thread; use it for SSE-heavy apps.
- Other opts: `:host` (default loopback), `:keep-alive-timeout-ms` 30000, `:max-request-bytes` 1 MiB, `:max-header-bytes` 8192, `:trust-proxy N` (peer addr kept at `:ring-chez/peer-addr`), `:remote-addr` from accept().
- Channel body = chunked stream; on client disconnect the channel is closed, so `sse/send!` / `>!!` returns false — use that to drop dead subscribers. Middleware ns: `ring-chez.middleware.{gzip,static,proxy,multipart}`. README "Streaming responses"/"Server-Sent Events" sections (~line 505) document this.

## Testing with cognitect test-runner
- Add `io.github.cognitect-labs/test-runner` (git tag v0.5.1, full sha) under the `:test` alias `:extra-deps` + `:extra-paths ["test"]`; run `jolt -M:test -m cognitect.test-runner [-n ns]` (wrap in `bin/test`). It finds `test/**/*_test.clj`, so no hand-written `-main`. Check the real exit status (`${PIPESTATUS[0]}`), not `| tail`.
- A test requiring a missing ns fails with "Could not locate x/y.jolt (or .clj/.cljc)" — that is the TDD red step.
- The server entry is `bin/run` = `jolt -m <app>.main "$@"`; block with `(jolt.host/block-sigint)` + `(jolt.host/park-until-interrupt)`, then stop cleanly. In smoke tests stop the server with a plain `kill <pid>`; backgrounding servers inside one bash tool call can hang the call, so start it, then inspect in a separate call.

## SQLite via jdbc.core (verified)
- `jdbc/connection "sqlite:<path>"`; PRAGMAs via `jdbc/execute!` (journal_mode=WAL, foreign_keys=ON, busy_timeout=5000) work per connection; `fetch-one` on `PRAGMA busy_timeout` returns key `:timeout`.
- `jdbc/atomic-apply conn (fn [tx] ...)` is the function-style transaction (rolls back on throw); `jdbc/atomic` is the body macro. `locking` on an Object works across futures.
- `VACUUM INTO ?` accepts a sqlvec param (pre-migration backups). `PRAGMA user_version = N` cannot take a param, so build the string.
- Gotchas: `(into [:map ...])` with one arg throws ArityException in Jolt (no 1-arity `into`); just use the vector. When smoke-testing static handlers for path traversal, pass the path-as-is flag to the HTTP client, since clients normalize `/js/../x`; the handler should use a regex allow-list on flat file names.
- malli works (`m/explain`, `me/humanize`, `:multi` with `:dispatch`); tools.cli `parse-opts` works.

## Time zones and IP parsing quirks (tick on Jolt)
- `tick.core/zone` / `t/in` accept any syntactically valid region ("Nope/Zone"); `(t/zone "")` throws IndexOutOfBounds, `"../../etc"` throws. Validate user-supplied zones with `(.contains (ZoneId/getAvailableZoneIds) id)`.
- `(t/instant ms)` works for epoch ms, not for LocalDate. Format: `(t/format (t/formatter "EEE d MMM yyyy, HH:mm") (t/in (t/instant ms) "UTC"))`.
- `InetAddress/getByName` performs DNS on non-literals; gate with an IP-literal regex before using it for CIDR checks. `.getAddress` returns bytes (as signed? compare via vec/bit-and with 0xff masks).
- Middleware ordering: when adding an Origin/CSRF check or auth middleware, existing handler tests that POST without Origin start returning 403; update them to send a matching Origin.
- Spiking: write a scratch file to /tmp and run `jolt -M -e '(load-file "/tmp/spike.clj")'` to probe library behavior quickly before writing the tests.
- Don't leave the app's runtime files (chequer.db*, *.ini) staged: smoke-test with a config under /tmp and kill the server afterwards (`pkill -f`; verify with pgrep).

## Verification
`bin/test` (or `jolt test` on older scaffolds) — expect "0 failures, 0 errors" and exit 0.
