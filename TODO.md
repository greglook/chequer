# TODOs

A list of miscellaneous items I notice that I want to fix later.

## Development
- REPL code reloading is still NYI

## Framework
- Pre-compress the datastar JS file and serve it directly if the request accepts gzip?
- HoneySQL for SQL formatting? Depends on how things shape up.
- Adopt `com.amperity/ken` for better observability?
- Serve a prometheus-style metrics endpoint

## Routes
- Change `/plan-sections/:id/move?dir=up` to pass the direction in the body
- Slug identifiers in routes? Would be prettier

## UI
- Copy list task to plan (or update matching task)
- Copy list section to plan (or update matching tasks)
- Collapse/expand all sections

## Future Ideas
- Organize plans in a hierarchical structure
- Automatically suggest icon for task?
