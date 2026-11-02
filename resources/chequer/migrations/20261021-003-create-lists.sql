-- Lists, their sections and tasks get AUTOINCREMENT ids, for the same reason
-- as plans: an id is never reused for a new row.
CREATE TABLE list (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  due_at INTEGER,
  archived_at INTEGER,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

CREATE TABLE list_section (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES plan_section(id) ON DELETE SET NULL,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

CREATE TABLE task (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
  section_id INTEGER REFERENCES list_section(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES plan_task(id) ON DELETE SET NULL,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  due_at INTEGER,
  assignee_id INTEGER REFERENCES user(id),
  state TEXT NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'completed', 'skipped')),
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id),
  ended_at INTEGER,
  ended_by INTEGER REFERENCES user(id));

CREATE INDEX list_active ON list(archived_at, due_at);

CREATE INDEX list_section_list ON list_section(list_id, rank);

CREATE INDEX task_list ON task(list_id, section_id, rank);

CREATE INDEX task_assignee ON task(assignee_id) WHERE assignee_id IS NOT NULL;

CREATE TRIGGER task_section_same_list_insert BEFORE INSERT ON task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT list_id FROM list_section WHERE id = NEW.section_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another list'); END;

CREATE TRIGGER task_section_same_list_update BEFORE UPDATE OF list_id, section_id ON task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT list_id FROM list_section WHERE id = NEW.section_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another list'); END;
