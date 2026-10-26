// Bundles carry their source revision. Native deployments can supply the same
// public identifier explicitly. Never infer it from a request or expose env vars.
const revision = typeof __ROADCONQUEST_SERVER_REVISION__ === 'undefined'
  ? process.env.ROADCONQUEST_SERVER_REVISION : __ROADCONQUEST_SERVER_REVISION__;
export const SERVER_INFO = Object.freeze({ api_version: 1,
  revision: typeof revision === 'string' && /^[a-f0-9]{40}$/i.test(revision) ? revision.toLowerCase() : null });
