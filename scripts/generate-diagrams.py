"""Generates the README diagrams in English and Chinese: `python3 scripts/generate-diagrams.py assets/diagrams`.

The numbers in the results chart are copied from the committed report named in its footer; update RESULTS when a new report is published.
`ga-authorization.*.svg` is maintained by hand and is not generated here."""

import html
import sys
from pathlib import Path

OUT = Path(sys.argv[1])

STYLE = """
    text{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",Inter,Roboto,sans-serif;}
    .bg{fill:#FBFCFE}
    .kicker{font-size:11.5px;font-weight:700;fill:#94A3B8;letter-spacing:1.4px}
    .title{font-size:26px;font-weight:700;fill:#0F172A;letter-spacing:-.2px}
    .sub{font-size:14px;fill:#64748B}
    .lbl{font-size:15px;font-weight:650;fill:#0F172A}
    .lbl-sm{font-size:13.5px;font-weight:650;fill:#0F172A}
    .txt{font-size:12.5px;fill:#64748B}
    .txt-d{font-size:12.5px;fill:#334155}
    .foot{font-size:12.5px;fill:#64748B}
    .mono{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12px;font-weight:500;fill:#334155}
    .code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12.5px;fill:#E2E8F0}
    .codek{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:12.5px;fill:#7DD3FC}
    .num{font-size:13px;font-weight:650;fill:#0F172A}
    .big{font-size:30px;font-weight:700;fill:#0F172A}
    .bigok{font-size:30px;font-weight:700;fill:#059669}
    .card{fill:#FFFFFF;stroke:#E2E8F0;stroke-width:1.25}
    .dash{fill:#FFFFFF;stroke:#CBD5E1;stroke-width:1.25;stroke-dasharray:5 4}
    .group{fill:#F1F5F9;fill-opacity:.55;stroke:#E2E8F0;stroke-width:1.25}
    .blue{fill:#EFF6FF;stroke:#BFDBFE;stroke-width:1.25}
    .teal{fill:#F0FDFA;stroke:#99F6E4;stroke-width:1.25}
    .violet{fill:#F5F3FF;stroke:#DDD6FE;stroke-width:1.25}
    .amber{fill:#FFFBEB;stroke:#FDE68A;stroke-width:1.25}
    .rose{fill:#FFF1F2;stroke:#FECDD3;stroke-width:1.25}
    .green{fill:#ECFDF5;stroke:#A7F3D0;stroke-width:1.25}
    .dark{fill:#0F172A}
    .ln{stroke:#94A3B8;stroke-width:1.5;fill:none}
    .ln-v{stroke:#6366F1;stroke-width:1.75;fill:none}
    .mk{fill:#94A3B8}.mk-v{fill:#6366F1}
    .bar-s{fill:#38BDF8}.bar-d{fill:#6366F1}.bar-h{fill:#A78BFA}.bar-r{fill:#CBD5E1}
    .axis{stroke:#E2E8F0;stroke-width:1}
    .whisk{stroke:#0F172A;stroke-width:1.5}
    .pill-ok{fill:#D1FAE5}.pill-ok-t{font-size:11.5px;font-weight:700;fill:#047857}
    .pill-now{fill:#E0E7FF}.pill-now-t{font-size:11.5px;font-weight:700;fill:#4338CA}
    .pill-next{fill:#F1F5F9}.pill-next-t{font-size:11.5px;font-weight:700;fill:#64748B}
"""

DEFS = """<defs>
    <marker id="ar" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="mk" d="M0 0 L10 5 L0 10 z"/></marker>
    <marker id="ar-v" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="mk-v" d="M0 0 L10 5 L0 10 z"/></marker>
  </defs>"""


