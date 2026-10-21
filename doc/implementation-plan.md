# Chequer Implementation Plan

Every phase delivers a vertical slice: schema, domain logic, routes, UI and tests. At the end of each phase,
`jolt serve` runs, `jolt test` passes, and the new feature works in a browser. A phase depends only on the
phases before it.


## Foundations

These conventions apply to every phase.

**Namespace layout:**
- `chequer.main` holds `-main` and the system start and stop.
- `chequer.config` parses the ini file.
- `chequer.db` handles connections, `with-tx` and migrations.
- `chequer.migrations` holds the numbered migrations.
- `chequer.time` covers clocks and formatting.
- `chequer.order` does order arithmetic.
- `chequer.event` writes and reads the audit log.
- `chequer.auth` covers auth modes and middleware.
- `chequer.router` is a hand-rolled router.
- `chequer.web.layout`, `chequer.web.plans` and `chequer.web.lists` hold page and fragment rendering.
- `chequer.broker` tracks SSE subscriptions.
- `chequer.markdown` is a vendored, hardened renderer.
- `chequer.data.plans`, `chequer.data.lists` and `chequer.data.add-plan` hold the domain logic.
- `chequer.search` and `chequer.oidc` land in later phases.
- Tests mirror these as `test/chequer/*_test.clj`, run by one `chequer.test-runner`.

**Mutation model:**
- Datastar `@post`/`@put`/`@delete` handlers validate the input, then run `with-tx`, then return `204`.
- `with-tx` returns the set of topics the transaction touched, e.g. `#{[:plan 7] [:lists]}`.
- After commit, the broker re-renders the affected fragments and pushes them to every subscribed SSE stream,
  including the actor's own.
- Fragments have stable ids: `plan-7`, `plan-group-3`, `plan-task-42`, `list-9`, `list-group-5`, `task-88`.
- Validation errors come back directly as a `datastar-patch-elements` SSE response that targets the form's
  error element. The status is still 200, because Datastar applies SSE responses.

**DB access:**
- There is one writer connection, guarded by a lock (`locking` on a sentinel), plus a small pool of read
  connections. All connections are WAL with `foreign_keys=ON` and `busy_timeout=5000`.
- `(with-tx [tx db] body)` binds `chequer.db/*topics*` (an atom) so that domain code can call `(touch! [:plan id])`.
  It returns `{:result r :topics #{...}}`.
- `(q db sqlvec)` and `(q1 db sqlvec)` run reads through the pool.
- Queries use plain sqlvecs, not honeysql, because there's one less dependency to make work on Jolt.
- Rows are converted to namespaced keywords (`:plan/title`) by a `row->entity` function for each table.

**Server:**
- Routing: `(router/match routes method path)` splits the path into segments and matches route vectors like
  `[:get ["plans" :id]]` and `[:post ["plans" :id "tasks"]]`. It returns `{:handler f :params {:id "7"}}` or
  nil. Ids are parsed with `parse-long`, and anything non-numeric gets a 404.

**Time:**
- `chequer.time/now-ms` is a dynamic var so tests can pin the clock.
- `format-instant [ms tz]` uses jolt-lang/time.
- The viewer's time zone comes from a `tz` cookie, set by an inline script in the layout from
  `Intl.DateTimeFormat().resolvedOptions().timeZone`. If that's missing, the configured `time-zone` is used.

**Libraries added over time:**
- `jolt-lang/db` and `io.github.jolt-lang/time` come in Phase 1.
- `clojure.data.json` comes in Phase 3, for Datastar signal bodies.
- `jolt-lang/http-client` and `jolt-lang/jolt-crypto` come in Phase 9 only.
- The markdown renderer is vendored from the examples repo's `commonmark-app`.
- Every git dependency gets a full `:git/sha` pin.

**Testing conventions:**
- Each test gets a fresh temp SQLite file, using a `with-test-db` fixture that runs every migration.
- Handlers are tested as ring maps, with no network involved.
- Broker tests use a fake channel that collects pushed strings.
- The clock is pinned with `binding [time/*now-ms* (constantly 1000)]`.

**Security:**
- Escaping: all user text goes through hiccup2's escaping. Markdown is passed through `h/raw` only after the
  hardened renderer has processed it.

## Phase 0: Scaffold (done)

**Goal:** this phase already exists. There's a ring-chez-adapter server in
`chequer.core`, a Datastar demo route at `/hello`, `wrap-request-log`, the
`PORT` env variable, vendored `resources/public/js/datastar.js`, and `jolt
test`/`jolt serve` tasks.

## Phase 1: Config, database and migrations

**Goal:** the app starts from an ini file, opens and migrates SQLite, and
serves `/` (placeholder shell) and `/healthz`.

### Task 1.1: Ini parser

**Files:** create `src/chequer/config.clj` and `test/chequer/config_test.clj`.
- `(parse-ini s)` returns a map like `{"server" {"listen" "127.0.0.1:3000"}
  "auth.single" {...}}`. It ignores `;`/`#` comments and blank lines, and trims
  keys and values.
