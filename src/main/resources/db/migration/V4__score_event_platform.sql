-- Schema evolution paired with the proto change (SubmitScoreRequest.platform = 6).
-- Additive and nullable, so it is safe during a rolling deploy: old pods simply don't write it.
ALTER TABLE score_events ADD COLUMN platform TEXT NULL;
