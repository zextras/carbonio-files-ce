-- SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Same foreign keys as Advanced V16 (its transferOwnership gives nodes new ids with one UPDATE of
-- node.node_id), so both schemas match. Constraint names are looked up, they differ on old databases.

BEGIN;

DO $$
DECLARE
  referencing TEXT;
  existing RECORD;
BEGIN
  FOREACH referencing IN ARRAY
    ARRAY['activity', 'collaboration_link', 'custom', 'link', 'revision', 'share', 'trashed']
  LOOP
    FOR existing IN
      SELECT conname FROM pg_constraint
      WHERE contype = 'f' AND conrelid = referencing::regclass AND confrelid = 'node'::regclass
    LOOP
      EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', referencing, existing.conname);
    END LOOP;
    EXECUTE format(
      'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (node_id) REFERENCES node (node_id)'
        ' ON UPDATE CASCADE ON DELETE CASCADE',
      referencing, referencing || '_node_id_fkey');
  END LOOP;
END $$;

COMMIT;
