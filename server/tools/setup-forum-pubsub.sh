#!/usr/bin/env bash
# Run in Google Cloud Shell as the project owner. No private keys are created.
set -euo pipefail

rc_project='deep-pursuit-439200-d4'
rc_number='792616069211'
rc_topic='roadconquest-forum-purchases'
rc_subscription='roadconquest-forum-push'
rc_push_name='roadconquest-forum-push'
rc_push_email="${rc_push_name}@${rc_project}.iam.gserviceaccount.com"
# Google-authenticated server traffic goes directly to the existing backend,
# avoiding a client-ingress Worker request and browser-oriented edge filtering.
rc_endpoint='https://br-withered-dew-b7u432kv-roadconquest.compute.c-13.us-east-1.aws.neon.tech/v1/forum/play-notifications'

gcloud services enable pubsub.googleapis.com iam.googleapis.com --project="$rc_project" --quiet
gcloud beta services identity create --service=pubsub.googleapis.com --project="$rc_project" --quiet

if ! gcloud iam service-accounts describe "$rc_push_email" --project="$rc_project" >/dev/null 2>&1
then
  gcloud iam service-accounts create "$rc_push_name" --display-name='Road Conquest billing notifications' --project="$rc_project" --quiet
fi

if ! gcloud pubsub topics describe "$rc_topic" --project="$rc_project" >/dev/null 2>&1
then
  gcloud pubsub topics create "$rc_topic" --project="$rc_project" --quiet
fi

# Google Play can publish only to this topic. The push identity has no project roles.
gcloud pubsub topics add-iam-policy-binding "$rc_topic" --project="$rc_project" \
  --member='serviceAccount:google-play-developer-notifications@system.gserviceaccount.com' \
  --role='roles/pubsub.publisher' --quiet

# Permit only OIDC identity tokens, scoped to the otherwise unprivileged push account.
gcloud iam service-accounts add-iam-policy-binding "$rc_push_email" --project="$rc_project" \
  --member="serviceAccount:service-${rc_number}@gcp-sa-pubsub.iam.gserviceaccount.com" \
  --role='roles/iam.serviceAccountOpenIdTokenCreator' --condition=None --quiet

if gcloud pubsub subscriptions describe "$rc_subscription" --project="$rc_project" >/dev/null 2>&1
then
  rc_existing_topic=$(gcloud pubsub subscriptions describe "$rc_subscription" --project="$rc_project" --format='value(topic)')
  if [[ "$rc_existing_topic" != "projects/${rc_project}/topics/${rc_topic}" ]]
  then
    echo 'The existing subscription belongs to another topic. No subscription was changed.' >&2
    exit 1
  fi
  gcloud pubsub subscriptions update "$rc_subscription" --project="$rc_project" \
    --push-endpoint="$rc_endpoint" --push-auth-service-account="$rc_push_email" \
    --push-auth-token-audience="$rc_endpoint" --clear-push-no-wrapper-config \
    --expiration-period=never --ack-deadline=60 --quiet
else
  gcloud pubsub subscriptions create "$rc_subscription" --project="$rc_project" --topic="$rc_topic" \
    --push-endpoint="$rc_endpoint" --push-auth-service-account="$rc_push_email" \
    --push-auth-token-audience="$rc_endpoint" --expiration-period=never --ack-deadline=60 --quiet
fi

gcloud pubsub subscriptions describe "$rc_subscription" --project="$rc_project" \
  --format='yaml(name,topic,pushConfig,expirationPolicy)'
echo 'Forum notification setup complete.'
