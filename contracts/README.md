# SCORE activity contract lifecycle

`score-activity-event.schema.json` and `score.activity.events.v1` are the initial, unreleased v1
contract introduced by the activity-history work in this branch. There are no released v1
producers or consumers to roll from an older envelope.

After v1 is released, its strict envelope is frozen: adding, removing, or renaming an envelope or
`context` field requires a new major `schemaVersion` and Redis topic. Catalog additions that do not
change the envelope may remain on the existing topic only when all supported consumers accept the
new activity name.
