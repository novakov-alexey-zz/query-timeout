# --- !Ups

CREATE TABLE test_data (
  id INTEGER PRIMARY KEY
);

# --- !Downs

DROP TABLE IF EXISTS test_data;
