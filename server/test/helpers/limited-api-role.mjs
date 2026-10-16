import { readFile } from "node:fs/promises";

/** Exercise the HTTP APIs with a runtime role that cannot modify schema or map catalogs. */
export async function limitedApiDatabase(admin, connectionString) {
  await admin.query(await readFile(new URL("../../schema.sql", import.meta.url), "utf8"));
  const role = "roadconquest_api_test";
  if (!(await admin.query("SELECT 1 FROM pg_roles WHERE rolname = $1", [role])).rowCount) {
    await admin.query("CREATE ROLE roadconquest_api_test LOGIN PASSWORD 'ci-only-api-password'");
  }
  await admin.query("GRANT USAGE ON SCHEMA public TO roadconquest_api_test");
  await admin.query("GRANT SELECT, INSERT ON users TO roadconquest_api_test");
  await admin.query("GRANT UPDATE (username_display, username_key, leaderboard_visible), DELETE ON users TO roadconquest_api_test");
  await admin.query("GRANT SELECT, INSERT, DELETE ON sessions TO roadconquest_api_test");
  await admin.query("GRANT SELECT, INSERT, UPDATE, DELETE ON notification_devices TO roadconquest_api_test");
  await admin.query("GRANT SELECT, INSERT, UPDATE, DELETE ON auth_rate_limits, competition_scores, competition_roads, competition_runs, competition_receipts, competition_weeks, cell_scores, competition_cells, cell_runs, cell_receipts, cell_weeks, friendships TO roadconquest_api_test");
  await admin.query("GRANT SELECT ON road_catalogs, road_edges TO roadconquest_api_test");
  await admin.query("GRANT USAGE, SELECT ON SEQUENCE users_id_seq TO roadconquest_api_test");
  const url = new URL(connectionString);
  url.username = role;
  url.password = "ci-only-api-password";
  return url.toString();
}
