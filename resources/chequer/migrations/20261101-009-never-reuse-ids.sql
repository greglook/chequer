-- Plans, lists, their sections and tasks get AUTOINCREMENT ids, which SQLite
-- never hands out twice. Without it a new row could take the id of the last
-- one deleted, and with it whatever is keyed by that id: the deleted row's
-- history, its cached render, the page's state for its element.
--
-- SQLite can't add AUTOINCREMENT to a table, so each is rebuilt: created anew,
-- filled from the old one, which is dropped, and renamed. Migrations run with
-- foreign keys off, so the drops don't cascade. Renaming a table checks every
-- trigger in the schema, and the same-section triggers name the section tables
-- in their bodies, so all the triggers on these tables are dropped first and
-- created again at the end, with the indexes the drops took along.
DROP TRIGGER plan_task_section_same_plan_insert;

DROP TRIGGER plan_task_section_same_plan_update;

DROP TRIGGER task_section_same_list_insert;

DROP TRIGGER task_section_same_list_update;

DROP TRIGGER plan_search_insert;

DROP TRIGGER plan_search_update;

DROP TRIGGER plan_search_delete;

DROP TRIGGER plan_task_search_insert;

DROP TRIGGER plan_task_search_update;

DROP TRIGGER plan_task_search_delete;

DROP TRIGGER list_search_insert;

DROP TRIGGER list_search_update;

DROP TRIGGER list_search_delete;

DROP TRIGGER task_search_insert;

DROP TRIGGER task_search_update;

DROP TRIGGER task_search_delete;

-- Plans

CREATE TABLE new_plan (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  description TEXT,
  icon TEXT,
  created_at INTEGER NOT NULL,
  created_by INTEGER NOT NULL REFERENCES user(id),
  updated_at INTEGER NOT NULL,
  updated_by INTEGER NOT NULL REFERENCES user(id));

INSERT INTO new_plan (id, title, description, icon, created_at, created_by, updated_at, updated_by)
  SELECT id, title, description, icon, created_at, created_by, updated_at, updated_by FROM plan;

DROP TABLE plan;

ALTER TABLE new_plan RENAME TO plan;

