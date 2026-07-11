# AfyaSignal Autopilot — Implementation Plan

**Competition:** Qwen Cloud Global AI Hackathon — Track 4: Autopilot Agent
(https://qwencloud-hackathon.devpost.com/ · resources: https://qwencloud-hackathon.devpost.com/resources)

**Deadline: EXTENDED to July 20, 2026 @ 2:00pm PDT** (updated 2026-07-11; originally tighter). Participation has grown to 7,500+ entrants.

**Judging criteria (explicit as of 2026-07-11):**
| Criterion | Weight | Notes |
|---|---|---|
| Technical Depth | 30% | Names MCP integrations verbatim — the Phase 2 "stretch" MCP server item is worth reconsidering for Phase 3/4, not just a token bolt-on. |
| Innovation & Architecture | 30% | Modularity, error handling, clean code — **error handling and tests are scoring surface now, not nice-to-haves.** |
| Problem Value | 25% | |
| Presentation | 15% | |

**Companion document:** `prior-art-qwen-autopilot-agent.md` (prior-art research report — read it for the reasoning behind every architectural decision below, the winning-submission archetype, and all source links).

---

## 1. What we are building

An autonomous triage agent ("Autopilot") layered on top of the existing AfyaSignal platform. A Community Health Volunteer (CHV) submits an ambiguous, free-text patient report. The agent autonomously chains tools — patient triage history lookup → risk reasoning → referral drafting — then **pauses at a human-in-the-loop checkpoint** for a nurse to approve, edit, or reject before any real-world action (referral creation, CHV notification) executes. On approval, the agent resumes from persisted state and completes the workflow.

The demo centerpiece is the visible chain: ambiguous input → multi-step tool use → interrupt → nurse approval → action fires. This maps one-to-one onto the Track 4 brief (end-to-end workflow automation, ambiguous inputs, external tools, HITL checkpoints, production-readiness over toy demos).

## 2. Architecture (two services, one host)

```
CHV report (web/USSD-style form)
        │
        ▼
AfyaSignal  (EXISTING — Spring Boot 4.0.6, Java 21, PostgreSQL, JWT auth)
   domain API: patients, triage records, referrals, notifications
        ▲   REST (JWT service account)
        │
afyasignal-autopilot  (NEW — Spring Boot 3.5.x, Java 21)
   Spring AI Alibaba Agent Framework (ReactAgent + HumanInTheLoopHook)
   DashScope starter → Qwen model via dashscope-intl endpoint
   Nurse approval endpoints + minimal approvals page
        │
        ▼
Qwen Cloud (model API)

Deployment: docker-compose (afyasignal + autopilot + postgres) on ONE
Alibaba Cloud ECS instance. Frontend may remain on Vercel.
```

**Non-negotiable constraint discovered during research:** AfyaSignal is on Spring Boot 4 (Jackson 3, Spring Framework 7). Spring AI Alibaba 1.1.2.x is built on Spring AI 1.1.x, which requires Spring Boot 3.5.x. The two CANNOT share a classpath. Therefore the agent lives in a **separate Boot 3.5 service** and AfyaSignal is **not modified** beyond (possibly) adding a service-account credential and any small missing API endpoints. Do not attempt to add Spring AI Alibaba dependencies to the AfyaSignal pom.

## 3. Fixed scope (do not expand)

Three agent tools only:

| Tool | Calls (AfyaSignal API) | Gated? |
|---|---|---|
| `getPatientTriageHistory` | GET patient + triage records | No (read-only) |
| `draftReferral` | POST referral (draft state) | **Yes — nurse approval** |
| `notifyCHV` | POST notification/SMS (may be simulated) | **Yes — nurse approval** |

Explicitly out of scope: multimodal/photo triage, voice, multi-agent supervisor patterns, new frontend frameworks, migrating AfyaSignal itself to a new Boot version. If an idea is not on this list, it goes in the README's "Future work" section, not in the code.

---

## Phase 0 — Accounts, credits, repo (no code)

- [x] Create Qwen Cloud API key at home.qwencloud.com → API Keys. Store as `DASHSCOPE_API_KEY` (never commit it). Verified working against `dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions` (2026-07-08).
- [x] Claim the hackathon credit voucher via the coupon form linked on the Devpost resources page, using the same email as the Devpost registration. Verify it appears under Billing.
- [x] Sign up / verify **Alibaba Cloud International** (alibabacloud.com) — verified, $90 ECS free-trial credit confirmed (2026-07-08).
- [x] Create public GitHub repo `afyasignal-autopilot` with an **Apache-2.0 LICENSE file** — https://github.com/Mwaisaks/afyasignal-autopilot, license visible via `gh repo view`.
- [x] Decide: is the AfyaSignal repo itself made public too? — already public (confirmed via GitHub API).
- [ ] Join the hackathon Discord; ask whether credits cover ECS compute or model API only.

**Exit criteria:** API key works (one successful curl to the compatible-mode endpoint), voucher visible, Alibaba Cloud account verified or in progress, repo exists with license.

Smoke test call (endpoint from the official resources page):

```bash
curl https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions \
  -H "Authorization: Bearer $DASHSCOPE_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model": "qwen-plus", "messages": [{"role":"user","content":"ping"}]}'
```

---

## Phase 1 — Framework spike (go/no-go gate)

Goal: prove the interrupt → approve → resume cycle works with Spring AI Alibaba before committing to it. This phase is a **timeboxed spike**; treat failure as information, not defeat.

- [x] Scaffold `afyasignal-autopilot`: Spring Boot **3.5.16** parent, Java 21. Repo: https://github.com/Mwaisaks/afyasignal-autopilot. Deps used (all pinned to `1.1.2.2` via 3 BOM imports — `spring-ai-bom` 1.1.2, `spring-ai-alibaba-extensions-bom` + `spring-ai-alibaba-bom` 1.1.2.2):
  - `com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework`
  - `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`
  - `spring-boot-starter-web`
  - Both artifacts confirmed present on Maven Central at 1.1.2.2 (repo1.maven.org, not just search.maven.org's lagging index — check `maven-metadata.xml` directly if search.maven.org shows 0 results).
- [x] Cloned https://github.com/alibaba/spring-ai-alibaba and https://github.com/spring-ai-alibaba/examples locally as reference (deleted after extracting the patterns needed — re-clone if more reference is needed later). Copied the exact API from `HumanInTheLoopTest.java` and `HumanInTheLoopExample.java` in that repo.
- [x] Built a `ReactAgent` with one dummy tool `draftReferral`, gated via `HumanInTheLoopHook` — see `src/main/java/com/afyasignal/autopilot/config/AgentConfig.java`.
- [x] Invoked via threadId-bearing `RunnableConfig` (`TriageController`, `POST /api/triage` + `POST /api/triage/{threadId}/decision`); confirmed `InterruptionMetadata` returned on first call, resume via `RunnableConfig.builder().addHumanFeedback(...)` completes the run. Tested APPROVED and REJECTED paths live against the real Qwen key (2026-07-08) — both worked, including the agent sensibly adapting its final answer after a rejection.
- [x] Confirmed the DashScope starter's default base-url is the **CN** endpoint (`https://dashscope.aliyuncs.com`, verified via bytecode inspection of `DashScopeApiConstants`) — overridden via `spring.ai.dashscope.base-url: https://dashscope-intl.aliyuncs.com` in `application.yml`. Property prefix is `spring.ai.dashscope.*` (confirmed via `DashScopeConnectionProperties`/`DashScopeChatProperties` `@ConfigurationProperties`).

**GOTCHA found (not in original plan):** a tool with a single bare `String` input type causes the resume call to fail with `HTTP 400 InvalidParameter: "function.arguments" parameter ... must be in JSON format` — DashScope's function-calling backend requires tool arguments to round-trip as a JSON *object*, not a bare JSON string literal. Fix: give every gated tool a record/DTO input type (e.g. `record DraftReferralArgs(String patientSummary, String reason)`), never `inputType(String.class)`. Apply this to `draftReferral` and `notifyCHV` in Phase 2 as well.

**Exit criteria (GO): MET.** interrupt fires on the gated tool, state persists across the pause (via `MemorySaver` + threadId), resume completes the run. Verified live, not just unit-tested.

**FALLBACK (if the framework cannot be made to work within the spike):** switch to plain **Spring AI 2.0.0-M4 inside a Boot 4 service** (or the OpenAI SDK directly) against the compatible-mode endpoint, with a hand-rolled gate: agent loop intercepts calls to `draftReferral`/`notifyCHV`, writes a `pending_approval` row, halts; approval endpoint executes the stored call and re-invokes the loop with the tool result. Everything else in this plan stays identical. Record the fallback decision in the README honestly — judges respect engineering trade-offs.

---

## Phase 2 — Real tools + persistence + nurse approval surface

- [x] Replace dummy tools with real ToolCallbacks that call AfyaSignal's REST API using a service-account JWT — `AfyaSignalClient` + `AutopilotTools` in `afyasignal-autopilot`, backed by `ServiceAccountSeeder` in AfyaSignal (AUTOPILOT role, normal `/api/auth/login` flow).
- [x] `getPatientTriageHistory` (ungated): calls `GET /api/assessments/by-child/{childId}`, returns a compact per-assessment text summary to the model.
- [x] `draftReferral` (gated): resolves the child's assessment + a facility for their village internally (not exposed as separate tools), then `PATCH /api/assessments/{id}/referral` sets DRAFT status.
- [x] `notifyCHV` (gated): resolves the assessment's `chvId` internally, then `POST /api/notifications` with type `REFERRAL_ACKNOWLEDGED`.
- [ ] Swap `MemorySaver` for a persistent saver (database-backed if available in the framework version; otherwise persist `InterruptionMetadata` + threadId in a small `pending_approvals` table so approvals survive restarts).
- [ ] Nurse approval surface: REST endpoints `GET /approvals` (pending, with the agent's reasoning + proposed action payload) and `POST /approvals/{id}` (approve / reject, optional edited payload). A minimal server-rendered page or tiny addition to the existing AfyaSignal frontend is enough — clarity over beauty. (Today there's only `TriageController`'s generic `POST /api/triage/{threadId}/decision`, which requires already knowing the threadId — no listing endpoint yet.)
- [x] System prompt: `AgentConfig.SYSTEM_PROMPT` — structured reasoning order, tool preference, ambiguity-handling instruction, and explicit "approval is mandatory regardless of confidence" defense-in-depth line.
- [ ] Write 3–5 realistic demo scenarios as seed data (synthetic/anonymized Kenyan CHV reports, including at least one genuinely ambiguous input). Three were exercised ad hoc during manual verification (see below) but aren't persisted as seed data/fixtures yet.
- [ ] Integration test of the full happy path + one rejection path. Verified manually via curl instead (see below) — no automated test written yet.

**Exit criteria: MET, verified manually 2026-07-11.** Ran three scenarios end-to-end against a live local AfyaSignal (Postgres via Docker) + live Qwen key:
1. Clear emergency (fever/lethargy/difficulty breathing) → history fetched → both `draftReferral` and `notifyCHV` requested → approved → backend confirms `referralStatus: DRAFT` with real facility + CHV received the real notification.
2. Genuinely ambiguous report ("seems a bit off, not eating much") → agent pulled prior URGENT history but correctly declined to act, asked specific clarifying questions instead of guessing.
3. Clear-cut case → both tools requested → **rejected** with a reason ("nurse already treated on-site") → agent adapted its final answer, no referral was created (`referralStatus` stayed `null`), confirming rejection has no side effects.

Two pre-existing bugs surfaced and were fixed along the way: `AssessmentServiceImpl` and `FacilityServiceImpl` had no `@Transactional` anywhere, so with `open-in-view=false` any read touching the lazy `chv`/`referralFacility`/`manager` relations threw `LazyInitializationException` — affected every read path in both services, not just the new autopilot endpoints. Also had to add `AUTOPILOT` to the village-facility-lookup endpoint's allowed roles and expose `chvId` on `AssessmentResponse` (small missing pieces, not scope creep — `draftReferral`/`notifyCHV` need them).

**Note:** the DashScope/Qwen API key format has changed since Phase 0/1 — keys are now `sk-ws-...` with dot-separated segments (still work against the same `dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions` endpoint), not the `sk-` + 32-hex-char format used before. Update Phase 0 notes if this trips up a future session.

**Stretch, re-scoped 2026-07-11:** now that Technical Depth (30%) explicitly names MCP integrations, exposing the autopilot's tools as an MCP server via Spring AI's MCP server starter is worth doing in Phase 3/4 if time allows — not a token bolt-on anymore, a named rubric line. Still skip without guilt if it would come at the cost of a reliable demo; an honest omission beats a flaky flex.

---

## Phase 3 — Alibaba Cloud deployment + proof recording

- [ ] Dockerfiles for both services; `docker-compose.yml` with `afyasignal`, `autopilot`, `postgres` (+ volumes, healthchecks, env files).
- [ ] Provision ONE small ECS instance (Ubuntu, 2 vCPU / 4 GB is plenty), open ports 80/443/8080 in the security group, install Docker + compose.
- [ ] Deploy; run DB migrations; load seed data; smoke-test the full demo path on the live instance.
- [ ] **Record the Alibaba Cloud deployment-proof video immediately** (this is a separate, required recording from the demo video): show the ECS console with the instance, SSH in, `docker compose ps`, hit the live endpoint. Also capture the link to a code file demonstrating Alibaba Cloud / Qwen Cloud service usage as the rules require.
- [ ] Run the demo path start-to-finish at least 3 times on the live deployment; fix flakiness now, not on submission day. Add one retry/timeout guard around the model call (production-readiness signal + demo insurance).

**Exit criteria:** live URL works, proof recording saved and backed up, demo path is boringly reliable.

---

## Phase 4 — Submission assets

- [ ] **Architecture diagram:** if on the Graph API, export Mermaid/PlantUML from the framework itself and render it (a diagram generated by the running system is a Technical Depth flex); otherwise draw the two-service diagram from §2. Must show how Qwen Cloud connects to backend, database, and frontend (explicit requirement).
- [ ] **Demo video (~3 min, YouTube/Vimeo, public).** Script before recording:
  1. Problem with a hard number (Kenya's CHV-to-clinician reality; one stat, cited) — ~30s
  2. Live chain: ambiguous CHV report → agent reasons → pulls history → drafts referral — ~75s
  3. **The pause.** Nurse reviews the agent's reasoning and proposed action, approves; agent resumes, referral confirmed, CHV notified — ~45s
  4. Architecture flash + "runs on Alibaba Cloud, built on Spring AI Alibaba + Qwen" — ~20s
- [ ] **README:** what/why, architecture diagram, quickstart (env vars, compose up), tool list + gating table, HITL design rationale (orchestrator-defined checkpoints, persisted state — see prior-art report §"Where approaches agree"), honest limitations + future work.
- [ ] **Devpost submission:** text description, track = Track 4 Autopilot Agent, repo URL, video URL, deployment proof, diagram. Submit with a comfortable buffer before the deadline — Devpost forms fail at the worst moments.
- [ ] **Blog post (optional, separate $500 award):** the build journey — the Boot 4 compatibility landmine and the two-service solution is a genuinely good story. Publish on the usual platforms, add URL to submission.

**Exit criteria:** submitted, confirmed visible on Devpost, all links tested logged-out/incognito.

---

## Standing instructions for Claude Code sessions

1. Read this file and `prior-art-qwen-autopilot-agent.md` at the start of every session; state which phase and checklist items the session targets.
2. Never add spring-ai-alibaba dependencies to the AfyaSignal (Boot 4) pom. AfyaSignal changes are limited to: service-account credential, small missing endpoints, referral DRAFT state if absent.
3. Keep all `com.alibaba.cloud.ai` artifacts on one version line. On any `NoSuchMethodError`/`ClassNotFoundException`, suspect version skew first.
4. Prefer copying working patterns from the official examples repo over inventing API usage from memory — the framework is young and docs drift.
5. Secrets only via environment variables / `.env` (gitignored). The repo is public.
6. Scope guard: if a change isn't required by a checklist item in the current phase, don't make it. Log ideas under "Future work" in the README instead.
7. Every phase ends with its exit criteria demonstrably true (test, curl, or recording) before moving on.
