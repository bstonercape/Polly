# Organization-Enforced Groups

How might we tag an account/number as belonging to an organization, then use that attribute to create groups where only members of that organization can be added?

This document proposes several options, from lightweight client-only approaches to more robust designs.

---

## Background: How Groups Work Today

Signal GV2 groups are encrypted state machines stored on the Signal server. Key points relevant to this feature:

- **Member addition** goes through `GroupManagerV2.GroupEditor.addMembers()`, which resolves candidates via `GroupCandidateHelper`, then commits a change to the server via `GroupsV2Api.patchGroup()`.
- **Access control** is per-group: who can add members, edit attributes, use invite links, etc. Defined in `AccessControl` protobuf with levels `MEMBER`, `ADMINISTRATOR`, `UNSATISFIABLE`.
- **Announcement groups** already exist as a precedent for group-level policy flags (`isAnnouncementGroup` in `DecryptedGroup`). Only admins can post in announcement groups — enforced both client-side and on message receipt.
- **Group metadata** (title, description, avatar, access control) is encrypted in the group state. The `description` field is free-text.
- **Recipient attributes** are stored locally in `RecipientTable`. The `EXTRAS` column holds a `RecipientExtras` protobuf blob that can be extended without a schema migration.
- **Profile attributes** (name, about, emoji, badges) are synced via the Signal profile system and encrypted with the profile key.

Since Polly is a fork that doesn't control the Signal server, any solution must work **client-side only** — we can't add server-side validation of organization membership.

---

## Option A: Local Organization Tags (Client-Only, Simplest)

### Concept

The user manually tags contacts as belonging to an organization (or imports a list). When creating or editing an "org-only" group, the client blocks adding anyone who isn't tagged.

### How It Works

1. **Extend `RecipientExtras` protobuf** with an organization ID:
   ```protobuf
   message RecipientExtras {
       bool   manuallyShownAvatar = 1;
       bool   hideStory           = 2;
       int64  lastStoryView       = 3;
       string organizationId      = 4;  // e.g. "cape" or empty
   }
   ```
   This requires no database schema migration — `EXTRAS` is already a blob column that holds serialized `RecipientExtras`. The existing `updateExtras()` method in `RecipientTable.kt:3982` handles read-modify-write of this blob.

2. **Tag the user's own account** in `AccountRegistry` with an org ID (new column, or store in `RecipientExtras` for self).

3. **Extend group metadata** with an org policy. Two sub-options:
   - **Convention in description**: Prefix the group description with a machine-readable tag like `[org:cape]`. Cheap but fragile.
   - **Local-only group metadata**: Add an `orgRestriction` column to `GroupTable` that's purely local (not synced to the server). This is more robust.

4. **Enforce at `addMembers()`**: In `GroupManagerV2.GroupEditor.addMembers()`, before calling `commitChangeWithConflictResolution()`, check each candidate's `RecipientExtras.organizationId` against the group's org restriction. Reject with a user-facing error if mismatched.

5. **UI for tagging**: Add an "Organization" field in contact details or a bulk-import flow (CSV of phone numbers -> tag them all with org ID).

### Pros
- No server changes needed
- No protocol changes
- Can be implemented in a few files
- Works today with existing Signal server

### Cons
- **Client-side only enforcement** — other group members using stock Signal (or other forks) can still add anyone. The restriction is only enforced on the Polly client.
- **Manual tagging burden** — someone has to mark which contacts are in the org.
- **No cross-device sync** — tags live in the local `RecipientExtras` blob, which isn't synced via Storage Service (unless we also extend that).
- **No cryptographic proof** — the org tag is self-asserted; there's no verification that a contact actually belongs to the org.

### Files to Change

