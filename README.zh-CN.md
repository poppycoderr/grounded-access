<p align="center">
    <img src="./assets/brand/logo.svg" alt="Grounded Access" width="140" />
</p>

<h1 align="center">Grounded Access</h1>

<p align="center">
    <b>面向企业知识库的权限感知、评测驱动检索系统。</b><br/>权限被编译进检索查询本身，每一次检索改动都要在公开评测上拿出证据。
</p>

<p align="center">
    <a href="https://github.com/poppycoderr/grounded-access/actions/workflows/build.yml"><img src="https://github.com/poppycoderr/grounded-access/actions/workflows/build.yml/badge.svg" alt="Build" /></a>
    <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue" alt="License" /></a>
    <img src="https://img.shields.io/badge/Java-21%20%7C%2025-ED8B00?logo=openjdk&logoColor=white" alt="Java 21 | 25" />
    <img src="https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1" />
    <img src="https://img.shields.io/badge/Python-3.12-3776AB?logo=python&logoColor=white" alt="Python 3.12" />
    <img src="https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-4169E1?logo=postgresql&logoColor=white" alt="PostgreSQL 17 + pgvector" />
    <img src="https://img.shields.io/badge/status-M0%20walking%20skeleton-8B5CF6" alt="Status" />
</p>

<p align="center">
    <a href="./README.md">English</a> · <b>简体中文</b> · <a href="./docs/architecture/overview.md">架构</a> · <a href="./docs/evaluation/strategy.md">评测</a> · <a href="./docs/project/milestones.md">里程碑</a>
</p>

---

## 亮点

- 🔐 **权限在查询内部执行**：身份被编译成一个 SQL 谓词，关键词通道与向量通道都带着它，未授权的数据不会进入重排、prompt、日志或 trace
- 🙈 **不泄漏存在性**：无权访问的内容与不存在的内容表现完全一致——同样的 404、同样的空回答，也没有「已过滤 3 条」这类计数
- 📊 **用数据说话**：带版本的语料、人工核对的标注、按字符区间判定的相关性，报告可由提交在仓库里的结果文件重新生成
- 🚦 **每个 PR 都有安全门禁**：出现一条越权结果就让构建失败
- 🧪 **诚实的 baseline**：关键词通道如实称为 PostgreSQL FTS 而不是 BM25；v0.1 的向量检索是精确检索，过滤不会悄悄损失召回
- 🔑 **无需 API key**：PostgreSQL、内置模型权重的 CPU embedding 服务、以及一份 demo 语料，`docker compose up` 即可
- 🧩 **真实的多语言边界**：Java 负责身份、授权、入库与检索；Python 通过带版本的 OpenAPI 契约负责模型计算，且不接触数据库
- 🤖 **面向 AI 协作**：`AGENTS.md` 与 `CLAUDE.md` 写明不可违反的规则，架构测试在被违反时直接让构建失败

> **当前状态：** M0（walking skeleton）已完成并在 CI 中运行。hybrid 检索、完整 ABAC 决策表、重排、带引用的回答与链路追踪是后续里程碑。下文未特别标注的内容均已实现。

## 为什么做这个项目

多数 RAG demo 先检索、再过滤。这会把数据泄漏到应用内存——并进一步进入重排、prompt 和日志——同时悄悄损失召回，因为过滤吃掉的正是索引已经选出的 top-k。

<p align="center">
    <img src="./assets/diagrams/ga-authorization.zh-CN.svg" alt="授权属于检索环节，不是事后过滤" />
</p>

## 快速开始