- `(load-config path)` merges the parsed file over defaults and returns a normalized map:

  ```clojure
  {:server {:listen {:host "127.0.0.1"
                     :port 3000}
            :base-url "http://127.0.0.1:3000"
            :time-zone "UTC"}
   :database {:path "chequer.db"}
   :auth {:mode :single
          :single {:name "owner"
                   :title "Owner"}
          :proxy {:header nil
                  :trusted-proxies ["127.0.0.1" "::1"]}
          :oidc {:issuer nil
                 :client-id nil
                 :client-secret nil
                 :scopes "openid profile email"
                 :session-ttl-ms 1209600000}}}
  ```
- `(validate-config cfg)` throws `ex-info` with a list of problems. It checks
  for an unknown mode, a missing proxy header in proxy mode, and missing OIDC
  keys in oidc mode.
- The config path comes from the first CLI argument, falling back to
  `CHEQUER_CONFIG` and then `chequer.ini`. The `PORT` env variable is removed.

**Tests:**
Comments and sections parse; `listen` handles `:3000` and `0.0.0.0:8080`;
`session-ttl` accepts `14d`, `12h` and `30m`; an invalid mode throws.

### Task 1.2: Connections and `with-tx`

**Files:** create `src/chequer/db.clj` and `test/chequer/db_test.clj`.
- `(open path)` returns `{:writer conn :lock (Object.) :readers (atom [conn...]) :path path}` and runs the
  pragmas on each connection. If `Object.` isn't shimmed in Jolt, the lock uses a `ReentrantLock`-style shim
  or a core.async mutex channel.
- `(with-tx [tx db] & body)` is a macro. It takes the lock, uses `jdbc/atomic` on the writer, binds
  `*topics*`, and returns `{:result .. :topics ..}`.
- `(touch! topic)` adds to `*topics*`.
- `(insert! tx table row)` uses `{:returning true}` and returns the id.
- `(q db sqlvec)` and `(q1 db sqlvec)` take a reader. The pool is a simple checkout from an atom, with the
  writer used as a fallback.

**Tests:**
An exception inside `with-tx` rolls back; topics are returned; a reader sees committed rows.

### Task 1.3: Migrations

**Files:** create `src/chequer/migrations.clj` and add `migrate!` to `src/chequer/db.clj`.
- `migrations` is a vector of `{:version n :sql [stmt...]}` compiled into the binary.
- `(migrate! db)` reads `PRAGMA user_version`. If migrations are pending and the DB file isn't empty, it
  first runs `VACUUM INTO '<path>.pre-v<N>.bak'` (refusing to overwrite). Then it runs every pending
  statement plus `PRAGMA user_version = N` in one transaction.
- Migration 001 creates `user` and `event` (Phase 2 uses them, but the event log is foundational):

  ```sql
  CREATE TABLE user (id INTEGER PRIMARY KEY, subject TEXT NOT NULL UNIQUE, name TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL);
  CREATE TABLE event (id INTEGER PRIMARY KEY, instant INTEGER NOT NULL, user_id INTEGER NOT NULL REFERENCES
  user(id),
    entity_type TEXT NOT NULL, entity_id INTEGER NOT NULL, plan_id INTEGER, list_id INTEGER,
    action TEXT NOT NULL, data TEXT, message TEXT);
  CREATE INDEX event_plan ON event(plan_id, instant) WHERE plan_id IS NOT NULL;
  CREATE INDEX event_list ON event(list_id, instant) WHERE list_id IS NOT NULL;
  CREATE INDEX event_entity ON event(entity_type, entity_id, instant);
  ```

**Tests:**
A fresh DB ends at the latest version; re-running is a no-op; a backup file is
created when upgrading a non-empty DB; a failing migration leaves
`user_version` unchanged.

### Task 1.4: System wiring, router and health check

**Files:** modify `src/chequer/core.clj`; create `src/chequer/router.clj` and `test/chequer/router_test.clj`.
- `(start! cfg)` opens and migrates the DB, builds the handler and calls `run-server` with `:host`/`:port`
  from `listen`. It returns `{:server .. :db .. :cfg ..}`. `(stop! sys)` reverses that.
- The handler is `(wrap-request-log (wrap-system (router/handler routes) sys))`. `wrap-system` puts
  `:chequer/db` and `:chequer/cfg` on the request.
- The demo routes `/hello` and the `case` handler are removed. Static files are served from `/js/*` and
  `/css/*` (a new `resources/public/css/app.css`) with a fixed allow-list of content types.
- `GET /healthz` returns `200 ok` after `SELECT 1`.

**Tests:**
Route matching and params; 404 for unknown paths and non-numeric ids; 405 for a known path with the
wrong method; `/healthz` returns 200.


## Phase 2: Users, auth modes and the app shell

**Goal:** each request is tied to a user, in single or proxy mode. A styled layout shows the current user, and the
home page is a placeholder for lists.

### Task 2.1: User provisioning

