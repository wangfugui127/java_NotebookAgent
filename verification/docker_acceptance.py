"""Actual HTTP + worker + DeepSeek/SiliconFlow + Milvus/Neo4j acceptance.
Requires the project's isolated Docker Compose stack and configured provider keys.
Only creates/deletes its own notebooks. Credentials stay in ignored runtime/.
"""
from __future__ import annotations
import concurrent.futures
import json
import os
from pathlib import Path
import time
import uuid
import requests

ROOT = Path(__file__).resolve().parent
BASE = os.environ.get("JAVA_NOTEBOOK_URL", "http://127.0.0.1:13000").rstrip("/")
API = BASE + "/api/v1"
session = requests.Session()
report = {"url": BASE, "started_at": time.strftime("%Y-%m-%d %H:%M:%S"), "checks": []}

def check(name, details=True):
    report["checks"].append({"name": name, "result": "passed", "details": details})
    print("PASS", name, json.dumps(details, ensure_ascii=False), flush=True)
    (ROOT / "docker-acceptance-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

def call(method, path, **kwargs):
    r = session.request(method, API + path, timeout=45, **kwargs)
    if r.status_code >= 400:
        raise AssertionError(f"{method} {path}: {r.status_code}: {r.text[:1000]}")
    return r.json() if r.content else None

def wait(label, fetch, accept, timeout=600):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        value = fetch()
        status = value.get("status") if isinstance(value, dict) else None
        if status != last:
            print(label, status, flush=True)
            last = status
        if accept(value):
            return value
        if status in {"failed", "cancelled"}:
            raise AssertionError(f"{label}: {value}")
        time.sleep(2)
    raise TimeoutError(f"{label}: {value}")

def stream(run_id, early_close=False, after=0):
    events, ids = [], []
    with requests.get(API + f"/agent/runs/{run_id}/events", params={"after": after},
                      headers=session.headers, stream=True, timeout=(10, 240)) as r:
        r.raise_for_status()
        event, sequence, data = None, None, []
        for raw in r.iter_lines(chunk_size=1):
            line = raw.decode("utf-8")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("id:"):
                sequence = int(line[3:].strip())
            elif line.startswith("data:"):
                data.append(line[5:].strip())
            elif not line:
                if event:
                    events.append({"type": event, "payload": json.loads("\n".join(data)) if data else {}})
                    if sequence is not None:
                        ids.append(sequence)
                    if early_close or event in {"run_completed", "run_failed", "run_cancelled"}:
                        break
                event, sequence, data = None, None, []
    assert ids == sorted(set(ids)), ids
    return events, ids

def new_run(conversation, query, attachments=None, mode="read_only"):
    return call("POST", "/agent/runs", json={"conversation_id": conversation, "query": query,
         "approval_mode": mode, "attachment_ids": attachments or [],
         "attachment_instructions": {d: "通读全部正文并引用证据" for d in attachments or []}})["id"]

def main():
    assert requests.get(BASE, timeout=15).status_code == 200
    assert requests.get("http://127.0.0.1:18001/health", timeout=15).json()["status"] == "ok"
    check("frontend_and_database_redis_health")
    runtime = ROOT / "runtime"
    runtime.mkdir(exist_ok=True)
    credentials = {"email": "java-acceptance-" + uuid.uuid4().hex[:8] + "@example.com", "password": uuid.uuid4().hex + "Ja!"}
    auth = call("POST", "/auth/register", json=credentials)
    (runtime / "credentials.json").write_text(json.dumps(credentials), encoding="utf-8")
    session.headers["Authorization"] = "Bearer " + auth["access_token"]
    check("registration_authentication")
    notebook = call("POST", "/notebooks", json={"title": "Java 迁移验收 " + time.strftime("%H:%M"), "description": "真实模型及数据库验收样本"})["id"]
    report["notebook_id"] = notebook
    text = """# 限量商城库存实验

## 摘要
本文验证限量商品订单系统在并发请求下的库存约束。测试样本初始库存为10件，发起40个购买请求，最终成功创建10个订单，无超卖。

## 方法与数据
实验名称：限量库存并发实验。方法为MySQL事务与条件更新，数据集为40个购买请求；每个订单只扣减一次库存，成功订单数必须小于或等于初始库存。

## 结果
指标：成功订单数10、超卖订单数0、剩余库存0。该实验仅覆盖单机样本，不代表生产容量。
"""
    source = call("POST", f"/notebooks/{notebook}/documents", files={"file": ("Java-验收样本.md", text.encode("utf-8"), "text/markdown")})
    document = source["document_id"]
    report["source"] = source
    def documents():
        return call("GET", f"/notebooks/{notebook}/documents")["items"]
    ready = wait("ingestion", lambda: next(d for d in documents() if d["id"] == document), lambda d: d["status"] == "ready")
    check("real_embedding_and_milvus_ingestion", {"status": ready["status"], "version_id": ready["version_id"]})
    conversation = call("POST", "/conversations", json={"notebook_id": notebook})["id"]
    run_id = new_run(conversation, "请检索当前Notebook的Java-验收样本.md，说明初始库存、请求数、成功订单数和是否超卖。必须用原文证据回答，并使用[evidence:真实证据ID]引用。")
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        early = pool.submit(stream, run_id, True)
        full = pool.submit(stream, run_id)
        early_events, early_ids = early.result()
        events, ids = full.result()
    assert events[-1]["type"] == "run_completed", events[-1]
    answer = events[-1]["payload"]
    assert "10" in answer["answer"] and answer["citations"], answer
    check("real_retrieval_chat_citations_and_sse", {"run_id": run_id, "citation_count": len(answer["citations"]), "durable_event_count": len(ids), "independent_connection_closed_at": early_ids})
    replay, replay_ids = stream(run_id, after=ids[-2] if len(ids) > 1 else 0)
    assert replay[-1]["type"] == "run_completed"
    check("sse_database_replay", replay_ids)
    manifests = call("GET", f"/agent/runs/{run_id}/context-manifest")["items"]
    assert manifests and all(m["estimated_tokens"] <= m["effective_input_budget"] for m in manifests)
    check("context_manifest", {"count": len(manifests), "mode": manifests[-1]["mode"]})
    attachment_run = new_run(conversation, "请通读上传的完整附件，总结实验的结果与局限，必须引用附件提供的真实evidence_id。", [document])
    attach_events, _ = stream(attachment_run)
    assert attach_events[-1]["type"] == "run_completed" and attach_events[-1]["payload"]["citations"]
    check("full_attachment_chat_and_citations", {"run_id": attachment_run, "citations": len(attach_events[-1]["payload"]["citations"])})
    graph = wait("graph", lambda: next(d for d in documents() if d["id"] == document),
                 lambda d: d["graph_status"] in {"ready", "failed", "skipped"}, 300)
    assert graph["graph_status"] == "ready", graph
    graph_items = call("GET", f"/notebooks/{notebook}/graph/experiments", params={"q": "", "limit": 10})
    assert not graph_items["degraded"] and graph_items["items"]
    check("real_graph_index_and_query", {"graph_status": graph["graph_status"], "experiments": len(graph_items["items"])})
    dataset = call("POST", "/eval/datasets", json={"name": "Java真实评测验收"})["id"]
    call("POST", f"/eval/datasets/{dataset}/cases", json={"case_key": "smoke", "question": "请只回答：Java验收通过", "required_keywords": ["Java", "验收", "通过"]})
    eval_run = call("POST", f"/eval/datasets/{dataset}/runs", json={"notebook_id": notebook, "allow_missing": False})
    finished = wait("evaluation", lambda: call("GET", f"/eval/runs/{eval_run['id']}"), lambda e: e["status"] == "completed")
    assert finished["completed_cases"] == 1 and finished["passed_cases"] == 1, finished
    check("real_evaluation", {"id": eval_run["id"], "completed_cases": 1, "passed_cases": 1})
    # Destructive tool authorization: reject once, then approve another run.
    for decision in ["rejected", "approved"]:
        conv = call("POST", "/conversations", json={"notebook_id": notebook})["id"]
        delete_run = new_run(conv, f"请立即调用remove_notebook_source删除这份资料，document_ids必须为[\"{document}\"]。我已明确提出删除需求，系统将单独显示审批卡片，请提交工具调用等待系统审批。", mode="confirm")
        state = wait("approval " + decision, lambda: call("GET", f"/agent/runs/{delete_run}"), lambda r: r["status"] in {"waiting_approval", "completed"})
        assert state["status"] == "waiting_approval", state
        approval = next(a for a in call("GET", f"/agent/runs/{delete_run}/approvals")["items"] if a["status"] == "pending")
        call("POST", f"/agent/approvals/{approval['id']}/resolve", json={"decision": decision})
        done_events, _ = stream(delete_run)
        assert done_events[-1]["type"] == "run_completed", done_events[-1]
        present = any(d["id"] == document for d in documents())
        assert present == (decision == "rejected"), (decision, present)
        check("real_approval_" + decision, {"run_id": delete_run, "source_present": present})
    check("source_deletion")
    report["finished_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
    check("acceptance_complete", {"checks": len(report["checks"]) + 1})

if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        report["failure"] = str(exc)
        (ROOT / "docker-acceptance-results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        raise
