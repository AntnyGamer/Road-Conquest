import test from 'node:test';
import assert from 'node:assert/strict';
import pg from 'pg';
import { readFile } from 'node:fs/promises';
import { generateKeyPairSync } from 'node:crypto';
import { limitedApiDatabase } from './helpers/limited-api-role.mjs';

test('forum HTTP checkout can reuse the approved Play account while dedicated credentials take precedence',
  { skip: !process.env.DATABASE_URL }, async () => {
    const admin = new pg.Pool({ connectionString: process.env.DATABASE_URL });
    const originalFetch = globalThis.fetch;
    const runtimes = [];
    const keys = generateKeyPairSync('rsa', { modulusLength: 2048 });
    const credentials = email => JSON.stringify({ type: 'service_account', client_email: email,
      private_key: keys.privateKey.export({ format: 'pem', type: 'pkcs8' }) });
    let expectedEmail = 'shared@example.test', binding, oauth = 0, acknowledgements = 0;
    try {
      await admin.query(await readFile(new URL('../schema.sql', import.meta.url), 'utf8'));
      await admin.query(await readFile(new URL('../forum-schema.sql', import.meta.url), 'utf8'));
      await admin.query('TRUNCATE users,auth_rate_limits RESTART IDENTITY CASCADE');
      process.env.DATABASE_URL = await limitedApiDatabase(admin, process.env.DATABASE_URL);
      await admin.query(`GRANT SELECT,INSERT,UPDATE,DELETE ON forum_profiles,forum_posts,forum_reactions,
        forum_blocks,forum_reports,forum_purchases,forum_billing_events TO roadconquest_api_test`);
      process.env.FORUM_ENABLED = '1';
      process.env.FORUM_MODERATION_API_KEY = 'test-only';
      delete process.env.FORUM_RTDN_AUDIENCE;
      process.env.FORUM_RTDN_SERVICE_ACCOUNT_EMAIL = 'push@example.test';
      process.env.PLAY_SERVICE_ACCOUNT_JSON = credentials(expectedEmail);
      delete process.env.FORUM_PLAY_SERVICE_ACCOUNT_JSON;
      process.env.APPLY_SCHEMA_ON_STARTUP = '0';
      process.env.REQUIRE_HTTPS = '1';
      process.env.TRUST_PROXY = '1';
      process.env.PASSWORD_PEPPER ||= 'forum-billing-test-pepper-'.repeat(2);
      globalThis.fetch = async (url, init) => {
        if (url === 'https://oauth2.googleapis.com/token') {
          const assertion = new URLSearchParams(init.body).get('assertion');
          const claims = JSON.parse(Buffer.from(assertion.split('.')[1], 'base64url'));
          assert.equal(claims.iss, expectedEmail);
          assert.equal(claims.scope, 'https://www.googleapis.com/auth/androidpublisher');
          oauth++;
          return Response.json({ access_token: 'test-access', expires_in: 3600 });
        }
        assert.match(url, /^https:\/\/androidpublisher\.googleapis\.com\/androidpublisher\/v3\/applications\/com\.roadconquest\.app\/purchases\/products\/forum_plus_lifetime\/tokens\//);
        assert.equal(init.headers.Authorization, 'Bearer test-access');
        if (url.endsWith(':acknowledge')) {
          acknowledgements++;
          assert.equal(init.method, 'POST');
          return new Response(null, { status: 204 });
        }
        return Response.json({ purchaseState: url.endsWith('/pending') ? 2 : 0, consumptionState: 0,
          acknowledgementState: 0, obfuscatedExternalAccountId: binding });
      };
      const { createFetchHandler } = await import('../src/fetch-adapter.mjs');
      async function application(suffix) {
        const runtime = await import('../src/application.mjs?' + suffix);
        runtimes.push(runtime.pool);
        const handler = createFetchHandler(runtime.serve);
        return async (path, method = 'GET', body, token) => {
          const headers = { 'Content-Type': 'application/json', 'X-Forwarded-Proto': 'https', 'X-Forwarded-For': '192.0.2.1' };
          if (token) headers.Authorization = 'Bearer ' + token;
          const response = await handler(new Request('https://accounts.test/v1/' + path,
            { method, headers, body: body ? JSON.stringify(body) : undefined }));
          return { status: response.status, body: await response.json() };
        };
      }
      const unready = await application('billing-without-notifications');
      assert.equal((await unready('forum/posts')).body.purchases_available, false,
        'A shared Play credential alone must not enable checkout without refund notification identity');
      process.env.FORUM_RTDN_AUDIENCE = 'https://accounts.test/v1/forum/play-notifications';
      const shared = await application('shared-billing');
      assert.equal((await shared('forum/posts')).body.purchases_available, true);
      const first = await shared('signup', 'POST', { username: 'SharedBilling', password: 'correct horse battery staple' });
      assert.equal(first.status, 201);
      const firstToken = first.body.token;
      binding = (await shared('forum/posts', 'GET', undefined, firstToken)).body.viewer.billing_account_id;
      assert.deepEqual((await shared('forum/purchase', 'POST', { purchase_token: 'purchased' }, firstToken)).body, { plus: true });
      assert.equal((await shared('forum/posts', 'GET', undefined, firstToken)).body.viewer.plus, true);
      assert.equal(oauth, 1);
      assert.equal(acknowledgements, 1);

      const second = await shared('signup', 'POST', { username: 'DedicatedBilling', password: 'correct horse battery staple' });
      assert.equal(second.status, 201);
      const secondToken = second.body.token;
      assert.deepEqual((await shared('forum/purchase', 'POST', { purchase_token: 'pending' }, secondToken)).body, { plus: false });
      assert.equal(acknowledgements, 1, 'Pending payments are never acknowledged or granted');

      expectedEmail = 'dedicated@example.test';
      process.env.FORUM_PLAY_SERVICE_ACCOUNT_JSON = credentials(expectedEmail);
      const dedicated = await application('dedicated-billing');
      binding = (await dedicated('forum/posts', 'GET', undefined, secondToken)).body.viewer.billing_account_id;
      assert.deepEqual((await dedicated('forum/purchase', 'POST', { purchase_token: 'dedicated' }, secondToken)).body, { plus: true });
      assert.equal(oauth, 2, 'An explicit dedicated credential must override the still-configured shared account');
      assert.equal(acknowledgements, 2);
    } finally {
      globalThis.fetch = originalFetch;
      await Promise.all(runtimes.map(pool => pool.end()));
      await admin.end();
    }
  });