**Files:** create `src/chequer/users.clj` and `test/chequer/users_test.clj`.
- `(ensure-user! db {:subject s :name n :title t})` looks up by subject. If the user doesn't exist, it
  inserts them. If they do, it refreshes name and title. If the name collides with another user, a suffix is
  appended (`greg`, `greg2`, `greg3`...), using the first free suffix.
- `(touch-last-seen! db user)` only writes if `last_seen` is more than 5 minutes old. That's the "fuzzy"
  tracking from the design.

**Tests:**
The first call inserts; the second refreshes the title; a name collision gives
`name2`; the last-seen throttle works.

### Task 2.2: Auth middleware

**Files:** create `src/chequer/auth.clj` and `test/chequer/auth_test.clj`.
`(wrap-auth handler)` dispatches on `(get-in cfg [:auth :mode])`:
- `:single` calls `ensure-user!` with subject `owner` and the configured name and title. The owner id is
  cached in an atom after the first request.
- `:proxy` uses `(trusted? remote-addr cidrs)` with IPv4/IPv6 CIDR matching. An untrusted source or a
  missing header gets a 403 text response. Otherwise the user is provisioned with subject and name set to
  the header value and the title also set to the header value.
- The user is put on the request as `:chequer/user`. `/healthz` and static paths bypass auth.

**Tests:**
Single mode always resolves the owner; proxy mode trusts loopback; an untrusted
IP gets 403 even with the header; a missing header gets 403; CIDR `10.0.0.0/8`
matches `10.2.3.4`.

### Task 2.3: Origin check (CSRF)

**Files:** add `wrap-origin-check` to `src/chequer/auth.clj`; tests go in `auth_test.clj`.
- For anything other than GET and HEAD: allow if the `Origin` header equals the
  base-url origin (scheme, host and port). If `Origin` is missing, allow only
  when `Sec-Fetch-Site` is `same-origin`. Otherwise return 403.

**Tests:**
A matching origin is allowed; a foreign origin is blocked; no Origin plus
same-origin is allowed; no Origin and no Sec-Fetch-Site is blocked.

### Task 2.4: Layout and time zone

**Files:** create `src/chequer/web/layout.clj`, `src/chequer/time.clj` and `test/chequer/time_test.clj`; modify
`resources/public/css/app.css`.
- `(page req title & body)` renders the doctype, head (Datastar module and
  CSS), an inline tz-cookie script that only sets the cookie if it differs, a
  nav with Lists, Plans, Search and the user title, then `[:main body]`.
- `(viewer-tz req)` reads the `tz` cookie, checks it against jolt-lang/time
  zone ids, and falls back to the config value.
- `(format-instant ms tz)` gives "Mon 3 Mar 2025, 14:05". `(relative ms now)`
  gives "in 2 days" or "3 hours ago" for due dates.
- `GET /` renders an empty-state Lists page.

**Tests:**
Formatting in UTC and in `America/New_York`; an invalid cookie falls back to the config.


## Phase 3: Plans, groups, plan tasks and a minimal live broker

**Goal:** full CRUD for plans, groups and plan tasks, with markdown descriptions and up/down reordering. Every
change is logged as an event and pushed live to other open plan pages.

### Task 3.1: Schema (migration 002)

**Files:** modify `src/chequer/migrations.clj`.
  CREATE TABLE plan (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT, icon TEXT,
    created_at INTEGER NOT NULL, created_by INTEGER NOT NULL REFERENCES user(id),
    updated_at INTEGER NOT NULL, updated_by INTEGER NOT NULL REFERENCES user(id));
  CREATE TABLE plan_group (id INTEGER PRIMARY KEY, plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE
  CASCADE,
    "order" REAL NOT NULL, title TEXT NOT NULL, icon TEXT);
  CREATE TABLE plan_task (id INTEGER PRIMARY KEY, plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE
  CASCADE,
    group_id INTEGER REFERENCES plan_group(id) ON DELETE CASCADE, "order" REAL NOT NULL,
    title TEXT NOT NULL, description TEXT, icon TEXT, estimate INTEGER,
    created_at INTEGER NOT NULL, created_by INTEGER NOT NULL REFERENCES user(id),
    updated_at INTEGER NOT NULL, updated_by INTEGER NOT NULL REFERENCES user(id));
  CREATE INDEX plan_group_plan ON plan_group(plan_id, "order");
  CREATE INDEX plan_task_plan ON plan_task(plan_id, group_id, "order");
  CREATE TRIGGER plan_task_group_same_plan BEFORE INSERT ON plan_task WHEN NEW.group_id IS NOT NULL
    AND (SELECT plan_id FROM plan_group WHERE id = NEW.group_id) <> NEW.plan_id
    BEGIN SELECT RAISE(ABORT, 'group belongs to another plan'); END;

There is a matching `BEFORE UPDATE OF group_id` trigger as well.

### Task 3.2: Order arithmetic

