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

## Routes
- Change `/plan-groups/:id/move?dir=up` to pass the direction in the body
- Should the `/plan-groups/:id` route be `/plans/:plan/groups/:id` instead?

## Gaps
- Icons need an emoji picker built in instead of requiring the user to enter one directly

## Bugs
- Reordering doesn't seem to work yet
