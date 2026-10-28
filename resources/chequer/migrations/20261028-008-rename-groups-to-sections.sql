-- Groups are renamed to sections. Renaming a table or column updates the
-- references to it, but not the names of indexes and triggers or the triggers'
-- messages, so those are dropped first and created again under the new names.
DROP TRIGGER plan_task_group_same_plan_insert;

DROP TRIGGER plan_task_group_same_plan_update;

DROP TRIGGER task_group_same_list_insert;

DROP TRIGGER task_group_same_list_update;

DROP INDEX plan_group_plan;

DROP INDEX plan_task_plan;

DROP INDEX list_group_list;

DROP INDEX task_list;

ALTER TABLE plan_group RENAME TO plan_section;

ALTER TABLE list_group RENAME TO list_section;

ALTER TABLE plan_task RENAME COLUMN group_id TO section_id;

ALTER TABLE task RENAME COLUMN group_id TO section_id;

CREATE INDEX plan_section_plan ON plan_section(plan_id, rank);

CREATE INDEX plan_task_plan ON plan_task(plan_id, section_id, rank);

CREATE INDEX list_section_list ON list_section(list_id, rank);

CREATE INDEX task_list ON task(list_id, section_id, rank);

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

-- Rename the entity type in the history, and the count of new sections in the
-- results of adding a plan to a list.
UPDATE event SET entity_type = 'plan.section' WHERE entity_type = 'plan.group';

UPDATE event SET entity_type = 'list.section' WHERE entity_type = 'list.group';

UPDATE event
   SET data = replace(data, ':groups-created ', ':sections-created ')
 WHERE entity_type = 'list'
   AND action = 'add-plan';
