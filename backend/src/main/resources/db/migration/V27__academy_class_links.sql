-- Which LUMA batch stands for each class in the Academy's own database (its payment rows' class_type).
-- LUMA reads that database read-only and never migrates it; this table lives only in LUMA's database.
-- The batch's enrolments are kept in step with the Academy's class by an explicit staff sync.
CREATE TABLE academy_class_links (
  id UUID PRIMARY KEY,
  academy_id UUID NOT NULL REFERENCES academies(id),
  class_name VARCHAR(200) NOT NULL,
  batch_id UUID NOT NULL,
  linked_by UUID NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_synced_at TIMESTAMPTZ,
  last_synced_by UUID,
  -- Distinct Academy students in the class at the last sync.
  last_synced_students INTEGER,
  CONSTRAINT fk_academy_class_links_batch FOREIGN KEY (batch_id, academy_id) REFERENCES batches (id, academy_id),
  CONSTRAINT uq_academy_class_links_class UNIQUE (academy_id, class_name),
  CONSTRAINT uq_academy_class_links_batch UNIQUE (batch_id),
  CONSTRAINT ck_academy_class_links_name CHECK (btrim(class_name) <> '' AND class_name = btrim(class_name))
);