| File | Change |
|------|--------|
| `Database.proto` | Add `organizationId` field to `RecipientExtras` |
| `RecipientTable.kt` | Add `setOrganizationId()` / `getOrganizationId()` helpers using existing `updateExtras()` |
| `GroupTable.kt` | Add local `orgRestriction` column (or use group description convention) |
| `GroupManagerV2.java` | Add org check in `addMembers()` before commit |
| `AddMembersActivity.java` / contact picker | Filter or warn when selecting non-org contacts for an org group |

---

## Option B: Shared Organization Roster via Group (Self-Bootstrapping)

### Concept

Use a special Signal group as the "org roster." Membership in this roster group = membership in the organization. Org-restricted groups check whether a candidate is a member of the roster group.

### How It Works

1. **Create a "roster group"** — a normal GV2 group whose only purpose is to define org membership. An admin manages it like any other group (add/remove members).

2. **Store the roster group ID** locally as a setting (e.g., in `SignalStore` or `AccountRegistry`). The UI provides a way to designate a group as "this is my org roster."

3. **When creating an org-enforced group**, tag it locally (same as Option A — local column or description convention).

4. **Enforce at `addMembers()`**: For each candidate, check whether they appear in the roster group's member list (query `GroupTable` / `MembershipTable` for the roster group ID). Block if not found.

5. **Roster stays in sync automatically** — since it's a real Signal group, membership changes propagate to all devices via the normal GV2 protocol. When an admin adds someone to the roster group, every Polly client sees the update.

### Pros
- **No manual per-contact tagging** — org membership is defined by group membership, managed in one place.
- **Cross-device sync for free** — the roster group syncs via Signal's existing GV2 protocol.
- **Familiar UX** — admins already know how to manage group members.
- **No server changes**.

### Cons
- **Still client-side enforcement only** — stock Signal clients don't know about the roster convention.
- **Roster group is visible** — it shows up in the chat list (could be mitigated by auto-archiving/muting it).
- **Group size limits** — Signal groups max out at ~1000 members. Fine for small/mid orgs, not for large enterprises.
- **Circular dependency risk** — need to make sure the roster group itself isn't org-restricted, or you can't bootstrap it.

### Files to Change

| File | Change |
|------|--------|
| `SignalStore` or `AccountRegistry` | Store roster group ID |
| `GroupTable.kt` | Add local `orgRestriction` column |
| `GroupManagerV2.java` | Check roster group membership in `addMembers()` |
| Settings UI | "Designate org roster group" picker |
| Group creation UI | "Restrict to organization" toggle |

---

## Option C: Organization Credential via Profile Field

### Concept

Use the Signal profile's encrypted `about` field (or a new custom field) to carry an organization claim. A shared secret (e.g., an org-specific token or domain signature) serves as the credential. When adding members to an org group, the client fetches their profile and verifies the credential.

### How It Works

1. **Org credential format**: Define a convention for the profile `about` field (or a new field stored locally). For example, the about text could include a signed token: `[org:cape:sig=<base64>]`. The signature is produced by a shared org signing key.

2. **Distribute an org signing key** out-of-band (e.g., admin generates a keypair, shares the private key with org members via a config file or QR code).

3. **Org members set their credential** — the Polly app signs their ACI with the org private key and embeds the signature in their profile.

4. **Verification at `addMembers()`**: Before adding a member to an org group, fetch their profile via `ProfileUtil`, extract the org token, and verify the signature against the org public key. Reject if invalid or absent.

5. **Store the org public key** locally (and optionally in `AccountRegistry` for multi-account).

### Pros
- **Cryptographic proof** — unlike Option A, the org claim is verifiable. You can't fake membership without the signing key.
- **No roster group needed** — each user carries their own credential.
- **Scales to any org size** — no group member limits.
- **Cross-device** — the credential lives in the Signal profile, which syncs.

### Cons
- **Key distribution problem** — need a secure out-of-band channel to give org members the signing key. If it leaks, anyone can claim membership.
- **Abuses the `about` field** — embedding machine-readable data in a user-visible field is ugly. A custom local field avoids this but then it doesn't sync.
- **Profile fetch latency** — verifying a credential requires fetching the candidate's profile, which is async and can fail.
- **Still client-side enforcement** — the server doesn't validate the credential.

