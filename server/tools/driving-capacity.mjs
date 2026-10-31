// Planning arithmetic, not a throughput test or a production capacity promise.
import { readFileSync } from 'node:fs';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: {
  'fixes-per-hour': { type: 'string', default: '900' },
  'hours-per-user': { type: 'string', default: '2.5' },
  'local-share': { type: 'string', default: '0' },
  'tile-misses': { type: 'string', default: '0' },
  'repair-credits': { type: 'string', default: '0' },
  'repair-backup-calls': { type: 'string', default: '0' },
  'budget-fraction': { type: 'string', default: '0.8' }
} });
const number = name => {
  const value = Number(values[name]);
  if (!Number.isFinite(value) || value < 0) throw new Error('Invalid --' + name);
  return value;
};
const fixes = number('fixes-per-hour'), hoursPerUser = number('hours-per-user');
const local = number('local-share'), fraction = number('budget-fraction');
if (fixes <= 0 || hoursPerUser <= 0 || local >= 1 || fraction <= 0 || fraction > 1)
  throw new Error('Fixes/hours must be positive, local-share must be below 1, and budget-fraction must be in (0,1].');
const config = JSON.parse(readFileSync(new URL('../maps/wrangler.jsonc', import.meta.url), 'utf8'));
const primaryCap = Number(config.vars.DAILY_CREDIT_CAP);
const backupCap = Number(config.vars.LOCATIONIQ_DAILY_REQUEST_CAP);
// Reference model: 75-minute fragments, three context
// slots, startup plus a final partial batch. Backup retains its 52.5s cadence.
const fragmentHours = 1.25;
const primaryPerHour = (1 + Math.ceil(fixes * fragmentHours / 97)) / fragmentHours;
const backupPerHour = Math.max(
  1 + Math.ceil(fragmentHours * 3600 / 52.5),
  1 + Math.ceil(fixes * fragmentHours / 22)
) / fragmentHours;
const availablePrimary = Math.max(0, primaryCap * fraction - number('tile-misses') / 4 - number('repair-credits'));
const availableBackup = Math.max(0, backupCap * fraction - number('repair-backup-calls'));
const estimate = share => {
  const hours = (availablePrimary / primaryPerHour + availableBackup / backupPerHour) / (1 - share);
  return { local_paid_work_avoided: share, driving_hours_per_day: hours,
    driving_users_per_day: Math.floor(hours / hoursPerUser), hours_per_user: hoursPerUser };
};
console.log(JSON.stringify({
  planning_only: true,
  configured_caps: { primary_credits: primaryCap, backup_calls: backupCap },
  assumptions: { retained_fixes_per_hour: fixes, fragment_hours: fragmentHours, context_slots: 3,
    budget_fraction: fraction, primary_credits_per_online_hour: primaryPerHour,
    backup_calls_per_online_hour: backupPerHour, tile_cache_misses: number('tile-misses'),
    repair_credits: number('repair-credits'), repair_backup_calls: number('repair-backup-calls') },
  selected: estimate(local),
  scenarios: [0, .25, .5, .55, .6].map(estimate),
  limitations: 'Matching-only planning model. Local share means whole paid work actually avoided, not familiar miles. Provider health, quality, trip shapes, repairs, bursts, shared IP caps and other service quotas can reduce supported usage. No production reuse share has been measured.'
}, null, 2));