**Files:** create `src/chequer/order.clj` and `test/chequer/order_test.clj`.
- `(append-order orders)` returns `(inc (reduce max 0 orders))`.
- `(between a b)` returns the midpoint. When `a` or `b` is nil, it's treated as ±1 from the neighbour.
- `(needs-renumber? a b)` is true when `(- b a)` is less than `1e-9`.
- `(move tx {:table t :scope-sql [...] :id id :direction :up|:down})` finds the
  neighbour two steps away and places the row at the midpoint, renumbering the
  siblings `1..n` first when needed.

**Tests:**
Append; midpoint; renumbering triggers after about 50 repeated midpoints;
moving the first row up is a no-op.

### Task 3.3: Event writer

**Files:** create `src/chequer/event.clj` and `test/chequer/event_test.clj`.
- `(record! tx {:user u :entity-type "plan.task" :entity-id id :plan pid :list nil :action :update :data {...} :message "..."})`
  writes `data` with `pr-str` and the action as `(name action)`.
- `(diff old new ks)` returns `{:title [old new] ...}` for changed keys only.

**Tests:**
A round trip with `read-string` on the data; diff skips unchanged keys.

### Task 3.4: Plan domain

**Files:** create `src/chequer/domain/plans.clj` and `test/chequer/domain/plans_test.clj`.
- `create-plan!`, `update-plan!`, `delete-plan!`, `create-group!`,
  `update-group!`, `delete-group!`, `move-group!`, `create-plan-task!`,
  `update-plan-task!`, `delete-plan-task!` and `move-plan-task!` all take `[tx user attrs]`.
  Each one writes its event(s) and calls `touch!` with `[:plan pid]`, and also
  `[:plans]` for plan-level changes.
- Deletes record a `:delete` event with `{:title t}` before deleting. Deleting a group also records a `:delete` event for each task in it.
- `(validate-title s)` trims, rejects blank, rejects newlines and caps at 200
  characters. `(validate-icon s)` allows blank or one grapheme cluster,
  approximated as at most 8 code points with no ASCII letters or digits.
  `(validate-estimate s)` accepts blank or a non-negative integer.
- `(load-plan db id)` returns `{:plan p :ungrouped [tasks] :groups [{:group g :tasks [...]}]}` in display order.

**Tests:**
Create, update and delete each write the right events; a cascade removes the
tasks; a cross-plan group is rejected; display order puts ungrouped tasks
first.

### Task 3.5: Markdown renderer

**Files:** create `src/chequer/markdown.clj` (vendored from the examples `commonmark-app`) and
`test/chequer/markdown_test.clj`.
- The hardening: raw HTML blocks and inline HTML are emitted as escaped text;
  link and image URLs are allow-listed to `http`, `https`, `mailto` or
  relative, and anything else becomes `#`; every attribute is escaped.
- `(render s)` returns a string to wrap in `h/raw`.

**Tests:**
`<script>` is escaped; a `javascript:` link is neutralized; emphasis, lists and code still render.

### Task 3.6: Minimal broker and SSE endpoint

**Files:** create `src/chequer/broker.clj` and `test/chequer/broker_test.clj`.
- `(subscribe! broker topic ch meta)` and `(unsubscribe! broker ch)` work over `broker = (atom {topic #{{:ch ch :meta m}}})`.
- `(publish! broker topics render-fn)`: for each subscriber of each topic it
  calls `(render-fn topic meta)`, which returns a sequence of SSE event
  strings, and pushes them with `>!!`. If the push returns false, the
  subscriber is unsubscribed.
- `GET /plans/:id/stream` creates a channel, subscribes it to `[:plan id]` with
  `{:user .. :tz ..}`, and returns `sse/event-response`.
- In Phase 3 the render function re-renders the whole `#plan-7` body fragment.
  Finer-grained patching waits until Phase 7, when it can be measured.
- A `wrap-publish` helper runs after each mutating handler. It takes `:topics`
  from the handler's response metadata and calls `publish!`.

**Tests:**
A fake channel receives the fragment; a closed channel is removed; unrelated topics get nothing.

### Task 3.7: Plan pages and handlers

**Files:** create `src/chequer/web/plans.clj` and `test/chequer/web/plans_test.clj`; add the routes in
`src/chequer/core.clj`.
Pages:
- `GET /plans` is the index page.
- `GET /plans/new` shows the form, and `POST /plans` creates the plan and redirects with a 303 to `/plans/:id`.
- `GET /plans/:id` shows the plan. Its body has `data-on-load="@get('/plans/7/stream')"`.

**Mutations:**
These all read Datastar signals with `clojure.data.json` and return 204 plus the touched topics.
- Plans: `PUT /plans/:id` and `DELETE /plans/:id`. Deleting a plan pushes `datastar-execute-script` to redirect to `/plans`.
- Groups: `POST /plans/:id/groups`, `PUT /plan-groups/:id`, `DELETE /plan-groups/:id` and `POST /plan-groups/:id/move?dir=up|down`.
- Tasks: `POST /plans/:id/tasks`, `PUT /plan-tasks/:id`, `DELETE /plan-tasks/:id` and `POST /plan-tasks/:id/move?dir=up|down`.