class Svg:
    def __init__(self, width, height, kicker, title, sub):
        self.w, self.h, self.title, self.desc = width, height, title, sub
        self.parts = [f'<rect class="bg" width="{width}" height="{height}" rx="18"/>']
        self.t(40, 44, kicker, "kicker")
        self.t(40, 76, title, "title")
        self.t(40, 101, sub, "sub")

    def rect(self, x, y, w, h, cls="card", rx=12):
        self.parts.append(f'<rect class="{cls}" x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/>')

    def t(self, x, y, text, cls="txt", anchor="start"):
        extra = "" if anchor == "start" else f' text-anchor="{anchor}"'
        self.parts.append(f'<text class="{cls}" x="{x}" y="{y}"{extra}>{html.escape(text)}</text>')

    def line(self, x1, y1, x2, y2, cls="ln", arrow=True):
        marker = "" if not arrow else f' marker-end="url(#{"ar-v" if cls == "ln-v" else "ar"})"'
        self.parts.append(f'<path class="{cls}" d="M{x1} {y1} L{x2} {y2}"{marker}/>')

    def card(self, x, y, w, h, label, lines, cls="card", label_cls="lbl-sm"):
        self.rect(x, y, w, h, cls)
        self.t(x + 16, y + 25, label, label_cls)
        for i, line in enumerate(lines):
            mono = line.startswith("`")
            self.t(x + 16, y + 45 + i * 18, line.strip("`"), "mono" if mono else "txt")

    def pill(self, x, y, text, kind):
        width = 14 + len(text) * (11 if any(ord(c) > 255 for c in text) else 6.6)
        self.rect(x, y, round(width), 20, f"pill-{kind}", 10)
        self.t(x + round(width) / 2, y + 14, text, f"pill-{kind}-t", "middle")

    def save(self, name):
        body = "\n  ".join(self.parts)
        (OUT / name).write_text(
            f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" height="{self.h}"\n'
            f'     role="img" aria-labelledby="ttl dsc" style="max-width:100%;height:auto">\n'
            f'  <title id="ttl">{html.escape(self.title)}</title>\n  <desc id="dsc">{html.escape(self.desc)}</desc>\n  {DEFS}\n'
            f"  <style>{STYLE}  </style>\n  {body}\n</svg>\n"
        )


def overview(lang, s):
    d = Svg(1120, 600, "GROUNDED ACCESS · v0.1", s["title"], s["sub"])
    for i, (label, text, edge) in enumerate(s["actors"]):
        y = 172 + i * 104
        d.card(40, y, 210, 66, label, [text])
        d.line(250, y + 33, 298, y + 33, "ln-v" if i == 0 else "ln")
        d.t(274, y + 24, edge, "txt", "middle")
    d.rect(300, 128, 440, 414, "group", 16)
    d.t(318, 155, s["plane"], "lbl")
    for i, (label, text, cls) in enumerate(s["modules"]):
        y = 170 + i * 73
        d.card(318, y, 404, 62, label, [text], cls)
        if i in (0, 1):
            d.line(520, y + 62, 520, y + 72, "ln-v")
    for i, (label, lines, cls) in enumerate(s["deps"]):
        y = 170 + i * 120
        d.card(800, y, 280, 84, label, lines, cls)
        d.line(740, y + 42, 798, y + 42, "ln-v" if i == 0 else "ln")
    d.t(40, 574, s["foot"], "foot")
    d.save(f"ga-overview.{lang}.svg")


def decision(lang, s):
    d = Svg(1120, 640, "GROUNDED ACCESS · AUTHORIZATION", s["title"], s["sub"])
    for x, head in ((40, s["h1"]), (360, s["h2"]), (790, s["h3"])):
        d.t(x, 142, head, "kicker")
    for i, (claim, claim_note, rule, rule_note, label, label_note) in enumerate(s["rows"]):
        y = 156 + i * 80
        d.card(40, y, 290, 64, claim, [claim_note], "blue")
        d.card(360, y, 400, 64, rule, [rule_note], "violet")
        d.card(790, y, 290, 64, label, [label_note], "amber")
        d.line(330, y + 32, 358, y + 32)
        d.line(790, y + 32, 762, y + 32)
    d.rect(40, 486, 1040, 112, "dark", 12)
    d.t(60, 512, s["sqlhead"], "codek")
    for i, line in enumerate(s["sql"]):
        d.t(60, 534 + i * 18, line, "code")
    d.t(40, 622, s["foot"], "foot")
    d.save(f"ga-decision-table.{lang}.svg")


