import { distance } from "./scoring.mjs";

// Keep this contract identical to Android map/FogGrid.kt. Changing it needs a new grid ID.
export const GRID_ID = "fog-grid-v1";
export const LATITUDE_STEP = 1609.344 / 111195;
export const COLUMN_COUNT = Math.round(360 * 111195 * Math.cos(40 * Math.PI / 180) / 1609.344);
export const LONGITUDE_STEP = 360 / COLUMN_COUNT;
const MAX_LATITUDE = 85.05112878;

export function cellFor(lat, lon) {
  if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return null;
  const row = Math.floor((Math.max(-MAX_LATITUDE, Math.min(MAX_LATITUDE, lat)) + 90) / LATITUDE_STEP);
  const wrapped = ((lon + 180) % 360 + 360) % 360;
  return { row, column: Math.min(COLUMN_COUNT - 1, Math.floor(wrapped / LONGITUDE_STEP)) };
}

function safelyInside(fix, cell) {
  const south = Math.max(-MAX_LATITUDE, -90 + cell.row * LATITUDE_STEP);
  const north = Math.min(MAX_LATITUDE, -90 + (cell.row + 1) * LATITUDE_STEP);
  const west = -180 + cell.column * LONGITUDE_STEP;
  const lon = fix.lon === 180 ? -180 : fix.lon;
  const latitudeMargin = Math.min(fix.lat - south, north - fix.lat) * 111195;
  const longitudeMargin = Math.min(lon - west, west + LONGITUDE_STEP - lon) * 111195 *
    Math.cos(Math.max(Math.abs(south), Math.abs(north)) * Math.PI / 180);
  // Accuracy envelopes touching another cell do not establish a unique visit.
  return Math.min(latitudeMargin, longitudeMargin) > fix.accuracy + 5;
}

export function scoreCells(fixes) {
  const cells = new Map();
  // Each fix is shared by two neighboring pairs. Compute its cell and accuracy
  // envelope once, without weakening either pair's two-fix verification.
  let a = fixes[0];
  let first = a && cellFor(a.lat, a.lon);
  let firstInside = first != null && safelyInside(a, first);
  for (let i = 1; i < fixes.length; i++) {
    const b = fixes[i];
    const second = cellFor(b.lat, b.lon);
    const secondInside = second != null && safelyInside(b, second);
    if (firstInside && secondInside && first.row === second.row && first.column === second.column) {
      const seconds = (b.time - a.time) / 1000;
      // Duration and reported speed can reject a pair without any trigonometry.
      if (seconds >= 2 && Math.max(a.speed, b.speed) >= 3) {
        const meters = distance(a, b);
        if (meters >= Math.max(10, a.accuracy + b.accuracy) && meters / seconds >= 2.5) {
          cells.set(`${first.row}:${first.column}`, first);
        }
      }
    }
    a = b;
    first = second;
    firstInside = secondInside;
  }
  // No interpolated cells, client totals, road IDs, or imported history can earn credit.
  return [...cells.values()];
}
