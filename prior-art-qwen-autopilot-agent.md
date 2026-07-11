# Prior Art Report: Qwen Cloud Global AI Hackathon — Track 4 (Autopilot Agent) / AfyaSignal Autopilot

## What we're solving

Build an agent that autonomously completes a complex, multi-step real-world workflow end-to-end, handling ambiguous inputs, invoking external tools, and incorporating human-in-the-loop (HITL) checkpoints — judged with emphasis on production-readiness over toy demos. Hard constraints: must use Qwen models on Qwen Cloud (OpenAI-compatible endpoint at dashscope-intl.aliyuncs.com), backend must demonstrably run on Alibaba Cloud (recorded proof required), public open-source repo with visible license, architecture diagram, ~3 min video. Judging includes Technical Depth (30%) which explicitly rewards "sophisticated use of Qwen Cloud APIs like custom skills and MCP integrations." Deadline: July 9, 2026, 2:00pm PDT. Existing projects may be extended (the submission form has a "New or Existing" field). Only ~111 registered participants across 5 tracks at time of research.

## Search coverage

Searched across: past Qwen/Alibaba hackathons and winners; the official hackathon resources page (track sample projects, reference docs); the Qwen-Agent framework (GitHub + official docs); Spring AI Alibaba (GitHub, java2ai docs, Aliyun developer articles); HITL agent design patterns (Spring AI, LangGraph4j, LangChain, practitioner blogs); AI triage + community health worker prior art (medRxiv/JMIR D-CCC papers); and winning agentic hackathon projects (AWS AI Agent Global Hackathon winner EcoLafaek in depth, Microsoft AI Agents Hackathon 2025 winners, Agno Global Agent Hackathon). ~8 searches, 3 deep fetches, ~15 substantive sources.

Notable gap: **this is the first Qwen Cloud hackathon of this series**, so there are no past winners of this exact event to study. The project gallery is not yet published, so current competitors are invisible. The closest proxies are (a) an April 2026 "Qwen AI Build Day" (Vietnam) whose guidance emphasized "useful, clear, well executed — not just ambitious ideas" and warned that "a strong idea with a weak submission will lose to a clear project judges can evaluate quickly," and (b) other 2025–2026 global agent hackathons.

---

## Approach 1: Ecosystem-native Java — Spring AI Alibaba Agent Framework inside the existing AfyaSignal Spring Boot app

- **Source:** https://github.com/alibaba/spring-ai-alibaba ; HITL tutorial: https://java2ai.com/en/docs/1.0.0.2/tutorials/graph/human-in-the-loop/ ; worked HITL hook example: https://developer.aliyun.com/article/1721819 ; examples repo: https://github.com/spring-ai-alibaba/examples
- **Core idea:** Alibaba's own agentic AI framework for Java developers. Add it as Maven dependencies to AfyaSignal itself — no new service, no language switch. The triage autopilot becomes a `ReactAgent` (or a Graph workflow) living inside the Spring Boot app, calling Qwen via the DashScope starter.
- **Mechanics (replicable detail):**
  - Dependencies: `com.alibaba.cloud.ai:spring-ai-alibaba-agent-framework` (1.1.2.0) + `spring-ai-alibaba-starter-dashscope` (1.1.2.1). JDK 17+. API key via `AI_DASHSCOPE_API_KEY`.
  - Agent construction: `ReactAgent.builder().model(chatModel).tools(List.of(...)).saver(...)`. Tools are plain Java ToolCallbacks — meaning AfyaSignal's existing services (patient lookup, symptom records, referral creation, SMS/notification) become agent tools with a few annotations.
  - **HITL is a first-class primitive:** `HumanInTheLoopHook.builder().approvalOn(Map.of("createReferral", ToolConfig...))` gates named tools. When the agent tries to call a gated tool, execution interrupts and returns `InterruptionMetadata` with the pending tool call; state is checkpointed via a Saver (MemorySaver for dev, Redis/DatabaseSaver for production) keyed by a threadId; after the nurse approves/edits/rejects, the run resumes from the checkpoint. The Graph API alternative uses `CompileConfig...interruptBefore("humanfeedback")` on an explicit human-feedback node.
  - Built-in workflow compositions if needed: SequentialAgent, ParallelAgent, LlmRoutingAgent, LoopAgent, SupervisorAgent. Graph runtime provides persistence, streaming, and export of the workflow to PlantUML/Mermaid — which is literally your architecture diagram deliverable, generated from code.
  - MCP support via Spring AI's MCP client/server starters (Nacos MCP registry starters exist too).
