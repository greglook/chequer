---
name: design-doc-review
description: Review a user's design/data-model doc, surface gaps, then resolve open decisions interactively and fold them back into the doc.
---

# Design doc review and decision workflow

Use this when the user asks to "read the design and suggest updates / what I'm missing".

## 1. Review pass (read-only)
- Use `repo_overview` and `git log` to orient, then read the design doc and README in full.
- Report findings grouped by category, with the most important item first in each group:
  1. **Correctness bugs in the doc:** broken markdown such as unclosed backticks (cite line numbers), typos, references to entities that don't exist, naming drift, and fields wrongly marked required (for example `started-at` on a pending task).
  2. **Data model gaps:** missing attribution (`created-by`/`updated-by`/`ended-by`), provenance refs from copies back to their prototypes, undefined merge or dedupe rules, ordering semantics and the type of the `order` field, units (estimates, timestamps), lifecycle and archival, identity keyed by mutable names, integrity rules (same-parent constraints, cascade versus set-null).
  3. **State machine:** allowed transitions, side effects on timestamps, concurrency limits.
  4. **Audit/event log:** polymorphic refs can't be FKs, so add context refs; structured data alongside free text; writing events in the same transaction; history of deleted entities.
  5. **Auth/security:** header-based proxy auth is spoofable without a trusted-proxy allowlist; OIDC implies sessions, logout and CSRF; markdown must be sanitized.
  6. **Operations:** DB path, WAL, migrations, backups, full list of config keys, UI architecture, live updates and concurrent edits.
  7. **Features to mark in or out of scope explicitly**, as non-goals.
- End by offering two options: update the doc directly, or work through the open decisions one at a time.

## 2. Decision pass
- Use the `question` tool with 1–3 related questions per call. Give concrete options and mark one "(Recommended)" with a reason in each description.
- Order: merge rules → provenance → ordering → state machine → attribution/deletion → lifecycle/units → identity/events → auth/UI → concurrency/storage → scope (multi-select; unselected options become non-goals) → follow-ups on in-scope features → where to record the decisions.
- Users may answer in free text that overrides an option (for example "Datastar instead of htmx, via SSE"). Adopt the override and ask the follow-ups it implies, such as concurrency once live updates exist.

## 3. Write-back
- Rewrite the doc in place, unless the user picks a separate doc. Fix all the bugs found, and add these sections: Conventions (ids, instants, refs, ordering, text, units), per-entity rules, state transitions as a list, Applying/merging rules, Live Updates, Search, Operations, Configuration (every ini key), Non-Goals.
- In the summary, mention any unexpected files in the diff that you didn't create. Do not commit.
- Formatting: markdown lists only, no tables.

## 4. Terminology renames (common follow-up)
Users often rename a core entity after write-back (for example template → plan, "apply" → "add").
- Run one ordered `sed -i` pass. Put specific phrases first (section titles, anchors such as `#applying-templates`, event keywords such as `:apply-template`, derived words such as "templating"), then do case variants of the bare noun (`Templates`, `Template`, `templates`, `template`). This also renames namespaced keys like `:template.task/*` → `:plan.task/*`.
- Next, grep for leftover verb forms (`grep -niE 'appl|templat'`). Rewrite each sentence by hand so it reads naturally ("applied" → "added to a list", "re-applying" → "adding ... again"). Leave unrelated uses such as "if applicable" alone.
- Check articles (`a plan` versus `an ...`). If the new noun now collides with a general word in the prose ("long-term planning"), point it out to the user.

## 5. Implementation plan (common follow-up)
When the user asks for "a detailed implementation plan in incremental phases" saved to `doc/implementation-plan.md`:
- **Save the file right away.** Plan mode blocks writes, and the session DB keeps only the last assistant message, so an unsaved plan in a long or multi-message reply gets lost. If you're in plan mode, keep the whole plan in one message and ask to exit and save. Don't split it across several messages.
- Structure:
  - An intro with shared exit criteria (tests green, unit tests on domain functions against a temp SQLite DB, handler request/response tests).
  - Phase 0 for the existing scaffold.
  - Foundation: config, DB access, migrations, events, ordering helpers.
  - Then auth and layout, then each entity area in dependency order: plans → lists/tasks → adding plans → history → live SSE → search → OIDC → packaging.
  - Each phase gets a Goal line, its migration, domain namespace, pages and a **Tests** bullet that names the boundary cases.
- End with **Risks**: unverified library dependencies (SQLite binding/FTS5, markdown, HTTPS/JSON for OIDC), plus a spike in the phase that needs each one. Then **Open Questions** for undecided details.
- If you're recovering a lost plan from the previous session, check with `session_search` and then `sqlite3 -readonly .dirge/sessions/state.db "select content from messages where id=N"`. Keep any recovered text word for word, and make the regenerated phases match its cross-references (for example "fallback in Phase 2").

## 6. Deepening an existing plan (common follow-up)
When the user asks to "review the plan and deepen it / fill gaps":
- Read the plan, design.md and current source, then resolve the "Risks" spikes by **reading library sources** (clone jolt-lang/examples and the jolt-lang libs into /tmp; see the jolt-web-app skill). Most spikes, such as the SQLite binding, time zones, JSON and crypto for OIDC, can be settled this way without running code.
- Look for cross-cutting gaps the phase list misses: the mutation→UI update model (if live SSE is a later phase, earlier phases' UI gets reworked, so move a minimal broker earlier), DB connection/concurrency policy, routing, request parsing (Datastar signals), time-zone rendering, test helpers (temp DB fixture, fake clock, request builder), server thread strategy vs open SSE streams, and graceful shutdown.
- Ask the architectural questions first, one per call (mutation model, then connections). After that, batch the plan's Open Questions plus library choices (router, markdown) in 2–4 question calls.
- Before rewriting, present a per-phase change summary: a new "Foundations" section, per-phase additions, Risks dropped or added, and Open Questions turned into a "Decisions" section. Keep phase numbering stable. Also record the decisions in design.md, and ask for approval before writing.

## Verification
Check that no unclosed inline-code bullets or known typos remain (no output before the diffstat means clean):

```bash
grep -nE '^- `[^`]*$|^- `[^`]* \(' doc/design.md; git diff --stat
```