### Files to Change

| File | Change |
|------|--------|
| `Database.proto` | Add org credential field to `RecipientExtras` (cached locally after profile fetch) |
| `RecipientTable.kt` | Store/read cached org credential |
| `ProfileUtil.java` | Parse org credential from profile on fetch |
| `GroupManagerV2.java` | Verify credential in `addMembers()` |
| Settings UI | Import org signing key, generate own credential |
| `SignalStore` | Store org public key + own private key |

---

## Option D: Domain-Verified Organization (Phone Number Based)

### Concept

The simplest possible heuristic: define the organization as "everyone with a phone number matching a set of patterns." For example, all numbers provisioned by a corporate phone plan share a common prefix or belong to a known set.

### How It Works

1. **Admin configures org phone patterns** — e.g., a list of number prefixes, a range, or an explicit allowlist of e164 numbers.

2. **Store the pattern set** locally in `SignalStore` or a dedicated config file.

3. **Enforce at `addMembers()`**: Check each candidate's `e164` against the pattern set. Block if no match.

4. **Optionally auto-tag recipients** whose phone numbers match, so the contact list shows org membership visually.

### Pros
- **Extremely simple** to implement.
- **No key management, no special setup for members** — if your number matches, you're in.
- **Works well for small orgs** with known phone number sets.

### Cons
- **Not cryptographically secure** — anyone who knows the pattern could register a matching number.
- **Phone numbers change** — people leave the org, get new numbers, etc.
- **Doesn't work for orgs without corporate phone plans** (BYOD).
- **Requires knowing everyone's number** — won't work for large/distributed orgs.

### Files to Change

| File | Change |
|------|--------|
| `SignalStore` or config | Store org number patterns/allowlist |
| `GroupManagerV2.java` | Check e164 in `addMembers()` |
| Settings UI | Configure patterns |

---

## Comparison

| | A: Local Tags | B: Roster Group | C: Profile Credential | C + Okta | D: Phone Patterns |
|---|---|---|---|---|---|
| **Setup effort** | Manual per-contact | Create one group | Key generation + distribution | User logs into Okta (self-service) | Configure number list |
| **Enforcement** | Client-only | Client-only | Client-only (verifiable) | Client-only (verifiable) | Client-only |
| **Cross-device sync** | No (local blob) | Yes (via GV2) | Partial | Yes (credential service is central) | Yes (if config is shared) |
| **Scales to large orgs** | Tedious | Up to ~1000 members | Unlimited | Unlimited | Depends on number management |
| **Proof of membership** | Self-asserted | Implied by group membership | Cryptographic signature | Cryptographic signature + Okta auth | Phone number match |
| **Revocation** | Manual | Remove from roster group | None (key is permanent) | Automatic via Okta + credential expiry | Manual |
| **Implementation size** | Small | Small-Medium | Medium | Medium (+ small external service) | Small |
| **Resilience to non-Polly clients** | None | None | None | None | None |

---

## Recommendation

**For a hackathon: Option B (Roster Group)** is the best balance of power and simplicity. It's a small implementation, gives you real cross-device sync, has a natural admin workflow (add/remove people from the roster group), and doesn't require key management or manual tagging. The main limitation (group size cap of ~1000) is fine for the expected use case.

**For a production feature: Option C with Okta** is the strongest design. It provides cryptographic proof of org membership, scales to enterprise, revokes automatically when people leave the org, and leverages existing Okta SSO infrastructure. The credential service is a small stateless API (~100 lines) that keeps the private key safe server-side. See the appendix for the full architecture.

**If you want both**: Start with Option B for the hackathon, then layer Option C + Okta on top later. The enforcement point (`addMembers()`) is the same in both cases — only the membership check changes (query roster group vs. call credential service + verify signature).

---

## Appendix: Option C with Okta Integration