- **Why they did it this way:** Alibaba built it so Java/Spring shops can build production agents without leaving the JVM; it's used internally at Alibaba (JManus, DeepResearch, NL2SQL are all built on it).
- **Requirements:** JDK 17+, Maven, DashScope API key (your hackathon credits), the existing AfyaSignal codebase. Note AfyaSignal must be on a compatible Spring/Java version — check for Spring Boot version conflicts early.
- **Strengths for this competition:** Directly answers Technical Depth 30% — you'd be one of very few entrants using Alibaba's own agent framework rather than a raw OpenAI-SDK call; the HITL hook maps one-to-one onto the nurse checkpoint the track brief asks for; state persistence makes the "production-grade" claim credible; zero rewrite of AfyaSignal; the Mermaid/PlantUML export gives you a code-generated architecture diagram.
- **Weaknesses / risks:** Newer framework with fast-moving APIs (version mismatches between docs and releases are a documented annoyance); smaller English-language community (much documentation is Chinese-first — the Aliyun article above is in Chinese, though the code is legible); if you hit a framework bug 2 days before deadline there's less StackOverflow to save you. Mitigation: the examples repo has a working `human-node` module to copy from.

## Approach 2: Python sidecar — official Qwen-Agent framework as a microservice next to Spring Boot

- **Source:** https://github.com/QwenLM/Qwen-Agent ; MCP guide: https://qwenlm.github.io/Qwen-Agent/en/guide/core_moduls/mcp/ ; https://qwen.readthedocs.io/en/latest/framework/qwen_agent.html
- **Core idea:** Use QwenLM's official Python framework (`pip install qwen-agent[gui,rag,code_interpreter,mcp]`) in a small FastAPI service that Spring Boot calls over HTTP. The Python service owns the agent loop; Spring Boot owns the domain, data, and nurse UI.
- **Mechanics:**
  - Agent = `Assistant(llm=llm_cfg, function_list=tools)` where `llm_cfg = {'model': 'qwen3-max', 'model_type': 'qwen_dashscope'}` (reads `DASHSCOPE_API_KEY`).
  - Tools are defined three ways: an `mcpServers` config dict (e.g. sqlite, filesystem, fetch, memory MCP servers launched as subprocesses via npx/uvx), built-in tools (`code_interpreter` runs Python in Docker sandbox), or custom `BaseTool` subclasses (this is where AfyaSignal REST endpoints get wrapped).
  - Multi-step/multi-turn/parallel tool calling is handled by the framework's internal templates and parsers; Qwen-Agent is the actual backend of Qwen Chat, so it exercises the models exactly the way the Qwen team intends.
  - HITL is **not** a built-in primitive — you'd implement the pause/approve/resume yourself (e.g. the agent returns a "pending referral" object, Spring Boot stores it, nurse approves, a second call completes the action).
- **Why they did it this way:** It's the canonical showcase of Qwen's function calling/MCP/planning abilities, maintained by the model team itself.
- **Requirements:** Python 3.10+, Docker if using code_interpreter, Node.js/uv for MCP servers, an extra deployed service.
- **Strengths for this competition:** Deepest, most current alignment with Qwen's agentic features (MCP cookbooks, tool-call parsers tuned per model generation); using the official QwenLM framework is an easy story for "sophisticated Qwen API use"; MCP server subprocess config is nearly copy-paste.
- **Weaknesses / risks:** Splits the system into two services in a 5-day window (two deploys, two logs, CORS/auth between them, two things to prove running on Alibaba Cloud); hand-rolled HITL is exactly the part judges will scrutinize, and you'd be building it from scratch while Approach 1 gets it for free; MCP subprocess model (npx/uvx child processes) is awkward inside a cloud container and the docs themselves warn it's not sandboxed for production.

## Approach 3: Raw OpenAI-compatible API + custom orchestration (the default entrant path)

