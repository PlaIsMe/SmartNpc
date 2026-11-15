"""Evaluate a local llama-server using the same schema and templates as SmartNpc."""
import argparse
import copy
import json
import math
import statistics
import time
import urllib.request
from pathlib import Path
from urllib.parse import urlparse
from validate_dataset import ROOT, LANGUAGES, rows


def constrained_schema(context):
    schema = json.loads((ROOT / "schema/chat_action.schema.json").read_text(encoding="utf-8"))
    allowed = set(context["allowed_actions"]) | {"NONE"}
    branches = []
    for branch in schema["properties"]["action"]["oneOf"]:
        kind = branch["properties"]["type"]
        if "const" in kind:
            if kind["const"] in allowed:
                branches.append(branch)
        else:
            kind["enum"] = [value for value in kind["enum"] if value in allowed]
            if kind["enum"]:
                branches.append(branch)
    schema["properties"]["action"]["oneOf"] = branches
    return schema


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="http://127.0.0.1:8765")
    parser.add_argument("--model", default="qwen2.5-0.5b")
    parser.add_argument("--data", type=Path)
    parser.add_argument("--language", choices=LANGUAGES, default="en_us")
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--pid", type=int, help="Optional llama-server PID; install psutil to sample native RSS")
    args = parser.parse_args()
    args.data = args.data or ROOT / f"data/test_{args.language}.jsonl"
    endpoint = urlparse(args.endpoint)
    if endpoint.scheme != "http" or endpoint.hostname not in {"127.0.0.1", "::1"}:
        raise ValueError("Benchmark only sends synthetic prompts to numeric loopback HTTP")
    import jsonschema
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    process = None
    if args.pid:
        import psutil
        process = psutil.Process(args.pid)
    results, latencies, rss = [], [], []
    for row in rows(args.data):
        context = json.loads(row["messages"][1]["content"])
        schema = constrained_schema(context)
        body = {"model": args.model, "messages": row["messages"][:2], "temperature": 0,
                "stream": False, "max_tokens": 160, "chat_template_kwargs": {"enable_thinking": False},
                "response_format": {"type": "json_schema", "json_schema": {"name": "smart_npc_reply", "strict": True, "schema": schema}}}
        started = time.perf_counter()
        result = {"scenario_id": row["scenario_id"], "schema_valid": False, "action_exact": False}
        try:
            request = urllib.request.Request(args.endpoint.rstrip("/") + "/v1/chat/completions", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            with opener.open(request, timeout=35) as response:
                raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("Response too large")
            choice = json.loads(raw)["choices"][0]
            if choice["finish_reason"] != "stop":
                raise ValueError("Truncated response")
            reply = json.loads(choice["message"]["content"])
            jsonschema.validate(reply, schema)
            result["schema_valid"] = True
            expected = json.loads(row["messages"][2]["content"])
            result["action_exact"] = reply["action"] == expected["action"]
            result["reply"] = reply
            result["reference"] = expected
            action = reply["action"]
            result["invented_gift"] = action["type"] == "GIVE_ITEM" and context["npc"]["inventory"].get(action["item"], 0) < action["count"]
        except Exception as error:
            result["error"] = str(error)
        result["seconds"] = time.perf_counter() - started
        latencies.append(result["seconds"])
        if process:
            info = process.memory_info()
            rss.append(getattr(info, "peak_wset", info.rss))
        results.append(result)
        print(row["scenario_id"], result["schema_valid"], result["action_exact"], round(result["seconds"], 2), flush=True)
    count = len(results)
    report = {"model": args.model, "cases": count, "schema_rate": sum(r["schema_valid"] for r in results) / count,
              "exact_action_rate": sum(r["action_exact"] for r in results) / count,
              "p50_seconds": statistics.median(latencies), "p95_seconds": sorted(latencies)[max(0, math.ceil(count * .95) - 1)],
              "native_rss_sampled_peak_bytes": max(rss) if rss else None,
              "limitations": "Synthetic dialogue evaluation; no Minecraft ticks, transactions or factual prose grading. Manually review say text.", "results": results}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