RESULTS = [
    ("sparse-only", 0.683, 0.59, 0.77, 0.923, "bar-s"),
    ("dense-only", 0.873, 0.80, 0.94, 0.969, "bar-d"),
    ("hybrid-rrf", 0.822, 0.74, 0.89, 0.969, "bar-h"),
    ("bm25-reference", 0.720, 0.63, 0.80, 0.931, "bar-r"),
]


def results(lang, s):
    d = Svg(1120, 560, "GROUNDED ACCESS · EVALUATION", s["title"], s["sub"])
    x0, scale = 290, 540
    d.t(x0, 142, s["axis"], "kicker")
    d.t(980, 142, "Recall@10", "kicker")
    for tick in (0, 0.25, 0.5, 0.75, 1.0):
        x = x0 + tick * scale
        d.parts.append(f'<line class="axis" x1="{x}" y1="152" x2="{x}" y2="392"/>')
        d.t(x, 410, f"{tick:.2f}".rstrip("0").rstrip(".") if tick else "0", "txt", "middle")
    for i, (name, mean, low, high, recall, cls) in enumerate(RESULTS):
        y = 166 + i * 58
        d.t(40, y + 17, name, "mono")
        d.t(40, y + 35, s["notes"][i], "txt")
        d.rect(x0, y, round(mean * scale), 30, cls, 6)
        yl = y + 15
        d.parts.append(
            f'<path class="whisk" d="M{x0 + low * scale} {yl} L{x0 + high * scale} {yl} M{x0 + low * scale} {yl - 6} v12 M{x0 + high * scale} {yl - 6} v12"/>'
        )
        d.t(x0 + high * scale + 12, y + 20, f"{mean:.3f}  [{low:.2f}, {high:.2f}]", "num")
        d.t(980, y + 20, f"{recall:.3f}", "num")
    for i, (big, label, ok) in enumerate(s["tiles"]):
        x = 40 + i * 350
        d.rect(x, 436, 330, 70, "green" if ok else "card")
        d.t(x + 18, 482, big, "bigok" if ok else "big")
        d.t(x + 18 + len(big) * 19 + 12, 470, label[0], "lbl-sm")
        d.t(x + 18 + len(big) * 19 + 12, 489, label[1], "txt")
    d.t(40, 536, s["foot"], "foot")
    d.save(f"ga-eval-results.{lang}.svg")


def gate(lang, s):
    d = Svg(1120, 520, "GROUNDED ACCESS · SECURITY GATE", s["title"], s["sub"])
    d.card(40, 150, 300, 104, s["labels"][0], s["labels"][1:], "amber")
    d.card(40, 284, 300, 104, s["system"][0], s["system"][1:], "blue")
    d.rect(400, 130, 340, 290, "group", 16)
    d.t(418, 157, s["checks"], "lbl")
    for i, (label, lines) in enumerate(s["steps"]):
        y = 172 + i * 118
        d.card(418, y, 304, 104, label, lines, "violet")
    d.line(340, 202, 398, 222)
    d.line(340, 336, 398, 316)
    for i, (label, lines, cls) in enumerate(s["outcomes"]):
        y = 130 + i * 100
        d.card(800, y, 280, 84, label, lines, cls)
        d.line(740, 275, 798, y + 42)
    d.t(40, 462, s["foot"][0], "foot")
    d.t(40, 484, s["foot"][1], "foot")
    d.save(f"ga-security-gate.{lang}.svg")


def roadmap(lang, s):
    d = Svg(1120, 400, "GROUNDED ACCESS · ROADMAP", s["title"], s["sub"])
    d.parts.append('<line class="axis" x1="60" y1="150" x2="1060" y2="150" style="stroke-width:2"/>')
    for i, (name, label, lines, kind, status) in enumerate(s["stones"]):
        x = 40 + i * 210
        dot = {"ok": "#10B981", "now": "#6366F1", "next": "#CBD5E1"}[kind]
        d.parts.append(f'<circle cx="{x + 20}" cy="150" r="7" fill="{dot}" stroke="#FBFCFE" stroke-width="3"/>')
        d.rect(x, 176, 200, 190, "card" if kind != "next" else "dash")
        d.t(x + 16, 202, name, "kicker")
        d.t(x + 16, 226, label, "lbl")
        for j, line in enumerate(lines):
            d.t(x + 16, 250 + j * 19, line, "txt")
        d.pill(x + 16, 334, status, kind)
    d.save(f"ga-roadmap.{lang}.svg")