CREATE TABLE new_plan_section (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  plan_id INTEGER NOT NULL REFERENCES plan(id) ON DELETE CASCADE,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

INSERT INTO new_plan_section (id, plan_id, rank, title, icon)
  SELECT id, plan_id, rank, title, icon FROM plan_section;

DROP TABLE plan_section;

ALTER TABLE new_plan_section RENAME TO plan_section;

CREATE TABLE new_plan_task (
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

INSERT INTO new_plan_task (id, plan_id, section_id, rank, title, description, icon,
                           created_at, created_by, updated_at, updated_by)
  SELECT id, plan_id, section_id, rank, title, description, icon,
         created_at, created_by, updated_at, updated_by FROM plan_task;

DROP TABLE plan_task;

ALTER TABLE new_plan_task RENAME TO plan_task;

-- Lists

CREATE TABLE new_list (
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

INSERT INTO new_list (id, title, description, icon, due_at, archived_at,
                      created_at, created_by, updated_at, updated_by)
  SELECT id, title, description, icon, due_at, archived_at,
         created_at, created_by, updated_at, updated_by FROM list;

DROP TABLE list;

ALTER TABLE new_list RENAME TO list;

CREATE TABLE new_list_section (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  list_id INTEGER NOT NULL REFERENCES list(id) ON DELETE CASCADE,
  source_id INTEGER REFERENCES plan_section(id) ON DELETE SET NULL,
  rank REAL NOT NULL,
  title TEXT NOT NULL,
  icon TEXT);

INSERT INTO new_list_section (id, list_id, source_id, rank, title, icon)
  SELECT id, list_id, source_id, rank, title, icon FROM list_section;

DROP TABLE list_section;

ALTER TABLE new_list_section RENAME TO list_section;

CREATE TABLE new_task (
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

INSERT INTO new_task (id, list_id, section_id, source_id, rank, title, description, icon, due_at,
                      assignee_id, state, created_at, created_by, updated_at, updated_by,
                      ended_at, ended_by)
  SELECT id, list_id, section_id, source_id, rank, title, description, icon, due_at,
         assignee_id, state, created_at, created_by, updated_at, updated_by,
         ended_at, ended_by FROM task;

DROP TABLE task;

ALTER TABLE new_task RENAME TO task;

-- Ids deleted before now were above the highest left, if they were the
-- highest, and still have events: start each table past those too. Copying
-- the rows has started it past the highest left.

INSERT INTO sqlite_sequence (name, seq)
  SELECT t.name, 0
    FROM (SELECT 'plan' AS name UNION ALL SELECT 'plan_section' UNION ALL SELECT 'plan_task'
          UNION ALL SELECT 'list' UNION ALL SELECT 'list_section' UNION ALL SELECT 'task') AS t
   WHERE t.name NOT IN (SELECT name FROM sqlite_sequence);

UPDATE sqlite_sequence
   SET seq = max(seq, coalesce((SELECT max(entity_id) FROM event
                                 WHERE entity_type = CASE sqlite_sequence.name
                                   WHEN 'plan' THEN 'plan'
                                   WHEN 'plan_section' THEN 'plan.section'
                                   WHEN 'plan_task' THEN 'plan.task'
                                   WHEN 'list' THEN 'list'
                                   WHEN 'list_section' THEN 'list.section'
                                   WHEN 'task' THEN 'task' END), 0))
 WHERE name IN ('plan', 'plan_section', 'plan_task', 'list', 'list_section', 'task');

-- Indexes

CREATE INDEX plan_section_plan ON plan_section(plan_id, rank);

CREATE INDEX plan_task_plan ON plan_task(plan_id, section_id, rank);

CREATE INDEX list_active ON list(archived_at, due_at);

CREATE INDEX list_section_list ON list_section(list_id, rank);

CREATE INDEX task_list ON task(list_id, section_id, rank);

CREATE INDEX task_assignee ON task(assignee_id) WHERE assignee_id IS NOT NULL;

-- Same-section triggers

CREATE TRIGGER plan_task_section_same_plan_insert BEFORE INSERT ON plan_task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT plan_id FROM plan_section WHERE id = NEW.section_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another plan'); END;

CREATE TRIGGER plan_task_section_same_plan_update BEFORE UPDATE OF plan_id, section_id ON plan_task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT plan_id FROM plan_section WHERE id = NEW.section_id) <> NEW.plan_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another plan'); END;

CREATE TRIGGER task_section_same_list_insert BEFORE INSERT ON task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT list_id FROM list_section WHERE id = NEW.section_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another list'); END;

CREATE TRIGGER task_section_same_list_update BEFORE UPDATE OF list_id, section_id ON task
  WHEN NEW.section_id IS NOT NULL
   AND (SELECT list_id FROM list_section WHERE id = NEW.section_id) <> NEW.list_id
  BEGIN SELECT RAISE(ABORT, 'section belongs to another list'); END;

-- Search triggers

CREATE TRIGGER plan_search_insert AFTER INSERT ON plan BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 0, NEW.title, NEW.description, 'plan', NEW.id, 'plan', NEW.id); END;

CREATE TRIGGER plan_search_update AFTER UPDATE OF title, description ON plan BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 0; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 0, NEW.title, NEW.description, 'plan', NEW.id, 'plan', NEW.id); END;

CREATE TRIGGER plan_search_delete AFTER DELETE ON plan BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 0; END;

CREATE TRIGGER plan_task_search_insert AFTER INSERT ON plan_task BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 1, NEW.title, NEW.description, 'plan.task', NEW.id, 'plan', NEW.plan_id); END;

CREATE TRIGGER plan_task_search_update AFTER UPDATE OF title, description, plan_id ON plan_task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 1; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 1, NEW.title, NEW.description, 'plan.task', NEW.id, 'plan', NEW.plan_id); END;

CREATE TRIGGER plan_task_search_delete AFTER DELETE ON plan_task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 1; END;

CREATE TRIGGER list_search_insert AFTER INSERT ON list BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 2, NEW.title, NEW.description, 'list', NEW.id, 'list', NEW.id); END;

CREATE TRIGGER list_search_update AFTER UPDATE OF title, description ON list BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 2; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 2, NEW.title, NEW.description, 'list', NEW.id, 'list', NEW.id); END;

CREATE TRIGGER list_search_delete AFTER DELETE ON list BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 2; END;

CREATE TRIGGER task_search_insert AFTER INSERT ON task BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 3, NEW.title, NEW.description, 'task', NEW.id, 'list', NEW.list_id); END;

CREATE TRIGGER task_search_update AFTER UPDATE OF title, description, list_id ON task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 3; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 3, NEW.title, NEW.description, 'task', NEW.id, 'list', NEW.list_id); END;

CREATE TRIGGER task_search_delete AFTER DELETE ON task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 3; END;
