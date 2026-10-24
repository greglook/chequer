# TODOs

A list of miscellaneous items I notice that I want to fix later.

## Development
- Get better REPL workflow and setup working
- Create a gitea action runner image that bakes in node, clojure, and jolt tooling?
- CI workflow jobs should only run when relevant files have changed

## Framework
- Pre-compress the datastar JS file and serve it directly if the request accepts gzip?
- HoneySQL for SQL formatting? Depends on how things shape up.
- Adopt `com.amperity/ken` for better observability?
- Serve a prometheus-style metrics endpoint

## Routes
- Change `/plan-groups/:id/move?dir=up` to pass the direction in the body
- Should the `/plan-groups/:id` route be `/plans/:plan/groups/:id` instead?

## UI
- "Add Plans" to a list should be a modal view, not a collapsed list on the page
- Drag and drop reordering
- Instead of just expanding description, tap task row to expand with all info in a card

## Bugs
- ...
