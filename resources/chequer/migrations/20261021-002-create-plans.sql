-- Plans, their sections and tasks get AUTOINCREMENT ids, which SQLite never
-- hands out twice. Without it a new row could take the id of the last one
-- deleted, and with it whatever is keyed by that id: the deleted row's history,
-- its cached render, the page's state for its element.
CREATE TABLE plan (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE TABLE plan_section (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

CREATE TABLE plan_task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  section_id INTEGER REFERENCES plan_section(id) ON DELETE CASCADE,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE INDEX plan_section_plan ON plan_section(plan_id, rank);

CREATE INDEX plan_task_plan ON plan_task(plan_id, section_id, rank);

CREATE TRIGGER plan_task_section_same_plan_insert BEFORE INSERT ON plan_task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT plan_id FROM plan_section WHERE id = NEW.section_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another plan'); END;

CREATE TRIGGER plan_task_section_same_plan_update BEFORE UPDATE OF plan_id, section_id ON plan_task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT plan_id FROM plan_section WHERE id = NEW.section_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another plan'); END;
