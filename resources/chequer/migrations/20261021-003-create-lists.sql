CREATE TABLE list (
  id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  due_at INTEGER,
  archived_at INTEGER,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE TABLE list_group (
  id INTEGER PRIMARY KEY,
  list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES plan_group(id) ON DELETE SET NULL,
  "order" REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

CREATE TABLE task (
  id INTEGER PRIMARY KEY,
  list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
  group_id INTEGER REFERENCES list_group(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES plan_task(id) ON DELETE SET NULL,
  "order" REAL NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  estimate INTEGER,
  due_at INTEGER,
  assignee_id INTEGER REFERENCES user(id),
  state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'working', 'completed', 'abandoned')),
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id),
  started_at INTEGER,
  ended_at INTEGER,
  ended_by INTEGER REFERENCES user(id));

CREATE INDEX list_active ON list(archived_at, due_at);

CREATE INDEX list_group_list ON list_group(list_id, "order");

CREATE INDEX task_list ON task(list_id, group_id, "order");

CREATE INDEX task_assignee ON task(assignee_id) WHERE assignee_id IS NOT NULL;

CREATE TRIGGER task_group_same_list_insert BEFORE INSERT ON task
  WHEN NEW.group_id IS NOT NULL
   AND (SELECT list_id FROM list_group WHERE id = NEW.group_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'group belongs to another list'); END;

CREATE TRIGGER task_group_same_list_update BEFORE UPDATE OF list_id, group_id ON task
  WHEN NEW.group_id IS NOT NULL
   AND (SELECT list_id FROM list_group WHERE id = NEW.group_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'group belongs to another list'); END;