EN = {
    "overview": {
        "title": "One compiled predicate governs every retrieval path",
        "sub": "Java owns identity, authorization and retrieval; Python owns model work; the evaluation CLI drives the same public API.",
        "actors": [
            ("Query user", "demo JWT, scope query", "search"),
            ("Corpus maintainer", "documents + labels, scope admin", "ingest"),
            ("ga-eval CLI", "one token per principal", "evaluate"),
        ],
        "plane": "Control plane · Java / Spring Boot",
        "modules": [
            ("identity", "verify JWT · requires sub + tenant_id", "blue"),
            ("authorization", "PolicyCompiler → one SQL predicate · policy abac/1", "violet"),
            ("retrieval", "PostgreSQL FTS · pgvector · RRF fusion · plan hash", "teal"),
            ("corpus + ingestion", "job queue · chunking · versions · access labels", "amber"),
            ("answering · M3", "citations · abstention", "dash"),
        ],
        "deps": [
            ("PostgreSQL 17 + pgvector", ["authorization runs inside", "the retrieval query"], "card"),
            ("model-service · Python", ["receives authorized text only", "embeddings on CPU · rerank in M3"], "card"),
            ("local chat model · M3", ["optional, no API key needed"], "dash"),
        ],
        "foot": "Unauthorized rows never leave PostgreSQL  ·  label changes apply to the next query  ·  every evaluation run is reproducible",
    },
    "decision": {
        "title": "Four rules, one predicate, default deny",
        "sub": "A chunk is returned only if every rule holds. The rules are compiled into the SQL that selects candidates.",
        "h1": "VERIFIED TOKEN",
        "h2": "RULE",
        "h3": "DOCUMENT VERSION LABELS",
        "rows": [
            (
                "tenant_id",
                "required claim; token rejected without it",
                "Tenant must be equal",
                "nothing crosses a tenant boundary",
                "tenant_id",
                "set from the ingesting admin's token",
            ),
            (
                "clearance",
                "missing or unknown → public",
                "Classification ≤ clearance",
                "public < internal < confidential < restricted",
                "classification",
                "default public",
            ),
            (
                "department",
                "missing → unrestricted documents only",
                "No restriction, or department listed",
                "one department per principal",
                "allowed_departments",
                "empty = no restriction",
            ),
            (
                "projects[ ]",
                "empty → unrestricted documents only",
                "No restriction, or a project in common",
                "any one project is enough",
                "required_projects",
                "empty = no restriction",
            ),
        ],
        "sqlhead": "-- the same text for every principal; only the bound values differ",
        "sql": [
            "c.tenant_id = :auth_tenant_id AND v.classification_rank <= :auth_clearance_rank",
            "AND (cardinality(v.allowed_departments) = 0 OR CAST(:auth_department AS text) = ANY(v.allowed_departments))",
            "AND (cardinality(v.required_projects)  = 0 OR v.required_projects && CAST(:auth_projects AS text[]))",
        ],
        "foot": "Region, validity dates and document status are scope, not authorization, and are counted separately.",
    },
    "results": {
        "title": "Dense ranks evidence highest; hybrid does not beat it",
        "sub": "Dataset v3, test split, 65 answerable cases. Bars are MRR@10 with 95% bootstrap intervals, from the committed CI run.",
        "axis": "MRR@10",
        "notes": ["PostgreSQL full-text search", "exact pgvector search", "reciprocal rank fusion of the two", "offline reference row"],
        "tiles": [
            ("0", ("unauthorized results", "across 122 cases and every strategy"), True),
            ("31", ("authorization negatives", "tenant, clearance, project, department"), False),
            ("−0.05", ("hybrid vs dense, MRR@10", "no detectable difference"), False),
        ],
        "foot": "Source: benchmarks/reports/m2-authorization. A demo benchmark on a small fictional corpus, not a claim about production quality.",
    },
    "gate": {
        "title": "The security gate compares the system with hand-written labels",
        "sub": "The expected visibility is written by a person from the access labels, never produced by the policy compiler it checks.",
        "labels": ["visibility.yaml", "hand-labelled visible documents", "per principal, current version only", "`data/eval/v3`"],
        "system": ["System under test", "public API with demo tokens", "one token per principal", "`/retrieval/chunks · /retrieval/search`"],
        "checks": "ga-eval run",
        "steps": [
            (
                "1 · Listing check",
                ["every chunk each principal can list", "must match its visible set exactly", "covers documents no query retrieves"],
            ),
            ("2 · Per-result check", ["122 cases × every strategy", "each chunk: visible document", "and current version"]),
        ],
        "outcomes": [
            ("Extra document or old version", ["a violation: exit code 2", "the CI job fails"], "rose"),
            ("Visible document missing", ["labels and system disagree", "the run aborts"], "amber"),
            ("0 violations", ["the report is written", "and can be published"], "green"),
        ],
        "foot": [
            "31 cases try to reach a document the principal may not see: another tenant, a higher classification, another project or department.",
            "Relabelling a restricted document as public on a running stack makes the gate fail, which is how the gate itself was checked.",
        ],
    },
    "roadmap": {
        "title": "Each milestone ends with something that can be checked",
        "sub": "A tagged pre-release, a changelog entry and, where there are numbers, a committed report.",
        "stones": [
            ("M0", "Walking skeleton", ["tenant isolation in SQL", "sparse + dense retrieval", "eval CLI, CI security gate"], "ok", "Done"),
            (
                "M1",
                "Retrieval baseline",
                ["dataset, BM25 reference, CIs", "async ingestion, chunker", "RRF hybrid + verdict"],
                "ok",
                "Done · v0.1.0-alpha.1",
            ),
            ("M2", "Authorization", ["decision table, scope filters", "audit, existence-safe reads", "labelled dataset, threat model"], "ok", "Done"),
            ("M3", "Reranking + answers", ["cross-encoder reranker", "cited answers", "abstention"], "next", "Planned"),
            ("M4", "Operations + v0.1", ["OpenTelemetry traces", "failure and load tests", "full benchmark, release"], "next", "Planned"),
        ],
    },
}

