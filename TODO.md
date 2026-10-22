# TODOs

A list of miscellaneous items I notice that I want to fix later.

## Development
- Get better REPL workflow and setup working
- Create a gitea action runner image that bakes in node, clojure, and jolt tooling?

## Framework
- Adopt `ring-core` and use middleware from it instead of rolling our own where possible
    - static asset serving
    - not-modified caching
    - others?
- Pre-compress the datastar JS file and serve it directly if the request accepts gzip?
- Adopt `clojure.tools.logging` for more structured logging output
- HoneySQL for SQL formatting? Depends on how things shape up.
- Adopt `com.amperity/ken` for better observability?

## Routes
- Change `/plan-groups/:id/move?dir=up` to pass the direction in the body
- Should the `/plan-groups/:id` route be `/plans/:plan/groups/:id` instead?
