import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { cellFor, GRID_ID, LATITUDE_STEP, LONGITUDE_STEP, scoreCells } from "../src/cells.mjs";

test("server and Android share cell fixtures across hemispheres, borders, world wrap and map limits", () => {
  const rows = readFileSync(new URL("../../app/src/test/resources/fog-grid-cells.csv", import.meta.url), "utf8").trim().split("\n");
  assert.equal(GRID_ID, "fog-grid-v1");
  for (const line of rows) {
    const [lat, lon, row, column] = line.split(",").map(Number);
    assert.deepEqual(cellFor(lat, lon), { row, column }, line);
  }
  for (const [lat, lon] of [[NaN, 0], [0, Infinity], [90.1, 0], [0, -181]]) assert.equal(cellFor(lat, lon), null);
});

function fix(cell, longitudeOffset = 0, time = 0) {
  return { lat: -90 + (cell.row + 0.5) * LATITUDE_STEP,
    lon: -180 + (cell.column + 0.5) * LONGITUDE_STEP + longitudeOffset,
    accuracy: 3, speed: 10, time };
}

test("cells require independent moving observations safely inside, never jitter or inferred intermediate cells", () => {
  const cell = cellFor(40, -75);
  const a = fix(cell), b = fix(cell, 0.0005, 5000);
  assert.deepEqual(scoreCells([a, b]), [cell]);
  assert.deepEqual(scoreCells([a, b, { ...a, time: 10000 }]), [cell]);
  assert.deepEqual(scoreCells([{ ...a, speed: 0 }, { ...b, speed: 0 }]), []);
  assert.deepEqual(scoreCells([a, { ...a, time: 5000 }]), []);
  assert.deepEqual(scoreCells([a, fix(cell, 0.00001, 5000)]), []);
  assert.deepEqual(scoreCells([a, fix(cell, 0.0005, 1000)]), []);
  assert.deepEqual(scoreCells([a, fix(cell, 0.0005, 1999)]), []);
  assert.deepEqual(scoreCells([{ ...a, speed: 2.99 }, { ...b, speed: 2.99 }]), []);
  assert.deepEqual(scoreCells([{ ...a, speed: 0 }, { ...b, speed: 3 }]), [cell]);
  assert.deepEqual(scoreCells([a, fix(cell, 0.0005, 2000)]), [cell]);
  const west = -180 + cell.column * LONGITUDE_STEP;
  assert.deepEqual(scoreCells([{ ...a, lon: west + 0.00001 }, { ...b, lon: west + 0.00002 }]), []);
  const distant = { ...cell, column: cell.column + 3 };
  assert.deepEqual(scoreCells([a, fix(distant, 0, 5000)]), []);
  assert.deepEqual(scoreCells([a, b, fix(distant, 0, 10000), fix(distant, 0.0005, 15000)]), [cell, distant]);
  assert.deepEqual(scoreCells([{ ...a, lat: 89 }, { ...b, lat: 89 }]), []);
});
