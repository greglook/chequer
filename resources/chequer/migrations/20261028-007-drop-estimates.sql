-- Task estimates are removed. Update events that only changed an estimate
-- would describe nothing, so they are dropped; the data of other updates keeps
-- its :estimate key, which the history no longer names.
ALTER TABLE plan_task DROP COLUMN estimate;

ALTER TABLE task DROP COLUMN estimate;

DELETE FROM event
 WHERE entity_type IN ('plan.task', 'task')
   AND action = 'update'
   AND data LIKE '{:estimate [%'
   AND data NOT LIKE '%,%';
