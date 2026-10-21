(ns chequer.migrations
  "The numbered schema migrations compiled into the application.")


(def migrations
  "Numbered migrations, applied in order. Each :sql statement runs in the same transaction."
  [{:version 1
    :sql ["CREATE TABLE user (
             id INTEGER PRIMARY KEY,
             subject TEXT NOT NULL UNIQUE,
             name TEXT NOT NULL UNIQUE,
             title TEXT NOT NULL,
             created_at INTEGER NOT NULL,
             last_seen INTEGER NOT NULL)"
          "CREATE TABLE event (
             id INTEGER PRIMARY KEY,
             instant INTEGER NOT NULL,
             user_id INTEGER NOT NULL REFERENCES user(id),
             entity_type TEXT NOT NULL,
             entity_id INTEGER NOT NULL,
             plan_id INTEGER,
             list_id INTEGER,
             action TEXT NOT NULL,
             data TEXT,
             message TEXT)"
          "CREATE INDEX event_plan ON event(plan_id, instant) WHERE plan_id IS NOT NULL"
          "CREATE INDEX event_list ON event(list_id, instant) WHERE list_id IS NOT NULL"
          "CREATE INDEX event_entity ON event(entity_type, entity_id, instant)"]}])
