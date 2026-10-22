CREATE TABLE plan (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE TABLE plan_group (
  id INTEGER PRIMARY KEY,
  plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  "order" REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

CREATE TABLE plan_task (
  id INTEGER PRIMARY KEY,
  plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  group_id INTEGER REFERENCES plan_group(id) ON DELETE CASCADE,
  "order" REAL NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  estimate INTEGER,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE INDEX plan_group_plan ON plan_group(plan_id, "order");

CREATE INDEX plan_task_plan ON plan_task(plan_id, group_id, "order");

CREATE TRIGGER plan_task_group_same_plan_insert BEFORE INSERT ON plan_task
  WHEN NEW.group_id IS NOT NULL
   AND (SELECT plan_id FROM plan_group WHERE id = NEW.group_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'group belongs to another plan'); END;

CREATE TRIGGER plan_task_group_same_plan_update BEFORE UPDATE OF plan_id, group_id ON plan_task
  WHEN NEW.group_id IS NOT NULL
   AND (SELECT plan_id FROM plan_group WHERE id = NEW.group_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'group belongs to another plan'); END;