**Rendering:**
- `(render-plan db plan-id viewer)` builds `#plan-7`.
- `(render-plan-task t)` builds `#plan-task-42`. It has the title row, a
  `<details>` element with the markdown description, and edit, up, down and
  delete buttons.
- Edit forms are inline `data-show` toggles bound to local signals.

**Tests:**
- `POST /plans` gets a 303 and the row exists.
- An invalid title returns an error fragment.
- A stream subscriber receives a re-rendered `#plan-7` after a `PUT`.
- A description containing `<script>` comes out escaped in the HTML.

**Done when:** you can create, edit, reorder and delete plans, groups and tasks
in two browser tabs, and both tabs stay in sync.


## Phase 4: Lists and tasks

**Goal:** you can create blank lists, add and manage tasks with state changes, set due dates and assignees, and
archive lists. The home page shows the active lists, and everything updates live.
### Task 4.1: Schema (migration 003)

**Files:** modify `src/chequer/migrations.clj`.
  CREATE TABLE list (id INTEGER PRIMARY KEY, title TEXT NOT NULL, description TEXT, icon TEXT,
    due_at INTEGER, archived_at INTEGER,
    created_at INTEGER NOT NULL, created_by INTEGER NOT NULL REFERENCES user(id),
    updated_at INTEGER NOT NULL, updated_by INTEGER NOT NULL REFERENCES user(id));
  CREATE TABLE list_group (id INTEGER PRIMARY KEY, list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE
  CASCADE,
    source_id INTEGER REFERENCES plan_group(id) ON DELETE SET NULL,
    "order" REAL NOT NULL, title TEXT NOT NULL, icon TEXT);
  CREATE TABLE task (id INTEGER PRIMARY KEY, list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
    group_id INTEGER REFERENCES list_group(id) ON DELETE CASCADE,
    source_id INTEGER REFERENCES plan_task(id) ON DELETE SET NULL,
    "order" REAL NOT NULL, title TEXT NOT NULL, description TEXT, icon TEXT, estimate INTEGER, due_at INTEGER,
    assignee_id INTEGER REFERENCES user(id),
    state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','working','completed','abandoned')),
    created_at INTEGER NOT NULL, created_by INTEGER NOT NULL REFERENCES user(id),
    updated_at INTEGER NOT NULL, updated_by INTEGER NOT NULL REFERENCES user(id),
    started_at INTEGER, ended_at INTEGER, ended_by INTEGER REFERENCES user(id));
  CREATE INDEX list_active ON list(archived_at, due_at);
  CREATE INDEX list_group_list ON list_group(list_id, "order");
  CREATE INDEX task_list ON task(list_id, group_id, "order");
  CREATE INDEX task_assignee ON task(assignee_id) WHERE assignee_id IS NOT NULL;

The migration also adds same-list group triggers, on insert and on update of `group_id`, mirroring the
plan-side triggers from 002.
### Task 4.2: List and task domain

**Files:**
Create `src/chequer/domain/lists.clj` and `test/chequer/domain/lists_test.clj`.
- CRUD functions: `create-list!`, `update-list!`, `delete-list!`,
  `archive-list!`, `unarchive-list!`, the group CRUD and move functions, and
  `create-task!`, `update-task!`, `delete-task!`, `move-task!`, all with the
  signature `[tx user attrs]`.
- Events and topics: every write records an event with `:list` set and touches
  `[:list id]`. List-level changes also touch `[:lists]`.
- State changes: `(state-transition task new-state user now)` is a pure
  function that returns the changed columns according to the design's rules.
  `set-state!` applies them. The event action is `:complete` or `:abandon` for
  those two states, and `:update` with `{:state [old new]}` otherwise.

**Derived values:**
- `(list-done? tasks)` is true when there is at least one task and none of them are pending or working.
- `(overdue? task now)` is true when the task is past `due_at` and still pending or working.
- `(list-summary db)` returns rows for the active and archived list indexes, each with counts by state, done status and next due time.

**Tests:**
- All four transitions, each from every state, including undo back to pending.
- The first time a task moves to working sets `started_at`, and moving it to working again leaves `started_at` alone.
- Done is false for an empty list.
- Archiving and unarchiving clear `archived_at` correctly.
- Deleting a group cascades to its tasks and records a delete event for each task.

### Task 4.3: List pages

**Files:**
Create `src/chequer/web/lists.clj` and `test/chequer/web/lists_test.clj`; add a
`[:list id]`/`[:lists]` renderer to the broker.

**Pages:**
- `GET /` shows the active lists, sorted with overdue lists first, then by due
  date, then by most recently updated. A "Show archived" link goes to `GET
  /lists?archived=1`.
- `GET /lists/new` shows the form, and `POST /lists` creates the list and redirects with a 303.
- `GET /lists/:id` shows the list, with `@get('/lists/9/stream')`.

