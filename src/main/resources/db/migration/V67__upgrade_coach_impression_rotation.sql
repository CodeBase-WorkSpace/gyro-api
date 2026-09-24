-- Kind-only identities belonged to the former hard-suppression policy. They
-- must not impose a 48-hour kind cooldown after evidence-aware rotation ships.
delete from coach_insight_impressions
where kind in (
    'CALORIE_ADHERENCE',
    'SCORE_TREND',
    'BEST_DAY',
    'PROTEIN_CONSISTENCY',
    'LOGGING_STREAK'
);

-- Include the deterministic kind tiebreaker used by shownSince while retaining
-- the user/date prefix needed by the rolling seven-day range scan.
drop index if exists idx_coach_insight_impressions_user_shown_on;

create index idx_coach_insight_impressions_user_shown_on
    on coach_insight_impressions (user_id, shown_on desc, kind);