The original Option C has a key distribution problem: how do you securely give every org member the signing key? If you distribute the private key directly, a single compromised device leaks it for everyone.

Okta solves this cleanly by keeping the private key server-side and using OIDC authentication to gate credential issuance.

### Architecture

```
┌──────────┐       OIDC login        ┌────────┐
│  Polly   │ ──────────────────────▶  │  Okta  │
│  App     │ ◀── access_token ──────  │        │
└────┬─────┘                          └────────┘
     │
     │  POST /credential
     │  Authorization: Bearer <okta_access_token>
     │  Body: { aci: "<user's ACI>" }
     │
     ▼
┌──────────────────────────────────────────────────┐
│            Credential Service                     │
│  (stateless API — Lambda, Cloud Function, etc.)   │
│                                                   │
│  1. Validate Okta access token (call /introspect  │
│     or verify JWT signature against Okta JWKS)    │
│  2. Check Okta group membership (e.g. "Signal-Org") │
│  3. Sign credential:                              │
│     Ed25519_sign(org_private_key,                 │
│       aci || org_id || issued_at || expires_at)   │
│  4. Store credential in DB (indexed by ACI)       │
│  5. Return { credential, org_id, org_public_key } │
└──────────────────────────────────────────────────┘
```

The credential service also doubles as a **directory** for verification:

- `POST /credential` — Authenticate via Okta, receive a signed credential for your ACI
- `GET /credential/{aci}` — Look up any user's credential by ACI (public, read-only)

### Credential Format

```
OrgCredential {
    aci:         bytes     // user's Signal ACI (UUID)
    org_id:      string    // e.g. "cape"
    issued_at:   int64     // unix timestamp
    expires_at:  int64     // unix timestamp (e.g. 30 days from issuance)
    signature:   bytes     // Ed25519 signature over (aci || org_id || issued_at || expires_at)
}
```

The credential is compact (~128 bytes) and long-lived. It proves "this ACI was a member of this org at issuance time, as verified by Okta."

### Verification Flow (at group member addition)

```
Adding a member to an org-restricted group:

1. Polly app calls GET /credential/{candidate_aci}
2. If 404 → candidate is not in the org → block addition
3. If 200 → receive OrgCredential
4. Verify Ed25519_verify(org_public_key, payload, signature)
5. Check expires_at > now
6. Check org_id matches group's org restriction
7. If all pass → allow addition
```

### How Revocation Works

When someone leaves the org (disabled in Okta):

- **Passive revocation**: Set credential expiry to 30 days (configurable). After expiry, the credential fails verification. When the user next tries to refresh, the credential service checks Okta and refuses to reissue.
- **Active revocation**: The credential service can delete the credential from its DB immediately when the Okta account is deactivated (via Okta webhook on `user.lifecycle.deactivate`). Subsequent `GET /credential/{aci}` returns 404.
- **Existing group membership**: Revocation prevents being *added* to new org groups. Removing someone from existing groups would require a separate "audit" sweep (the Polly app could periodically re-verify credentials of current group members).

### What the Credential Service Looks Like

It's a small stateless API (~100 lines). Example in pseudocode:

```python
# POST /credential
def issue_credential(request):
    # 1. Validate Okta token
    okta_user = okta_client.introspect(request.headers["Authorization"])
    if not okta_user.active:
        return 401

    # 2. Check org membership (Okta group)
    groups = okta_client.get_user_groups(okta_user.uid)
    if "Signal-Org" not in [g.name for g in groups]:
        return 403

    # 3. Sign credential
    aci = request.body["aci"]
    payload = aci + ORG_ID + now() + (now() + 30 days)
    signature = ed25519_sign(ORG_PRIVATE_KEY, payload)

    # 4. Store and return
    db.upsert(aci, credential)
    return { credential, org_id, org_public_key: ORG_PUBLIC_KEY }

# GET /credential/{aci}
def lookup_credential(aci):
    credential = db.get(aci)
    if not credential or credential.expired:
        return 404
    return credential
```

