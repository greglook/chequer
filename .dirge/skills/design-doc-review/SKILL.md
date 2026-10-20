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

## Verification
Check that no unclosed inline-code bullets or known typos remain (no output before the diffstat means clean):

```bash
grep -nE '^- `[^`]*$|^- `[^`]* \(' doc/design.md; git diff --stat
```
