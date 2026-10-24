import pg from 'pg';

// CI uses real PostgreSQL, including concurrent connections and row locks.
// An optional local PostgreSQL WASM engine also exercises the actual SQL without a cloud database.
export async function forumDatabase() {
  if (process.env.DATABASE_URL) return new pg.Pool({ connectionString: process.env.DATABASE_URL });
  const { PGlite } = await import(process.env.FORUM_PGLITE_MODULE);
  const db = new PGlite();
  let tail = Promise.resolve();
  async function acquire() {
    const before = tail;
    let release;
    tail = new Promise(resolve => { release = resolve; });
    await before;
    return release;
  }
  async function query(sql, parameters) {
    const result = parameters ? await db.query(sql, parameters) : (await db.exec(sql)).at(-1);
    return { rows: result?.rows || [], rowCount: result?.rows?.length || result?.affectedRows || 0 };
  }
  return {
    async query(sql, parameters) { const release = await acquire(); try { return await query(sql,parameters); } finally { release(); } },
    async connect() { const release = await acquire(); return { query, release }; },
    async end() { await db.close(); }
  };
}
