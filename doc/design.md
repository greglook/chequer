# Chequer Design

A high-level design document for Chequer.


## Architecture

Chequer is a simple web application that runs from a single binary and stores its data in a local SQLite database.

- **Language:** [Jolt](https://github.com/jolt-lang/jolt), a dialect of Clojure
- **Storage:** SQLite
- **Deployment:** Single self-contained binary, or via a container
- **Configuration:** An `ini` file


## Data Model

There are two major groups of entities in Chequer, as it covers both _long-term
templating_ as well as _immediate task management_.

For long-term entities, users manage a collection of _templates_ which contain
_template tasks_. These are patterns of tasks that are repeated over time, for
example a packing list, a set of tasks for cleaning a house, how to maintain
something, etc. These are not associated with any particular instant in time
and may be edited and updated over time. The tasks may be arranged into
groups within the template.

Users don't actually check off the tasks in a template; when they are ready to
work on something, the user creates a _list_ which contains reified _tasks_.
The user can create a blank list or pre-populate it with one or more templates,
which are merged together. After creation, the user may manually add more
tasks, pull in more templates, and edit or remove existing tasks.


### Template

A template is a long-lived list of template tasks which will be used
repeatedly over time to create lists to work off of.

Templates have the following properties:

- `:template/id` (required) opaque unique numeric identifier for the template in storage
- `:template/title` (required) a short one-line plain-text title for the template
- `:template/description` long-form text with a detailed description of the template, with support for markdown formatting
- `:template/icon` a custom emoji icon to give the template
- `:template/created-at` (required) instant the template was created
- `:template/created-by` (required) the name of the user who created the template
- `:template/updated-at` (required) instant the template was last modified
- `:template/updated-by` (required) the name of the user who last modified the template


### Template Group

A grouping of template tasks within a template.

Template groups have the following properties:

- `:template.group/id` (required) opaque unique numeric identifier for the group in storage
- `:template.group/template (required, ref) the id of the template this group belongs to
- `:template.group/order` (required) a numeric sort ordering for the group within the template
- `:template.group/title` (required) a short one-line plain-text title for the group
- `:template.group/icon` a custom emoji icon to give the group


### Template Task

Task templates are prototypes for creating a live task in a list. The information is used to pre-fill data in the created task.

Template tasks have the following properties:

- `:template.task/id` (required) opaque unique numeric identifier for the task in storage
- `:template.task/template (required, ref) the id of the template this task belongs to
- `:template.task/group (optional, ref) the id of the template group this task is in, if any
- `:template.task/order` (required) a numeric sort ordering for the task within the template/group
- `:template.task/title` (required) a short one-line plain-text title indicating the task
- `:template.task/description` long-form text with a detailed description of the task, with support for markdown formatting
- `:template.task/icon` a custom emoji icon to give the task
- `:template.task/estimate` an estimated duration the task will take, if applicable
- `:template.task/created-at` (required) instant the task was created
- `:template.task/created-by` (required) the name of the user who created the task
- `:template.task/updated-at` (required) instant the task was last modified
- `:template.task/updated-by` (required) the name of the user who last modified the task


### List

A list is a container for tasks and the main interface users use to manage them.

Lists have the following properties:

- `:list/id` (required) opaque unique numeric identifier for the list in storage
- `:list/title` (required) a short one-line plain-text title indicating the list
- `:list/description` long-form text with a detailed description of the list, with support for markdown formatting
- `:list/icon` a custom emoji icon to give the list
- `:list/created-at` (required) instant the list was created
- `:list/created-by` (required) the name of the user who created the list
- `:list/updated-at` (required) instant the list was last modified
- `:list/updated-by` (required) the name of the user who last modified the list


### List Group

A grouping of tasks within a list.

List groups have the following properties:

- `:list.group/id` (required) opaque unique numeric identifier for the group in storage
- `:list.group/list (required, ref) the id of the list this group belongs to
- `:list.group/order` (required) a numeric sort ordering for the group within the list
- `:list.group/title` (required) a short one-line plain-text title for the group
- `:list.group/icon` a custom emoji icon to give the group


### Task

The lowest-level entity in Chequer is the task. This represents a single "thing
that needs doing" that the user is tracking.

Tasks have the following properties:

- `:task/id` (required) opaque unique numeric identifier for the task in storage
- `:task/list (required, ref) the id of the list this task belongs to
- `:task/group (optional, ref) the id of the list group this task is in, if any
- `:task/order` (required) a numeric sort ordering for the task within the list/group
- `:task/title` (required) a short one-line plain-text title indicating the task
- `:task/description` long-form text with a detailed description of the task, with support for markdown formatting
- `:task/icon` a custom emoji icon to give the task
- `:task/estimate` an estimated duration the task will take, if applicable
- `:task/state` (required) state enumeration, one of `:pending`, `:working`, `:completed`, or `:abandoned`
- `:task/created-at` (required) instant the task was created
- `:task/updated-at` (required) instant the task was last modified
- `:task/started-at` (required) instant work began on the task
- `:task/ended-at` (required) instant work ended on the task


### User

Chequer is a multi-user app, primarily for tracking and attribution. All saved
lists, tasks, and plans are visible and usable by every user. The app will
_not_ manage its own authentication, and may be used in one of three modes:

- **Single User** - in this mode, there is only one "owner" user and anyone who
  accesses the app will act as them, with no authentication. This may be
  appropriate for simple setups or where authentication is handled upstream of
  the app and multi-user functionality is not desired.
- **Proxy Auth** - this assumes an upstream proxy is handling auth and passing
  the name of the authenticated user in a header. The header name to look for
  is set in the configuration file. If the header is missing, access is denied.
- **OIDC** - the app should support OpenID Connect for SSO login and
  automatically provision new users as they access the service. The client id
  and secret are set in the config file.

Users have the following properties:

- `:user/name` (required) short unique identifier for the user, used as the login name
- `:user/title` (required) a display title for the user
- `:user/created-at` (required) when the user account was created in the system
- `:user/last-seen` (required) when the user was last seen accesssing the app (fuzzy, not tracked to the second)


### Event

User actions in the app are tracked as events in an auditing log. This is used
to show history on various entities in the user interface.

Events have the following properties:

- `:event/id` (required) opaque unique numeric identifier for the event in storage
- `:event/instant` (required) time the event occurred
- `:event/user` (required) the name of the user which caused the event
- `:event/entity-type (required) enumeration recording the type of entity the event is about, e.g. `template`, `template.task`, `list`, etc.
- `:event/entity-id` (required) the numeric identifier of the entity acted upon
- `:event/action` (required) a keyword denoting the action taken, e.g. `:create`, `:update`, `:delete`, `:complete`, `:abandon`, etc.
- `:event/message` a longer message describing what action took place
