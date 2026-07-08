ALTER TABLE investment_behavior_scores
    ADD COLUMN IF NOT EXISTS grade VARCHAR(20);
