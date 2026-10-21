---
name: phased-implementation-plan
description: Turn a design doc (e.g. doc/design.md) into a detailed phased implementation plan document (e.g. doc/implementation-plan.md), including how to deliver it when plan mode blocks writes.
---

# Phased implementation plan from a design doc

## Delivery
- When plan mode is active, `write` is denied. Don't end the turn after only reading files. The user's complaint in that case was "You haven't written anything." Always produce visible output.
- A single chat message can cut off a long plan partway through, which has already happened. Options:
  1. Ask the user to leave plan mode, then write the file directly. This is preferred when they asked for it to be saved.
  2. Post the plan section by section across turns (Foundations + Phases 0–2, then 3–6, then 7–10 + Risks/Decisions), and say clearly that more is coming.
- When the user says "start fresh", don't use session_search to look for earlier drafts. They denied that before.
- What happened: writing the plan "compactly" did NOT stop the cutoff. A ~10-phase plan with SQL and signatures cut off around Phase 3, and the continuation cut off again in Decisions. A single message holds about 3 phases of detail.
- Default when plan mode is on: before writing anything, say in one line that the plan will come in parts. Then post Part 1 (Foundations + Phases 0–2) and end with "Part 1 of 3 — say 'continue'". Never try the whole thing in one message.
- Each part should end at a clean heading boundary, not mid-task. Then the user never has to report a cutoff, and the parts can be joined word for word.
- After writing the file, verify it: `grep -c '^## Phase'` should equal the number of phases, and the last section (Decisions) should be present.
- Evidence: a 10-phase plan with SQL/code snippets cut off even after compacting it, and the continuation message also cut off at the very end. So, **before** you generate a long plan in plan mode, ask the user to exit plan mode (offer `plan_exit`) so it can be written straight to the file. If the user insists on chat, use at least 3 turns (Foundations+0–2, then 3–6, then 7–10+Risks+Decisions). End each part with an explicit "next: Phase X" marker so a cut-off is obvious and easy to resume.
- Before writing, check whether doc/implementation-plan.md already exists (possibly partial or uncommitted) and reconcile with it rather than blindly overwriting.

## Process
1. Read the design doc in full, plus deps.edn and the existing src/test scaffold. Nothing more.
2. Load the stack skill (e.g. `jolt-web-app`) for idioms.
3. Structure:
   - Intro: every phase leaves the app runnable (`jolt serve`), testable (`jolt test`) and usable in a browser. Each phase depends only on earlier ones.
   - **Foundations**: namespace layout, mutation model, DB access (`with-tx`), routing, time, libraries, testing conventions.
   - **Phase 0** describes the existing scaffold (done).
   - Phases 1..N, each with a **Goal** line and `### Task N.M: name` with **Files:**, concrete function signatures and data shapes, and the test cases.
   - Risks, then a Decisions section that replaces "Open questions".
4. Keep signatures and names consistent across tasks. Write in flowing prose and lists, with no tables.
5. Record newly settled decisions in place in doc/design.md, not in separate docs.

## Chequer phase order (agreed)
0 scaffold, 1 config+DB, 2 users/auth/shell, 3 plans (+ minimal broker), 4 lists/tasks, 5 adding plans, 6 history, 7 live-update hardening, 8 search, 9 OIDC, 10 packaging.

## Verification
`grep -c '^## Phase' doc/implementation-plan.md` should print 11 (Phases 0–10), which shows the whole plan was saved and not truncated.
