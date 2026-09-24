"""轮询 RabbitMQ Management API，记录一次压测窗口内的主队列峰值与最终值。"""

import argparse
import base64
import json
import os
import time
import urllib.parse
import urllib.request


def read_queue(base_url, vhost, queue, username, password):
    encoded_vhost = urllib.parse.quote(vhost, safe="")
    encoded_queue = urllib.parse.quote(queue, safe="")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}/api/queues/{encoded_vhost}/{encoded_queue}"
    )
    credentials = base64.b64encode(f"{username}:{password}".encode()).decode()
    request.add_header("Authorization", f"Basic {credentials}")
    with urllib.request.urlopen(request, timeout=2) as response:
        body = json.load(response)
    return {
        "ready": int(body.get("messages_ready", 0)),
        "unacked": int(body.get("messages_unacknowledged", 0)),
        "total": int(body.get("messages", 0)),
        "publish": int(body.get("message_stats", {}).get("publish", 0)),
        "ack": int(body.get("message_stats", {}).get("ack", 0)),
        "deliver": int(body.get("message_stats", {}).get("deliver_get", 0)),
    }


parser = argparse.ArgumentParser()
parser.add_argument("--base-url", default="http://127.0.0.1:15672")
parser.add_argument("--vhost", default="/")
parser.add_argument("--queue", default="hmdp.seckill.order.queue")
parser.add_argument("--duration", type=float, required=True)
parser.add_argument("--interval", type=float, default=0.1)
parser.add_argument("--output", required=True)
args = parser.parse_args()

username = os.environ["RABBITMQ_USERNAME"]
password = os.environ["RABBITMQ_PASSWORD"]
started = time.time()
samples = []
errors = []
while time.time() - started < args.duration:
    try:
        samples.append(
            read_queue(args.base_url, args.vhost, args.queue, username, password)
        )
    except Exception as error:
        errors.append(str(error))
    time.sleep(args.interval)

result = {
    "sample_count": len(samples),
    "error_count": len(errors),
    "first": samples[0] if samples else None,
    "last": samples[-1] if samples else None,
    "peak_ready": max((item["ready"] for item in samples), default=None),
    "peak_unacked": max((item["unacked"] for item in samples), default=None),
    "peak_total": max((item["total"] for item in samples), default=None),
    "publish_delta": samples[-1]["publish"] - samples[0]["publish"] if samples else None,
    "ack_delta": samples[-1]["ack"] - samples[0]["ack"] if samples else None,
    "first_error": errors[0] if errors else None,
}
with open(args.output, "w", encoding="utf-8") as output:
    json.dump(result, output, ensure_ascii=False, indent=2)
print(json.dumps(result, ensure_ascii=False))