**Mutations:**
- Lists: `PUT /lists/:id`, `DELETE /lists/:id`, `POST /lists/:id/archive` and `POST /lists/:id/unarchive`.
- Groups: `POST /lists/:id/groups`, `PUT /list-groups/:id`, `DELETE /list-groups/:id` and `POST /list-groups/:id/move`.
- Tasks: `POST /lists/:id/tasks`, `PUT /tasks/:id`, `DELETE /tasks/:id`, `POST /tasks/:id/move` and `POST /tasks/:id/state?to=completed`.

**Rendering:**
- `#task-88` has a checkbox that toggles between pending and completed, a menu
  for working and abandoned, and shows the assignee, the due date (relative, in
  the viewer's time zone) and an "overdue" badge.
- Deleting a group shows a `confirm()` with the number of tasks that will be
  removed. The count comes from a `data-task-count` attribute.
- An "Assigned to me" toggle uses the `?mine=1` query parameter. The filter
  runs on the server, and SSE re-renders respect the `:mine` flag stored with
  each subscription.

**Due-date input:** an `<input type="datetime-local">` value is interpreted in the viewer's time zone and
converted to UTC milliseconds with jolt-lang/time.

**Tests:**
  • Checking a task pushes the updated `#task-88` and `#list-9` header to the other stream.
  • Due-date input in `America/New_York` is stored as the correct UTC value.
  • The `?mine=1` view hides other users' tasks.

**Done when:** you can create a list, check tasks off, undo a check, archive
and unarchive the list, and see it all live in two tabs.


## Phase 5: Adding plans to lists

**Goal:** you can create a list from one or more plans, and add plans to an existing list, using the design's
merge rules.

### Task 5.1: Merge algorithm

**Files:**
Create `src/chequer/domain/add_plan.clj` and `test/chequer/domain/add_plan_test.clj`.
- `(normalize-title s)` trims and lower-cases the title.

`(add-plan! tx user list-id plan-id)` does the following:
 1. Loads the plan's display tree.
 2. Matches or creates a list group for each plan group. New groups are appended, and `source_id` is set to
    the plan group.
 3. For each task, skips it if the destination already has a task with the same normalized title. Otherwise it
    appends a pending copy with `source_id` set to the plan task.
 4. Records `:create` events for every new row, plus one `:add-plan` event on the list with `{:plan-id ..
    :plan-title .. :groups-created n :tasks-created n :tasks-skipped n}`.
 5. Returns those counts.

`(add-plans! tx user list-id plan-ids)` runs `add-plan!` for each plan, in the
given order, inside the caller's transaction.

**Tests:**
- Adding a plan to an empty list copies it exactly.
- Adding the same plan twice does nothing the second time.
- Two plans that share a group title merge into one group.
- A duplicate title in a different group is not skipped.
- Copying is a snapshot: editing the plan afterwards doesn't change the list.
- Deleting the source plan task sets `source_id` to null.
- An exception partway through rolls everything back.

### Task 5.2: UI

**Files:**
Modify `src/chequer/web/lists.clj` and `src/chequer/web/plans.clj`.
- The new-list form gets a plan picker: checkboxes plus up and down controls to
  set the order. The chosen plans are submitted as `plan_ids[]` in order. `POST
  /lists` creates the list and then calls `add-plans!` in the same transaction.
- `POST /lists/:id/add-plans` adds plans to an existing list. Afterwards, a
  flash fragment shows something like "Added Packing: 12 tasks, 3 skipped".
- The plan page gets a "Start a list from this plan" button that pre-selects the plan.

**Tests:**
The form round-trips; the flash counts are correct; other viewers get a live update.

**Done when:** you can start a list from two plans and see the groups merge correctly.


## Phase 6: History

**Goal:** plans, lists and individual tasks each have a readable history view.

### Task 6.1: History queries and rendering

**Files:**
Extend `src/chequer/event.clj`; create `src/chequer/web/history.clj` and `test/chequer/web/history_test.clj`.
- `(plan-history db plan-id {:before id :limit 50})` and `(list-history db
  list-id opts)` use the context indexes. `(entity-history db type id opts)`
  uses the `event_entity` index. All three join `user` to get display titles
  and use keyset pagination on `id DESC`.
- `(describe-event e)` turns an event into a sentence like "greg completed Pack
  charger" or "greg renamed group Kitchen → Cooking". For deleted entities it
  uses the title stored in `data`, and if no case matches it falls back to
  `message`.
- Routes: `GET /plans/:id/history`, `GET /lists/:id/history` and `GET
  /tasks/:id/history`. Timestamps are shown in the viewer's time zone, and a
  "Load more" button uses `@get` to append the next page.

**Tests:**
History for a deleted task still renders its title; pagination works; events
from other plans don't appear.

**Done when:** each plan and list page has a working History link.


## Phase 7: Live-update hardening

**Goal:** many simultaneous open pages work reliably.

### Task 7.1: Server strategy and heartbeats

**Files:**
Modify `src/chequer/core.clj` and `src/chequer/broker.clj`.
- Start the server with `:strategy :fibers`.
- A broker heartbeat loop sends an SSE comment (`: ping`) to every subscriber
  every 25 seconds and drops any channel where the send fails.
- `(broker/stats)` returns stream counts per topic and is shown on `/healthz` as `streams=N`.

**Tests:**
A heartbeat reaches the fake channel; a dead channel is removed.

### Task 7.2: Finer-grained fragments

**Files:**
Modify `src/chequer/web/plans.clj`, `src/chequer/web/lists.clj` and `src/chequer/broker.clj`.
- Topics get more specific, e.g. `[:list 9 :task 88]` and `[:list 9
  :structure]`. A change to a single task pushes only `#task-88` and the list
  header. Pushing the whole page fragment is limited to structural changes:
  adding, removing or moving rows, and group edits.
- The render cache is keyed by `[topic viewer-tz mine?]`, so each distinct
  render happens only once per publish.

**Tests:**
A state change sends exactly two fragments.

### Task 7.3: Load test

**Files:**
Create `test/chequer/load_test.clj`. It's excluded from the default run and runs with `jolt -M:test load`.
- The test opens 50 SSE streams against a real server on a random port,
  performs 100 mutations, and checks that every stream receives every update
  within 2 seconds. It also checks that request latency stays under 200 ms at
  p95.

**Done when:** the load test passes locally, and memory use is stable after the streams close.


## Phase 8: Search

**Goal:** a single search box finds plans, plan tasks, lists and tasks.

### Task 8.1: FTS index (migration 004)

**Files:**
Modify `src/chequer/migrations.clj`.

```sql
CREATE VIRTUAL TABLE search USING fts5(title, description, kind UNINDEXED, entity_id UNINDEXED,
  owner_kind UNINDEXED, owner_id UNINDEXED, tokenize='unicode61 remove_diacritics 2');
```

- Triggers on insert, update and delete for `plan`, `plan_task`, `list` and
  `task` keep this table in sync, using delete-then-insert by `(kind,
  entity_id)`.
- The migration ends with a backfill `INSERT ... SELECT` from all four tables.

**Tests:**
An inserted row can be found; updating a title replaces it in the index;
deleting it removes it; the backfill indexes existing data.

### Task 8.2: Search page

**Files:**
Create `src/chequer/search.clj`, `src/chequer/web/search.clj` and `test/chequer/search_test.clj`.
- `(fts-query s)` quotes each word of the user's input and adds a `*` suffix to
  the last one, so FTS operators in the input can't break the query.
- `(search db s {:limit 50})` ranks results by `bm25(search)`. Results whose
  owner list is archived get a     penalty in the `ORDER BY`, e.g. `rank + CASE
  WHEN archived THEN 1000 ELSE 0 END`. It returns rows with a snippet, built
  with `snippet(search, ...)` and escaped before the highlight `<mark>` tags
  are added.
- `GET /search?q=` gives a page with a nav input that searches as you type
  (`data-on-input__debounce.250ms="@get('/search/results')"`). Results link to
  `/plans/:id#plan-task-42` or `/lists/:id#task-88` and carry an "archived"
  label where it applies.

**Tests:**
- Prefix matching works.
- Input like `"` or `NEAR(` doesn't cause an error.
- Archived results rank after active ones.
- Snippets can't inject HTML.


## Phase 9: OIDC

**Goal:** `mode = oidc` provides SSO login with server-side sessions.

### Task 9.1: Sessions (migration 005)

**Files:**
Modify `src/chequer/migrations.clj`; create `src/chequer/sessions.clj` and `test/chequer/sessions_test.clj`.

```sql
CREATE TABLE session (id TEXT PRIMARY KEY, user_id INTEGER NOT NULL REFERENCES user(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL);
CREATE INDEX session_expiry ON session(expires_at);
```

- `(create-session! db user-id ttl)` generates the id from 32 random bytes in base64url, using `jolt-crypto/random-bytes`.
- `(lookup db id now)` returns the session, or nil if it doesn't exist or has expired. `(delete! db id)` removes one session.
- A sweeper runs `DELETE WHERE expires_at < now` every hour.

**Tests:**
Expiry is enforced; the sweeper removes old rows.

### Task 9.2: Discovery and the login flow

**Files:**
Create `src/chequer/oidc.clj` and `test/chequer/oidc_test.clj`; add the `:oidc` branch to
`src/chequer/auth.clj`; add `jolt-lang/http-client` and `jolt-lang/jolt-crypto` to `deps.edn`.
- `(discover issuer)` fetches `/.well-known/openid-configuration` and caches it.

`GET /auth/login`:
- Generates `state`, `nonce` and a PKCE verifier, and computes the S256 challenge.
- Stores them in a short-lived signed cookie (HMAC with a server key generated
  at first start and kept in a `setting` table that migration 005 creates),
  valid for 10 minutes.
- Redirects to the authorization endpoint.

`GET /auth/callback`:
- Checks `state`, then exchanges the code using the client secret and verifier.
- Decodes the ID token's claims without verifying the signature (this is the
  agreed decision: the token arrives directly from the token endpoint over TLS)
  and validates `iss`, `aud`, `exp` and `nonce`.
