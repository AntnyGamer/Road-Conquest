# Town completion and achievements

Town completion measures the area inside the original municipal boundary covered by the app's unlocked exploration tiles. A tile counts only for its overlap with the town. Lakes or other holes excluded by the boundary do not count, duplicate tiles do not count twice, and visual clipping between overlapping town overlays does not change the calculation. This is exploration coverage, not a percentage of roads driven.

- Bronze: 25% explored.
- Silver: 50% explored.
- Gold: 100% explored. A town at 95–99% remains silver.

The town overlay displays a compact medal near the town's interior center and a small percentage below it. Before bronze, the percentage appears on its own. Markers use local artwork, remain visible above base-map labels and appear from zoom 7. Tapping the town shows the percentage, earned badge and tier guide. A boundary is required; unavailable or invalid boundaries do not produce invented progress.

Calculations use equal-area latitude coordinates, original boundaries, and local saved tiles. Cached boundaries are checked in bounded foreground batches even when a different overlay is selected. No extra GPS listener, provider request or backend deployment is added. Existing explored tiles qualify on upgrade. Large histories may take several foreground progress refreshes to finish the initial medal backfill.

Town-badge achievements now require 5/20/75 bronze towns, 3/15/50 silver towns, and 2/8/20/50 gold towns, with smaller one-time rewards. The achievement IDs are unchanged for backup and reward-ledger compatibility, and previously earned achievements retain their recorded points. This does not change the underlying bronze (25%), silver (50%) or gold (100%) medal thresholds.

Earned tiers remain owned if a later boundary correction changes the current percentage. Each town counts once per attained tier, with silver/gold towns also qualifying for lower-tier achievements. The existing reward ledger awards achievement points once. Town tier counters, earned achievements and their exact historical point awards travel through full backups and are removed by local-data deletion. They never grant verified or weekly driving credit.

The 30 new achievements cover intermediate and long-term road, tile, distance and place milestones, including 10 town-medal achievements. Existing achievement IDs and payouts are preserved.

Settings now opens to seven searchable categories: Tracking; Map & appearance; Account & community; Notifications & privacy; Backups & data; Your progress; Help & about. Search includes individual control text. Back returns from a category or search to the settings home, then to the map. Navigation survives rotation and appearance changes. Existing export/restore and destructive-action confirmations remain in their original handlers.
