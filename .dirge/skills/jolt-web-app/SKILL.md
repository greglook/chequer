---
name: jolt-web-app
description: Build/test a Jolt (Clojure-on-Chez) web app with ring-chez-adapter, hiccup2 and Datastar SSE; covers deps.edn layout, test harness, serving, and quirks.
---

# Jolt web app (ring-chez-adapter + hiccup + Datastar)

## Learn idioms from examples, not by probing the binary
- Clone the jolt-lang/examples repo from GitHub (depth 1). Useful: `ring-app` (full stack, Integrant), `glimmer-datastar` (Datastar + SSE), `hiccup-app`.
- The ring-chez-adapter source (tag v0.8.0) is useful for API details. `src/ring_chez/sse.clj` has `event-response`, `format-event`, `send!`.
- Don't dump `all-ns` or scan the jolt binary for strings; the user rejected that.

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
- Pin full git SHAs. `jolt run` is built in, so the task is named `serve`.
- Git deps are cached in ~/.jolt/gitlibs. Add `.jolt/` to .gitignore.
- If `jolt test` says "Could not locate <ns> on the source roots", the test or source file doesn't exist yet. Create both before the first run.

## Tests
- Use clojure.test. The test namespace has a `-main` that calls `run-tests` and throws when fail+error > 0, so the exit status is non-zero on failure.
- Call the ring handler directly with `{:request-method :get :uri "/" :headers {}}`.
- For SSE responses, `:body` is a core.async channel. Read it with `async/<!!`. After the stream closes, `<!!` returns nil.
- The first run is slow because of the dep fetch and compile, so use a long timeout (about 900s).

## Server
- `(adapter/run-server handler {:port p})` binds 127.0.0.1 by default (`:host "0.0.0.0"` binds all interfaces) and serves on a background thread. Stop it with `adapter/stop-server`.
- Keep `-main` alive with `(jolt.host/block-sigint) (jolt.host/park-until-interrupt)`, then call stop-server.
- Static resources: `(slurp (io/resource "public/js/datastar.js"))` with an explicit Content-Type, or use ring-defaults like in ring-app.
- Smoke test: start the serve task in the background with a custom PORT env var, poll the root URL until it answers, then request the SSE route with output buffering disabled.

## Datastar
- Vendor the bundle at `resources/public/js/datastar.js`, taken from a starfederation/datastar tag's `bundles/datastar.js` (v1.0.4 is used here). Load it with `<script type="module">`.
- The patch event is `event: datastar-patch-elements` with `data: elements <html>`. Build it with `sse/format-event {:event ... :data (str "elements " html)}` on a channel, then `sse/event-response ch`.
- Attribute keywords like `:data-on:click` work in hiccup2.
- Gotcha: hiccup2 escapes `'` as `&apos;` in attributes, so assert `@get(&apos;/x&apos;)` in rendered HTML. The browser decodes it, so this is fine at runtime.
- Higher-level option: `jolt-lang/glimmer-datastar` (`jolt.datastar.core/wrap-datastar`, `init-opts`) for ratom-driven re-rendering.

## Verification
```
timeout 900 jolt test
```