- Provisions the user with subject `iss|sub`, name from `preferred_username`
  (falling back to `sub`) and title from `name` (falling back to the username).
- Creates the session and sets the cookie `HttpOnly; Secure; SameSite=Lax;
  Path=/`, then redirects to the original URL. Only same-origin paths are
  allowed as the original URL.

`POST /auth/logout`:
- deletes the session, clears the cookie and calls `(broker/close-session!
  session-id)`, which closes that session's streams. Subscriptions store
  `:session-id` in their metadata so this is possible.

Unauthenticated requests: in OIDC mode, a GET without a valid session redirects
to login. Other methods and SSE requests get a 401.

**Tests:**
These use a fake token endpoint (an injected `http-post` function) and cover:
- The happy path.
- A bad state, wrong `aud`, expired token and nonce mismatch are each rejected.
- An open redirect is blocked.
- Logout closes the fake stream.


## Phase 10: Packaging and operations

**Goal:** the app ships as a single binary and a container image, with documentation.

### Task 10.1: Binary build

**Files:**
Modify `deps.edn` (add a `build` task: `jolt build -m chequer.core -o target/chequer`);
create `chequer.example.ini`.
- Check: run `target/chequer chequer.example.ini` from a temp directory with no
  checkout present. The static assets must be served (they're embedded via
  `:jolt/build :embed`), and the migrations must run.