- **Source:** Hackathon resources page (https://qwencloud-hackathon.devpost.com/resources): base URL `https://dashscope-intl.aliyuncs.com/compatible-mode/v1`, "The API is OpenAI-compatible, so you can use the OpenAI SDK."
- **Core idea:** Call Qwen directly with the OpenAI SDK (or Spring AI's generic OpenAI client pointed at the compatible endpoint), write your own agent loop: prompt → parse tool call JSON → execute → feed result back → repeat.
- **Mechanics:** Standard chat-completions with `tools` schema; loop until no tool calls remain; persist conversation state yourself; HITL by intercepting specific tool names before execution.
- **Why people do it:** Fastest first API call (the hackathon's own quick-start pitches "first agent running in about 30 minutes"); zero framework risk; total control.
- **Requirements:** Almost nothing — any HTTP client.
- **Strengths for this competition:** Lowest integration risk; every failure is your own code, debuggable in minutes; fine as a fallback if a framework fights you.
- **Weaknesses / risks:** This is what the majority of the ~111 entrants will do, so it scores near zero differentiation on Technical Depth ("sophisticated use of Qwen Cloud APIs like custom skills and MCP integrations" is precisely what this approach doesn't demonstrate); hand-rolled multi-step loops are where demo-day failures live (malformed tool-call JSON, lost state); you'd be rebuilding, worse, what Approaches 1 and 2 ship for free.

## Approach 4: The winning-submission archetype — what EcoLafaek did (pattern, not framework)

- **Source:** Devpost writeup: https://devpost.com/software/ecolafaek ; docs: https://docs.ecolafaek.com/ ; AWS winners announcement: https://aws-agent-hackathon.devpost.com/updates/38140-congratulations-to-the-winners-of-the-aws-ai-agent-global-hackathon
- **Core idea:** EcoLafaek — a citizen waste-reporting platform for Timor-Leste built by a solo developer — won the AWS AI Agent Global Hackathon over 625 agents from 9,500 developers across 127 countries. It's the closest analogue on record to AfyaSignal Autopilot: developing-country social impact + real deployed system + autonomous agent layered on top of an existing domain app.
- **Mechanics of why it won (extracted from their writeup and coverage):**
  - **Multi-round autonomous tool chaining as the centerpiece demo:** a single user request ("Show waste trends and map hotspots") visibly triggers SQL query → chart generation → hotspot query → interactive map → synthesized response. The chaining IS the demo.
  - **Multimodal reasoning on real inputs:** image classification of citizen waste photos via the platform's multimodal model, returning structured JSON — no custom ML training.
  - **Real usage data, live links:** 50–100+ genuine citizen reports, 13 identified hotspots, a live dashboard and live agent chat anyone (including judges) could click during judging.
  - **Production signals:** JWT auth, rate limiting, offline-first mobile sync, sensible deploy split, a full docs site with architecture diagrams. One reviewer: "This is production-grade AI engineering — not a hackathon demo."
  - **Authentic founder story:** solo builder from the country affected, problem grounded in hard numbers (300 tons of waste daily in Dili, 100+ uncollected — from a JICA survey).
- **Requirements:** An already-real system (you have one), real or realistic seeded data, a public deployment, disciplined documentation.
- **Strengths for this competition:** This archetype is directly transplantable: AfyaSignal is real, Kenyan, health-critical, and already deployed; the nurse HITL checkpoint is a *stronger* production story than EcoLafaek had; Track 4's official sample ideas are all dev-tooling (code review, data analyst, self-healing deploys), so a life-adjacent health workflow will stand out on Problem Value.
- **Weaknesses / risks:** The archetype demands the demo actually work live and the chaining be visible — a half-migrated backend or a mocked tool call undermines the whole story; it also demands docs/video polish, which competes for the same 5 days as the code.

---

## Where approaches agree

1. **HITL must be orchestrator-defined, checkpointed, and resumable — never "the LLM decides when to ask."** Spring AI Alibaba (`approvalOn` + `InterruptionMetadata` + Saver), LangGraph4j (`approvalOn` + `updateState`), LangChain (interrupt middleware + checkpointer), and practitioner writeups all converge on: predefined gates on named high-risk tools, state persisted at the pause, explicit approve/edit/reject, resume from checkpoint. Healthcare guidance (FDA framing cited in HITL literature) reinforces judging the human+AI team, not the model alone. For AfyaSignal: gate `createReferral` / `sendPatientSMS` / `escalateCase`; never gate read-only lookups.
2. **Visible multi-step tool chaining is what "sophisticated agent" means to judges.** Every winning project examined (EcoLafaek, RiskWise at Microsoft's hackathon) demos an autonomous chain of 3+ heterogeneous tools from one input.
3. **Real deployment + real data + live links beat ambition.** The Qwen Build Day rubric said it outright; EcoLafaek proved it globally. Judges click links.
4. **Structured JSON out of the model, deterministic code around it.** Nobody who won let free-text drive actions.

## Where approaches diverge — the real decision points

1. **Java-native (Approach 1) vs Python sidecar (Approach 2) vs raw API (Approach 3)?** The trade is framework risk vs differentiation vs system complexity. Approach 1 keeps one codebase and gets HITL free but bets on a young framework; Approach 2 uses the model team's own framework but doubles your services and makes you hand-build the checkpoint; Approach 3 is safest to debug and weakest to judge. With 5 days and your Spring Boot fluency, this is really a question of: how much do you trust yourself to debug a new Java framework fast vs stand up and secure a second service fast?
2. **How much of AfyaSignal moves to Alibaba Cloud?** The rules require recorded proof the *backend* runs on Alibaba Cloud. Options range from full migration (Render → ECS + RDS PostgreSQL) to a minimal path (deploy the Spring Boot app on one ECS instance, keep Vercel frontend). Full migration is a cleaner story; minimal is a smaller time bomb. Nobody researched answers this for you — it's a time-budget call, and it's the single biggest schedule risk either way, so it should be Day 1, not Day 4.
3. **How wide should the autopilot's tool belt be?** EcoLafaek won with ~4 chained tool types. Candidates for AfyaSignal: triage-history SQL/analytics, referral drafting (gated), patient SMS (gated), CHW guidance lookup (RAG), possibly multimodal photo triage (wound/rash image → structured assessment — this would mirror EcoLafaek's most-praised feature using Qwen-VL). Each tool adds demo wow and demo fragility in equal measure. Where's your reliability line for a 3-minute video?
4. **MCP: real integration or skip?** Technical Depth explicitly names MCP. Approach 1 can expose AfyaSignal itself as an MCP server (Spring AI MCP server starter) or consume one; Approach 2 gets MCP via subprocess config. A genuine MCP integration is a rubric checkbox almost no one at 111 participants will hit well — but a token, non-load-bearing MCP bolt-on could read as gaming the rubric. Do you make MCP structural or leave it out honestly?

## Open questions / what I couldn't verify

- The project gallery for this hackathon is unpublished, so the actual competitive field on Track 4 is unknown (registration says 111 participants total across 5 tracks).
- Whether hackathon Qwen Cloud credits cover Alibaba Cloud *compute* (ECS) or only model API usage — this determines migration cost. Verify via the voucher form / Discord immediately.
- Exact judging weight split beyond Technical Depth 30% (the other criteria — innovation, problem value, presentation — appear in summaries but I couldn't confirm exact percentages).
- Spring AI Alibaba version compatibility with AfyaSignal's current Spring Boot version — must be tested on Day 1.
- Qwen-VL multimodal availability/pricing on the intl DashScope endpoint under hackathon credits.

## Sources

**Hackathon / competitions**
- https://qwencloud-hackathon.devpost.com/ (rules, tracks, deliverables)
- https://qwencloud-hackathon.devpost.com/resources (quick start, sample projects, API base URL)
- https://qwencloud-hackathon.devpost.com/project-gallery (unpublished; confirms New/Existing filter)
- https://qwen-ai-build-day.devpost.com/ (April 2026 Vietnam edition — judging philosophy)
- https://www.qwencloud.com/challenge/hackathon (official site)

**GitHub / frameworks**
- https://github.com/alibaba/spring-ai-alibaba (Agent Framework, HITL, Graph runtime)
- https://github.com/spring-ai-alibaba/examples (human-node module, chatbot examples)
- https://github.com/QwenLM/Qwen-Agent (official Python agent framework)
- https://qwenlm.github.io/Qwen-Agent/en/guide/core_moduls/mcp/ (MCP config mechanics)
- https://github.com/spring-ai-alibaba/spring-ai-extensions (DashScopeChatModel, MCP registry)

**Papers & blogs**
- https://java2ai.com/en/docs/1.0.0.2/tutorials/graph/human-in-the-loop/ (interruptBefore graph pattern)
- https://developer.aliyun.com/article/1721819 (HumanInTheLoopHook worked example, Chinese)
- https://medium.com/@ali.gelenler/human-in-the-loop-for-ai-agents-a-checkpoint-based-pause-resume-pattern-with-spring-ai-134700afc36c (orchestrator-driven checkpoints rationale)
- https://dev.to/bsorrentino/langgraph4j-implementing-human-in-the-loop-at-ease-565h (approvalOn/updateState pattern)
- https://www.ncbi.nlm.nih.gov/pmc/articles/PMC9021941/ (D-CCC: HITL AI platform for community health workers — academic anchor for your problem framing)
- https://devpost.com/software/ecolafaek + https://docs.ecolafaek.com/ (winning archetype, full mechanics)
- https://aws-agent-hackathon.devpost.com/updates/38140-congratulations-to-the-winners-of-the-aws-ai-agent-global-hackathon (what judges praised)
- https://techcommunity.microsoft.com/blog/azuredevcommunityblog/ai-agents-hackathon-2025-%E2%80%93-category-winners-showcase/4415088 (Microsoft 2025 winners — RiskWise et al.)
