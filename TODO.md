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

## Bugs
- Reordering groups doesn't seem to work yet (tasks does)
- Date input has a picker but requires a time entry right now

## Tweaks
- Icons need an emoji picker built in instead of requiring the user to enter one directly
- "Add Plans" to a list should be a modal view, not a collapsed list on the page
- Rename 'order' in the code to 'rank'
- Don't record history for reordering changes
- Small circular badges to show task assignees
    - label with first letter of user name
    - generate unique stable color by hashing name