ZH = {
    "overview": {
        "title": "一个编译好的谓词管住所有检索路径",
        "sub": "Java 负责身份、授权与检索；Python 负责模型计算；评测 CLI 走的是同一套公开 API。",
        "actors": [
            ("查询用户", "demo JWT，scope query", "检索"),
            ("语料维护者", "文档 + 标签，scope admin", "入库"),
            ("ga-eval CLI", "每个身份一个 token", "评测"),
        ],
        "plane": "控制面 · Java / Spring Boot",
        "modules": [
            ("identity", "验签 JWT · 必须带 sub 与 tenant_id", "blue"),
            ("authorization", "PolicyCompiler → 一个 SQL 谓词 · policy abac/1", "violet"),
            ("retrieval", "PostgreSQL FTS · pgvector · RRF 融合 · 配置哈希", "teal"),
            ("corpus + ingestion", "任务队列 · 切分 · 版本 · 访问标签", "amber"),
            ("answering · M3", "引用 · 拒答", "dash"),
        ],
        "deps": [
            ("PostgreSQL 17 + pgvector", ["授权在检索查询内部执行"], "card"),
            ("model-service · Python", ["只接收已授权的文本", "CPU 上计算向量 · M3 加入重排"], "card"),
            ("本地对话模型 · M3", ["可选，不需要 API key"], "dash"),
        ],
        "foot": "未授权的行不会离开 PostgreSQL  ·  标签变更对下一次查询生效  ·  每次评测都可复现",
    },
    "decision": {
        "title": "四条规则，一个谓词，默认拒绝",
        "sub": "只有四条规则同时成立，chunk 才会被返回。规则被编译进筛选候选的那条 SQL。",
        "h1": "已验证的 TOKEN",
        "h2": "规则",
        "h3": "文档版本上的标签",
        "rows": [
            ("tenant_id", "必填 claim；缺失则拒绝 token", "租户必须相同", "任何内容都不跨租户", "tenant_id", "取自入库管理员的 token"),
            (
                "clearance",
                "缺失或无法识别 → public",
                "文档密级 ≤ 身份密级",
                "public < internal < confidential < restricted",
                "classification",
                "默认 public",
            ),
            ("department", "缺失 → 只能看不限部门的文档", "不限部门，或部门在列表中", "每个身份一个部门", "allowed_departments", "为空 = 不限制"),
            ("projects[ ]", "为空 → 只能看不限项目的文档", "不限项目，或有共同项目", "有一个共同项目即可", "required_projects", "为空 = 不限制"),
        ],
        "sqlhead": "-- 谓词文本对所有身份都相同，只有绑定的值不同",
        "sql": EN["decision"]["sql"],
        "foot": "region、有效期和文档状态属于适用范围，不属于授权，单独统计。",
    },
    "results": {
        "title": "dense 把证据排得最靠前；hybrid 没有超过它",
        "sub": "数据集 v3，test 划分，65 条可回答用例。柱子是 MRR@10 及 95% bootstrap 区间，来自已提交的 CI 运行。",
        "axis": "MRR@10",
        "notes": ["PostgreSQL 全文检索", "pgvector 精确检索", "两者的 RRF 融合", "离线参考行"],
        "tiles": [
            ("0", ("越权结果", "122 条用例、所有策略"), True),
            ("31", ("授权负例", "租户、密级、项目、部门"), False),
            ("−0.05", ("hybrid 对 dense，MRR@10", "没有可检测的差异"), False),
        ],
        "foot": "数据来源：benchmarks/reports/m2-authorization。这是小型虚构语料上的 demo benchmark，不代表生产效果。",
    },
    "gate": {
        "title": "安全门禁拿系统和手写标注做比较",
        "sub": "预期的可见范围由人对照访问标签写出，绝不由被检验的 policy compiler 生成。",
        "labels": ["visibility.yaml", "人工标注的可见文档", "按身份列出，只认当前版本", "`data/eval/v3`"],
        "system": ["被测系统", "公开 API + demo token", "每个身份一个 token", "`/retrieval/chunks · /retrieval/search`"],
        "checks": "ga-eval run",
        "steps": [
            ("1 · 列表检查", ["每个身份能列出的全部 chunk", "必须与它的可见集合完全一致", "覆盖没有被任何查询检索到的文档"]),
            ("2 · 逐条结果检查", ["122 条用例 × 每种策略", "每个 chunk：文档可见", "且来自当前版本"]),
        ],
        "outcomes": [
            ("多出文档或返回旧版本", ["记为越权：退出码 2", "CI 任务失败"], "rose"),
            ("可见文档列不出来", ["标注与系统不一致", "评测中止"], "amber"),
            ("0 条越权", ["生成报告", "可以发布"], "green"),
        ],
        "foot": [
            "31 条用例专门去够身份无权查看的文档：别的租户、更高的密级、别的项目或部门。",
            "在运行中的环境里把一篇 restricted 文档改标成 public，门禁就会失败——门禁本身就是这样验证的。",
        ],
    },
    "roadmap": {
        "title": "每个里程碑都以可以检验的东西收尾",
        "sub": "一个带标签的预发布、一条 changelog，有数字的地方再加一份提交在仓库里的报告。",
        "stones": [
            ("M0", "Walking skeleton", ["SQL 内的租户隔离", "sparse + dense 检索", "评测 CLI、CI 安全门禁"], "ok", "已完成"),
            ("M1", "检索基线", ["数据集、BM25 参考行、置信区间", "异步入库、chunker", "RRF hybrid 与结论"], "ok", "已完成 · v0.1.0-alpha.1"),
            ("M2", "授权", ["决策表、适用范围过滤", "审计、不泄漏存在性的读取", "带标签的数据集、威胁模型"], "ok", "已完成"),
            ("M3", "重排与回答", ["cross-encoder 重排", "带引用的回答", "拒答"], "next", "计划中"),
            ("M4", "运维与 v0.1", ["OpenTelemetry trace", "故障与压力测试", "完整 benchmark、发布"], "next", "计划中"),
        ],
    },
}

for lang, strings in (("en", EN), ("zh-CN", ZH)):
    overview(lang, strings["overview"])
    decision(lang, strings["decision"])
    results(lang, strings["results"])
    gate(lang, strings["gate"])
    roadmap(lang, strings["roadmap"])
print("generated", len(list(OUT.glob("*.svg"))), "svg files")