### Task 10.2: Container

**Files:**
Create `Containerfile`.
- The build is multi-stage: a Jolt build stage, then a minimal runtime stage with libsqlite3.
- Runtime settings: `/data` is a volume, the database path defaults to
  `/data/chequer.db`, the server listens on `0.0.0.0:3000`, and it runs as a
  non-root user. `HEALTHCHECK` calls `/healthz`.

### Task 10.3: Docs

**Files:**
Create `README.md` and `doc/operations.md`.
- They cover configuration reference, the three auth modes (with an example
  proxy setup), backups with `VACUUM INTO`/`.backup`, the migration backup
  files, and upgrading.


## Risks

- Jolt library gaps (jolt-lang/db pooling, `locking`, time-zone data): spike
  these in Task 1.2 and Task 2.4 before relying on them. The fallback is a
  core.async-channel mutex and the configured time zone only.
- SSE capacity under `:threads`: before Phase 7, only a few tabs can be open.
  Phase 7 switches to `:fibers`, and that should be pulled earlier if it hurts
  during development.
- Whole-fragment re-render cost on big lists: this is addressed in Task 7.2.
  Measure first.
- Markdown renderer hardening: this is security-sensitive, so the tests in Task
  3.5 have to cover the XSS vectors, and every renderer change needs review.
- FTS triggers and migrations: a trigger bug corrupts search silently. The
  tests in Task 8.1 cover each table, and a `rebuild-search!` admin function
  provides recovery.
- OIDC without signature verification: this is acceptable only because the
  token comes directly from the token endpoint over TLS. Add JWKS verification
  later if the ID token ever arrives through the browser.
- Floating-point order drift: handled by renumbering, as tested in Task 3.2.


## Decisions

- Mutations are broker-only: handlers return 204, and every viewer, including
  the actor, gets updates over SSE.
- There's a single writer connection behind a lock, plus a read pool. `with-tx`
  returns the touched topics.
- The router is hand-rolled, and queries use plain sqlvecs.
- Reordering in v1 is up/down buttons only, with no drag-and-drop.
- Adding plans writes one `:add-plan` event per plan, plus `:create` events for
  the rows it creates.
- The viewer's time zone comes from a cookie, and all formatting happens on the
  server.
- When the `Origin` header is missing, a request is accepted only if
  `Sec-Fetch-Site: same-origin` is present. Otherwise it gets a 403.
- OIDC uses the authorization code flow with PKCE S256. The ID token's claims
  (`iss`, `aud`, `exp`, `nonce`) are validated, but its signature isn't
  checked, because the token comes directly from the token endpoint over TLS.
- Server sessions exist only in oidc mode. The cookie holds only the session
  id. Logging out deletes the session row and closes that session's SSE
  streams.
- Config keys `listen` and `base-url` replace the `PORT` env variable. The
  config path comes from the CLI argument, then `CHEQUER_CONFIG`, then
  `chequer.ini`.
- Migrations are numbered and compiled into the binary: 001 users and events,
  002 plans, 003 lists, 004 search, 005 sessions and settings. Each upgrade
  first writes a backup with `VACUUM INTO`.
- Events are never foreign keys, and deletes are hard. A `:delete` event stores
  the entity's final title. • The markdown renderer is vendored from the
  examples repo and hardened: raw HTML is escaped and link schemes are
  allow-listed.
- Non-goals from the design stay out of scope for v1: comments, recurring
  lists, subtasks, multiple assignees, notifications and per-entity
  permissions.
