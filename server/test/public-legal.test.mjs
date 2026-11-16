import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";
import { APP_ADS_TXT } from "../src/public-legal.mjs";

test("legacy account API and public website authorize the same ad sellers", async () => {
  const website = await readFile(new URL("../../docs/app-ads.txt", import.meta.url), "utf8");
  assert.equal(APP_ADS_TXT, website);
});