环境要求：Docker、[uv](https://docs.astral.sh/uv/)，首次构建镜像约 5 分钟。无需 API key，无需 GPU。

```bash
git clone https://github.com/poppycoderr/grounded-access.git
cd grounded-access

docker compose up -d --build --wait   # PostgreSQL + pgvector、控制面、CPU 模型服务
./scripts/load-demo                   # 导入 Northstar 与 Orbit Labs 两套虚构语料
./scripts/demo-queries                # 同一个问题，不同身份
./scripts/benchmark                   # 评测所有策略并执行安全门禁
```

以任意 demo 身份检索，或者自己签发 token 调用 API：

```bash
uv run --project packages/evaluation ga-eval search alice-engineer "How many paid volunteer days do EU employees receive?"

TOKEN=$(uv run scripts/mint-token.py alice-engineer --scope "query debug")
curl -s localhost:8080/api/v1/retrieval/search -H "Authorization: Bearer $TOKEN" \
  -H 'content-type: application/json' \
  -d '{"query":"paid volunteer days","strategy":"dense-only","k":3}'
```

## 实际效果

同一个问题，由两个不同租户的身份提问。除了 token，请求没有任何差别：

```text
alice-engineer (tenant northstar) · dense-only · policy tenant-only/1
  1. hr-volunteer-policy › Volunteer Time Off Policy > European Union
     Employees based in the EU receive two paid volunteer days per calendar year.
  2. hr-volunteer-policy › Volunteer Time Off Policy > United States
     Employees based in the US receive one paid volunteer day per calendar year.

mallory-outsider (tenant external) · dense-only · policy tenant-only/1
  1. volunteer-handbook › Community Volunteering Handbook > Volunteer days
     Orbit Labs employees receive three volunteer days per year, which can be taken as half days.
```

外部身份拿到的不是「权限不足」，也没有命中数量或文档标题。Northstar 的政策根本不存在于他的世界里。

## 检索评测

来自 walking skeleton 数据集的 CI 运行结果：15 条用例、10 篇文档、2 个租户。

| 策略 | 可回答用例 | Recall@5 | Recall@10 | MRR@10 | 越权结果 |
|---|---|---|---|---|---|
| `sparse-only`（PostgreSQL FTS） | 13 | 0.923 | 0.923 | 0.705 | **0** |
| `dense-only`（pgvector 精确检索） | 13 | 1.000 | 1.000 | 0.910 | **0** |

这是小规模虚构语料上的 demo benchmark，不代表生产效果。之所以公开，是因为方法比数字更重要：每次运行都记录数据集版本、commit、策略、policy 版本与运行平台，报告由提交在仓库中的结果文件生成。两个全新数据库与 GitHub runner 上的排名完全一致。

sparse 唯一的未命中是 *"What is the daily food budget for a business trip to Germany?"*——原文写的是 "meal allowance … inside the EU"，与问题没有任何共同词元。这正是 hybrid 检索要解决的问题，而它会被测量，而不是被假定。

证据以**文档版本 + 原文引用**标注，而不是 chunk id，因此不同切分策略可以在同一份标注上比较。方法见 [docs/evaluation/strategy.md](./docs/evaluation/strategy.md)。

## 架构

<p align="center">
    <img src="./assets/diagrams/ga-overview.zh-CN.svg" alt="Grounded Access 请求链路" />
</p>

| 组件 | 职责 |
|---|---|
| `apps/control-plane` | Java 21 / Spring Boot 4.1：验签、编译授权谓词、入库、检索、API |
| `apps/model-service` | Python 3.12 / FastAPI：当前提供 embedding，M3 加入重排；不持有身份，不访问数据库 |
| `packages/contracts` | 两者之间的 OpenAPI 契约，双侧都有漂移检测 |
| `packages/evaluation` | `ga-eval`：数据集校验、检索指标、安全门禁、报告 |
| `data/` | 虚构语料、manifest、demo 身份、评测用例与可见性标注 |

```text
io.groundedaccess
├── identity        # 验签 JWT → Principal（属性只来自 token）
├── authorization   # PolicyCompiler → 单一参数化 SQL 谓词
├── corpus          # 规范化 · 按标题切分 · 内容哈希版本管理
├── retrieval       # AuthorizedChunkQuery：chunk 表唯一的读取方
├── modelclient     # model-service 客户端：分批、超时、固定模型
└── api             # REST controller、作用域、问题响应
```

## 权限如何生效

身份被编译成带绑定参数的谓词（绝不做字符串拼接），两条通道嵌入的是同一个对象：

```sql
c.tenant_id = :auth_tenant_id
AND v.classification_rank <= :clearance_rank                                        -- M2
AND (cardinality(v.allowed_departments) = 0 OR :department = ANY(v.allowed_departments))
AND (cardinality(v.required_projects)  = 0 OR v.required_projects && :projects::text[])
```

租户、密级、部门、项目属于**授权**，计入安全门禁；region、有效期与文档状态属于**适用范围**，它们影响相关性，请求可以用 `asOf` 调整。把两者分开，安全指标才只统计真正的越权。完整决策表见 [docs/architecture/authorization.md](./docs/architecture/authorization.md)。

四道防线保证它不被绕过：

- 架构测试——只有 `AuthorizedChunkQuery` 可以读取 chunk 表；
- 真实 pgvector 上的集成测试——两条通道的跨租户隔离、无效与权限不足的 token；
- 评测安全门禁——候选结果与人工标注的可见性比较，而不是与编译器自己比较；
- 决策表的 property-based 测试（M2）。

## 路线图

| 里程碑 | 范围 | 状态 |
|---|---|---|
| **M0** Walking skeleton | demo 身份、Markdown 入库、sparse 与 dense 检索、租户隔离、评测 CLI、CI 冒烟 benchmark | ✅ 已完成 |
| **M1** 检索 baseline | 异步入库任务、chunker v1、RRF hybrid、数据集扩充与 dev/test 划分、bootstrap 置信区间 | ⏳ 进行中 |
| **M2** 授权 | 访问标签、完整 ABAC 决策表、适用范围过滤、审计事件、威胁模型 | 计划中 |
| **M3** 重排与回答 | 带降级的 cross-encoder 重排、上下文构建、结构化引用、拒答 | 计划中 |
| **M4** 运维与发布 | OpenTelemetry trace、dashboard、故障与压力测试、v0.1 benchmark 报告 | 计划中 |

第一阶段明确不做：知识图谱与 GraphRAG、自主 Agent、更多向量数据库、OCR 与多模态、模型微调、Kubernetes 与多云、低代码编排。详见 [docs/project/milestones.md](./docs/project/milestones.md)。

## 文档

- [架构总览](./docs/architecture/overview.md)——信任边界、数据模型、入库与查询链路、故障行为
- [授权模型](./docs/architecture/authorization.md)——不变量、决策表、编译后的 SQL、测试方式
- [评测策略](./docs/evaluation/strategy.md)——用例格式、指标、CI 门禁、可复现规则
- [架构决策记录](./docs/adr/)——模块化单体、PostgreSQL FTS + pgvector、检索时授权、Python 模型服务
- [里程碑](./docs/project/milestones.md)与[待决问题](./docs/project/open-questions.md)

## 参与贡献

欢迎提 issue 和 PR。当前最有价值的贡献，是能打穿现有检索 baseline 的评测用例，以及对 ADR 中设计决策的反驳。请先阅读 [CONTRIBUTING.md](./CONTRIBUTING.md) 与 [AGENTS.md](./AGENTS.md)——`AGENTS.md` 中的规则对人类与 AI 代理同样适用。

安全问题请通过 [GitHub security advisories](https://github.com/poppycoderr/grounded-access/security/advisories/new) 提交。demo 身份体系按设计就是不安全的，见 [SECURITY.md](./SECURITY.md)。

## 相关项目

- [domain-driven-kit](https://github.com/poppycoderr/domain-driven-kit)——面向 Spring Boot 的可执行 DDD 工具箱。Grounded Access 复用它的工程约定（架构测试、空安全、CI 结构），但不依赖它。

## 许可证

[Apache-2.0](./LICENSE)。`data/corpus` 下的虚构语料以 CC BY 4.0 发布，其中的公司均不存在。
