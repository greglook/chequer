-- One full-text index over the titles and descriptions of plans, plan tasks,
-- lists and tasks. Each row's rowid is entity_id * 4 + a code for its kind
-- (plan 0, plan.task 1, list 2, task 3), so the triggers find the row to
-- replace by rowid instead of scanning the unindexed kind and entity_id.
-- The owner is the plan or list a result links to.
CREATE VIRTUAL TABLE search USING fts5(
  title,
  description,
  kind UNINDEXED,
  entity_id UNINDEXED,
  owner_kind UNINDEXED,
  owner_id UNINDEXED,
  tokenize = 'unicode61 remove_diacritics 2');

-- Plans

CREATE TRIGGER plan_search_insert AFTER INSERT ON plan BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 0, NEW.title, NEW.description, 'plan', NEW.id, 'plan', NEW.id); END;

CREATE TRIGGER plan_search_update AFTER UPDATE OF title, description ON plan BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 0; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 0, NEW.title, NEW.description, 'plan', NEW.id, 'plan', NEW.id); END;

CREATE TRIGGER plan_search_delete AFTER DELETE ON plan BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 0; END;

-- Plan tasks

CREATE TRIGGER plan_task_search_insert AFTER INSERT ON plan_task BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 1, NEW.title, NEW.description, 'plan.task', NEW.id, 'plan', NEW.plan_id); END;

CREATE TRIGGER plan_task_search_update AFTER UPDATE OF title, description, plan_id ON plan_task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 1; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 1, NEW.title, NEW.description, 'plan.task', NEW.id, 'plan', NEW.plan_id); END;

CREATE TRIGGER plan_task_search_delete AFTER DELETE ON plan_task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 1; END;

-- Lists

CREATE TRIGGER list_search_insert AFTER INSERT ON list BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 2, NEW.title, NEW.description, 'list', NEW.id, 'list', NEW.id); END;

CREATE TRIGGER list_search_update AFTER UPDATE OF title, description ON list BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 2; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 2, NEW.title, NEW.description, 'list', NEW.id, 'list', NEW.id); END;

CREATE TRIGGER list_search_delete AFTER DELETE ON list BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 2; END;

-- Tasks

CREATE TRIGGER task_search_insert AFTER INSERT ON task BEGIN
  INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 3, NEW.title, NEW.description, 'task', NEW.id, 'list', NEW.list_id); END;

CREATE TRIGGER task_search_update AFTER UPDATE OF title, description, list_id ON task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 3; INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  VALUES (NEW.id * 4 + 3, NEW.title, NEW.description, 'task', NEW.id, 'list', NEW.list_id); END;

CREATE TRIGGER task_search_delete AFTER DELETE ON task BEGIN
  DELETE FROM search WHERE rowid = OLD.id * 4 + 3; END;

-- Backfill (kept in step with chequer.search/rebuild-search!)

INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  SELECT id * 4 + 0, title, description, 'plan', id, 'plan', id FROM plan;

INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  SELECT id * 4 + 1, title, description, 'plan.task', id, 'plan', plan_id FROM plan_task;

INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  SELECT id * 4 + 2, title, description, 'list', id, 'list', id FROM list;

INSERT INTO search (rowid, title, description, kind, entity_id, owner_kind, owner_id)
  SELECT id * 4 + 3, title, description, 'task', id, 'list', list_id FROM task;
