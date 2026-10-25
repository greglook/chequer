-- Tasks are no longer started: working tasks go back to pending and started_at
-- is dropped. Abandoned tasks become skipped. SQLite can't alter a CHECK
-- constraint, so the state column is replaced by a new one and renamed.
ALTER TABLE task ADD COLUMN new_state TEXT NOT NULL DEFAULT 'pending'
  CHECK (new_state IN ('pending', 'completed', 'skipped'));

UPDATE task SET new_state = CASE state
  WHEN 'completed' THEN 'completed'
  WHEN 'abandoned' THEN 'skipped'
  ELSE 'pending' END;

ALTER TABLE task DROP COLUMN state;

ALTER TABLE task RENAME COLUMN new_state TO state;

ALTER TABLE task DROP COLUMN started_at;

-- Rewrite the history of state changes to match. Their data is only
-- {:state [old new]}, and starting a task has no equivalent, so those events
-- are dropped once they become pending → pending.
UPDATE event
   SET data = replace(replace(data, '"working"', '"pending"'), '"abandoned"', '"skipped"')
 WHERE entity_type = 'task'
   AND action IN ('update', 'complete', 'abandon')
   AND data LIKE '{:state [%';

UPDATE event SET action = 'skip' WHERE entity_type = 'task' AND action = 'abandon';

DELETE FROM event
 WHERE entity_type = 'task'
   AND action = 'update'
   AND data = '{:state ["pending" "pending"]}';
