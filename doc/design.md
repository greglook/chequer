# Chequer Design

A high-level design document for Chequer.


## Architecture

Chequer is a simple web application that runs from a single binary and stores its data in a local SQLite database.

- **Language:** [Jolt](https://github.com/jolt-lang/jolt), a dialect of Clojure
- **Storage:** SQLite
- **Deployment:** Single self-contained binary, or via a container
- **Configuration:** An `ini` file
- **UI:** Server-rendered HTML using [Datastar](https://data-star.dev/), with
  updates delivered over server-sent events (SSE). There is no separate
  frontend build step.


## Data Model

There are two major groups of entities in Chequer, as it covers both _long-term
planning_ as well as _immediate task management_.

For long-term entities, users manage a collection of _plans_ which contain
_plan tasks_. These are patterns of tasks that are repeated over time, for
example a packing list, a set of tasks for cleaning a house, how to maintain
something, etc. These are not associated with any particular instant in time
and may be edited and updated over time. The tasks may be arranged into
groups within the plan.

Users don't actually check off the tasks in a plan; when they are ready to
work on something, the user creates a _list_ which contains reified _tasks_.
The user can create a blank list or pre-populate it with one or more plans,
which are merged together (see [Adding Plans](#adding-plans)).
After creation, the user may manually add more tasks, add more plans,
and edit or remove existing tasks.

Lists are _copies_: editing or deleting a plan after it has been added to a
list never changes that list.

### Conventions

- **Identifiers:** every entity has an opaque numeric `id` primary key.
- **Instants** are stored as UTC epoch milliseconds (`INTEGER`) and rendered in
  the viewer's time zone (reported by the browser, falling back to the
  configured default).
- **User references** (`created-by`, `updated-by`, etc.) are refs to
  `:user/id`, never to a user's mutable name.
- **Ordering:** `rank` fields are `REAL`. Appending uses `max + 1`; inserting
  between two siblings uses their midpoint, so a reorder updates a single row.
  If the gap between neighbours becomes too small to split, the sibling set is
  renumbered `1, 2, 3, ...` in one transaction.
- **Display order:** within a plan or list, ungrouped tasks are shown
  first (ordered among themselves), followed by the groups in order, each
  containing its tasks in order. Group order and ungrouped-task order are
  independent sequences.
- **Text:** titles are short single-line plain text. Descriptions support
  markdown and are sanitized when rendered to HTML.
- **Icons:** a single emoji.
- **Estimates:** integer minutes.


### Plan

A plan is a long-lived list of plan tasks which will be used
repeatedly over time to create lists to work off of.

Plans have the following properties:

- `:plan/id` (required) opaque unique numeric identifier for the plan in storage
- `:plan/title` (required) a short one-line plain-text title for the plan
- `:plan/description` long-form text with a detailed description of the plan, with support for markdown formatting
- `:plan/icon` a custom emoji icon to give the plan
- `:plan/created-at` (required) instant the plan was created
- `:plan/created-by` (required, ref) the user who created the plan
- `:plan/updated-at` (required) instant the plan was last modified
- `:plan/updated-by` (required, ref) the user who last modified the plan

Deleting a plan deletes its groups and plan tasks.


### Plan Group

A grouping of plan tasks within a plan.

Plan groups have the following properties:

- `:plan.group/id` (required) opaque unique numeric identifier for the group in storage
- `:plan.group/plan` (required, ref) the id of the plan this group belongs to
- `:plan.group/rank` (required) a numeric sort ordering for the group within the plan
- `:plan.group/title` (required) a short one-line plain-text title for the group
- `:plan.group/icon` a custom emoji icon to give the group

Deleting a group deletes the plan tasks in it.


### Plan Task

Plan tasks are prototypes for creating a live task in a list. The
information is used to pre-fill data in the created task.

Plan tasks have the following properties:

- `:plan.task/id` (required) opaque unique numeric identifier for the task in storage
- `:plan.task/plan` (required, ref) the id of the plan this task belongs to
- `:plan.task/group` (optional, ref) the id of the plan group this task is in, if any; must belong to the same plan
- `:plan.task/rank` (required) a numeric sort ordering for the task within the plan/group
- `:plan.task/title` (required) a short one-line plain-text title indicating the task
- `:plan.task/description` long-form text with a detailed description of the task, with support for markdown formatting
- `:plan.task/icon` a custom emoji icon to give the task
- `:plan.task/estimate` an estimated duration in minutes the task will take, if applicable
- `:plan.task/created-at` (required) instant the task was created
- `:plan.task/created-by` (required, ref) the user who created the task
- `:plan.task/updated-at` (required) instant the task was last modified
- `:plan.task/updated-by` (required, ref) the user who last modified the task


### List

A list is a container for tasks and the main interface users use to manage them.

Lists have the following properties:

- `:list/id` (required) opaque unique numeric identifier for the list in storage
- `:list/title` (required) a short one-line plain-text title indicating the list
- `:list/description` long-form text with a detailed description of the list, with support for markdown formatting
- `:list/icon` a custom emoji icon to give the list
- `:list/due-at` instant by which the list should be finished, if any
- `:list/archived-at` instant the list was archived, if it has been
- `:list/created-at` (required) instant the list was created
- `:list/created-by` (required, ref) the user who created the list
- `:list/updated-at` (required) instant the list was last modified
- `:list/updated-by` (required, ref) the user who last modified the list

A list is _done_ when it has at least one task and none of its tasks are
`:pending` or `:working`. This is derived and not stored.

Archiving is a manual action that hides the list from the main view. Archived
lists remain readable, searchable, and editable, and may be unarchived (which
clears `archived-at`).

Deleting a list deletes its groups and tasks.


### List Group

A grouping of tasks within a list.

List groups have the following properties:

- `:list.group/id` (required) opaque unique numeric identifier for the group in storage
- `:list.group/list` (required, ref) the id of the list this group belongs to
- `:list.group/source` (optional, ref) the plan group this group was created from, if any; cleared if that plan group is deleted
- `:list.group/rank` (required) a numeric sort ordering for the group within the list
- `:list.group/title` (required) a short one-line plain-text title for the group
- `:list.group/icon` a custom emoji icon to give the group

Deleting a group deletes the tasks in it. The UI confirms the deletion and
shows how many tasks will be removed.


### Task

The lowest-level entity in Chequer is the task. This represents a single "thing
that needs doing" that the user is tracking.

Tasks have the following properties:

- `:task/id` (required) opaque unique numeric identifier for the task in storage
- `:task/list` (required, ref) the id of the list this task belongs to
- `:task/group` (optional, ref) the id of the list group this task is in, if any; must belong to the same list
- `:task/source` (optional, ref) the plan task this task was created from, if any; cleared if that plan task is deleted
- `:task/rank` (required) a numeric sort ordering for the task within the list/group
- `:task/title` (required) a short one-line plain-text title indicating the task
- `:task/description` long-form text with a detailed description of the task, with support for markdown formatting
- `:task/icon` a custom emoji icon to give the task
- `:task/estimate` an estimated duration in minutes the task will take, if applicable
- `:task/due-at` instant by which the task should be finished, if any
- `:task/assignee` (optional, ref) the user the task is assigned to, if any
- `:task/state` (required) state enumeration, one of `:pending`, `:working`, `:completed`, or `:abandoned`
- `:task/created-at` (required) instant the task was created
- `:task/created-by` (required, ref) the user who created the task
- `:task/updated-at` (required) instant the task was last modified
- `:task/updated-by` (required, ref) the user who last modified the task
- `:task/started-at` instant work began on the task, if it has started
- `:task/ended-at` instant work ended on the task, if it has ended
- `:task/ended-by` (ref) the user who completed or abandoned the task, if it has ended

A task is _overdue_ when its `due-at` has passed and its state is `:pending` or
`:working`.

The assignee is informational only. It is never changed automatically, and
assignment sends no notifications. The UI offers an "assigned to me" filter.

#### Task States

A task may move from any state to any other state, so that accidental checks
are easy to undo. Changing state applies these timestamp rules:

- **→ `:pending`**: clear `started-at`, `ended-at`, and `ended-by`.
- **→ `:working`**: set `started-at` to now if it is unset; clear `ended-at`
  and `ended-by`.
- **→ `:completed` or `:abandoned`**: set `ended-at` to now and `ended-by` to
  the acting user; if `started-at` is unset, set it to the same instant.

Any number of tasks may be `:working` at the same time.


### User

Chequer is a multi-user app, primarily for tracking and attribution. All saved
plans, lists, and tasks are visible and usable by every user. The app will
_not_ manage its own credentials, and may be used in one of three modes:

- **Single User** - in this mode, there is only one "owner" user and anyone who
  accesses the app will act as them, with no authentication. The owner's name
  and title are set in the configuration file. This may be appropriate for
  simple setups or where authentication is handled upstream of the app and
  multi-user functionality is not desired.
- **Proxy Auth** - this assumes an upstream proxy is handling auth and passing
  the name of the authenticated user in a header. The header name to look for
  is set in the configuration file. The header is only trusted on requests
  whose source address is in the configured `trusted-proxies` list (default
  loopback only). Requests from any other source, or without the header, are
  denied. Unknown users are provisioned automatically.
- **OIDC** - the app supports OpenID Connect (authorization code flow) for SSO
  login and automatically provisions new users as they access the service. The
  issuer, client id, client secret, and public base URL (used to build the
  redirect URI) are set in the configuration file. After login the app keeps a
  server-side [session](#session).

Users have the following properties:

- `:user/id` (required) opaque unique numeric identifier for the user in storage
- `:user/subject` (required, unique) stable external identity: `<issuer>|<sub>` in OIDC mode, the header value in proxy mode, or `owner` in single-user mode
- `:user/name` (required, unique) short handle for the user, e.g. the OIDC `preferred_username` or the proxy header value; refreshed on login
- `:user/title` (required) a display title for the user; refreshed from OIDC claims on login
- `:user/created-at` (required) when the user account was created in the system
- `:user/last-seen` (required) when the user was last seen accessing the app (fuzzy, not tracked to the second)

If a refreshed `:user/name` collides with another user's, a numeric suffix is
appended.


### Session

Sessions exist only in OIDC mode.

- `:session/id` (required) random, unguessable token; this is the only thing stored in the cookie
- `:session/user` (required, ref) the logged-in user
- `:session/created-at` (required) when the session was created
- `:session/expires-at` (required) when the session expires

The session cookie is `HttpOnly`, `Secure`, and `SameSite=Lax`. Logging out
deletes the session row. Expired sessions are swept periodically. Open SSE
streams are closed when their session ends.


### Event

User actions in the app are tracked as events in an auditing log. This is used
to show history on various entities in the user interface.

Events have the following properties:

- `:event/id` (required) opaque unique numeric identifier for the event in storage
- `:event/instant` (required) time the event occurred
- `:event/user` (required, ref) the user who caused the event
- `:event/entity-type` (required) enumeration recording the type of entity the event is about, e.g. `plan`, `plan.group`, `plan.task`, `list`, `list.group`, `task`
- `:event/entity-id` (required) the numeric identifier of the entity acted upon
- `:event/plan` (optional) the id of the plan the entity belongs to, for plan-side events
- `:event/list` (optional) the id of the list the entity belongs to, for list-side events
- `:event/action` (required) a keyword denoting the action taken, e.g. `:create`, `:update`, `:delete`, `:complete`, `:abandon`, `:add-plan`, `:archive`, etc.
- `:event/data` EDN map of structured details, e.g. changed fields with old and new values, or the plan added
- `:event/message` a longer message describing what action took place

`entity-id`, `plan`, and `list` are plain values, not foreign keys, so
events outlive the entities they describe. A plan's or list's full history,
including its groups and tasks, is a single indexed query on the context column.

Every change and the event that records it are written in the same SQLite
transaction.

Deletes are hard deletes. The `:delete` event's data captures the entity's
final title, so history can still render something meaningful.


## Adding Plans

Plans can be added to a list when it is created or at any time afterwards.
When several plans are added together they are processed one after another,
in the order the user chose. For each plan:

1. **Groups:** each plan group is matched to an existing list group by
   normalized title (trimmed, case-insensitive). A new list group is created
   if there's no match, appended to the end of the group order, and its
   `source` is set to the plan group.
2. **Tasks:** each plan task is placed in the list group matched in step
   1, or left ungrouped if it has no group in the plan. The task is
   skipped if that destination already contains a task with the same
   normalized title. Otherwise a new `:pending` task is appended to the
   destination in the plan's order. Title, description, icon, and
   estimate are copied, and `source` is set to the plan task.

Because duplicates are skipped, adding a plan that has already been added
to the list is a no-op for tasks that are still present.

Adding a plan is one transaction, recorded as a single
`:add-plan` event on the list, along with a `:create` event for each
group and task created.


## Live Updates

Each open plan or list page holds an SSE stream. When a transaction
commits, the server pushes updated fragments to every stream viewing the
affected plan or list, so all users see changes immediately. Concurrent
field edits are last-write-wins. The event log shows who changed what.

Mutating requests are protected against CSRF by the `SameSite=Lax` session
cookie plus an `Origin` header check against the configured base URL.


## Search

A single SQLite FTS5 index covers the titles and descriptions of plans,
plan tasks, lists, and tasks. Triggers keep it in sync with the source
tables. Results link to the owning plan or list. Results from archived
lists are included but labelled as archived and ranked lower.


## Operations

- **Database:** a single SQLite file at a configured path, opened in WAL mode
  with foreign keys enabled.
- **Migrations:** numbered migrations are compiled into the binary and tracked
  using `PRAGMA user_version`. Pending migrations run at startup in a
  transaction. Before migrating, a copy of the database is written alongside
  it with `VACUUM INTO`.
- **Backups:** documented as `VACUUM INTO` or the `sqlite3 .backup` command,
  both safe while the app is running.


## Configuration

The `ini` file has these sections and keys:

- `[server]`
  - `listen` - address and port to listen on
  - `base-url` - public URL of the app, used for OIDC redirects and origin checks
  - `time-zone` - default display time zone
- `[database]`
  - `path` - path to the SQLite file
- `[auth]`
  - `mode` - one of `single`, `proxy`, or `oidc`
- `[auth.single]`
  - `name`, `title` - the owner user
- `[auth.proxy]`
  - `header` - name of the header carrying the username
  - `trusted-proxies` - comma-separated IPs/CIDRs allowed to set the header
- `[auth.oidc]`
  - `issuer` - issuer URL, used for discovery
  - `client-id`, `client-secret` - client credentials
  - `session-key` - hex key (at least 32 bytes) that signs the login cookie,
    e.g. from `openssl rand -hex 32`
  - `scopes` - requested scopes (default `openid profile email`)
  - `session-ttl` - session lifetime


## Non-Goals

The following are explicitly out of scope for the first version:

- Comments on tasks
- Recurring or scheduled list creation from plans
- Nesting beyond group → task (no subtasks)
- Multiple assignees per task, and notifications of any kind
- Per-entity permissions (all users can see and edit everything)