### Okta OIDC Integration in the Android App

Use the AppAuth library (already widely used for OIDC on Android):

```kotlin
// In a new OktaAuthManager class
val config = AuthorizationServiceConfiguration(
    Uri.parse("https://your-org.okta.com/oauth2/default/v1/authorize"),
    Uri.parse("https://your-org.okta.com/oauth2/default/v1/token")
)

val authRequest = AuthorizationRequest.Builder(config, CLIENT_ID, ResponseTypeValues.CODE, REDIRECT_URI)
    .setScope("openid profile groups")
    .build()

// Launch Chrome Custom Tab for login
// On callback, exchange code for tokens
// Use access token to call credential service
```

The login flow only needs to happen once (at credential issuance) and then again every 30 days when the credential expires. It's not in the critical path of normal app usage.

### App-Side Storage

| Data | Where to store | Notes |
|------|---------------|-------|
| Own credential (OrgCredential) | Per-account `SignalStore` | Refreshed every 30 days via Okta |
| Org public key | Per-account `SignalStore` | Received from credential service, rarely changes |
| Credential service URL | Per-account `SignalStore` or app config | e.g. `https://creds.yourorg.com` |
| Okta client ID / org URL | App config or `SignalStore` | Standard OIDC config |
| Cached peer credentials | `RecipientExtras` protobuf | Cache `GET /credential` results to avoid repeated lookups |

### Advantages Over Raw Key Distribution

| | Raw Option C | Option C + Okta |
|---|---|---|
| Private key exposure | Every org member has it | Only the credential service has it |
| Revocation | None (key is permanent) | Automatic via Okta deactivation + credential expiry |
| Provisioning | Manual (QR code, file share) | Self-service (user logs into Okta) |
| Audit trail | None | Okta logs every authentication |
| Org group management | Manual | Managed in Okta (existing IT workflows) |
| Scales to | Small orgs | Enterprise scale |

### Implementation Size

| Component | Effort | Notes |
|-----------|--------|-------|
| Credential service | Small | ~100 lines, stateless, one DB table |
| Okta OIDC in Polly app | Small-Medium | AppAuth library + login flow + token storage |
| Credential refresh logic | Small | Background job, re-authenticate if expired |
| Verification in `addMembers()` | Small | HTTP call + Ed25519 verify |
| Settings UI | Small | "Connect to organization" button, shows org name + expiry |
| **Total** | **Medium** | Most complexity is in the credential service (outside the app) |

### Dependencies

- **AppAuth Android library** (`net.openid:appauth`) — standard OIDC client for Android
- **Ed25519** — already available in Signal's `libsignal` (or use Tink/BouncyCastle)
- **Credential service hosting** — any serverless platform (AWS Lambda + DynamoDB, GCP Cloud Function + Firestore, etc.)
- **Okta tenant** — with an OIDC application configured and an "org membership" group

---

## Shared Implementation Work (All Options)

Regardless of which option you choose, these pieces are common:

1. **Group-level org flag**: Mark a group as org-restricted. Either a local column in `GroupTable` or a convention in the group description.

2. **Enforcement hook in `GroupManagerV2.GroupEditor.addMembers()`** (~line 287): After resolving candidates and before `commitChangeWithConflictResolution()`, validate each candidate against the org membership check. Throw a new `OrgMembershipRequiredException` (or show a user-facing dialog) on failure.

3. **UI gate in the contact picker**: When selecting members for an org-restricted group, visually indicate who is/isn't in the org. Optionally filter non-org contacts out entirely, or show a warning.

4. **Group creation UI**: Add a toggle during group creation — "Restrict to organization members." This sets the org flag on the group.

5. **Multi-account consideration**: In Polly, each account might belong to a different org (or no org). The org configuration should be per-account, stored either in `AccountRegistry` (new column) or in the per-account `SignalStore`.
