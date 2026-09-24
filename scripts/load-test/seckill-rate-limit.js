import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

/*
 * 秒杀入口限流压测准备脚本。
 *
 * 这个脚本不会替项目决定阈值，只负责产生可重复的 USER/GLOBAL 请求并区分 HTTP 状态。
 * 运行前必须阅读同目录 README：成功放行的请求会继续执行真实 seckill.lua、MQ 和下单链路，
 * 因此只能使用隔离环境、专用测试券和测试账号。
 */

const mode = __ENV.MODE || 'user';
const iterations = Number(__ENV.ITERATIONS || 0);
const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8081';
const voucherId = __ENV.VOUCHER_ID;
const tokens = (__ENV.TOKENS || __ENV.TOKEN || '')
    .split(',')
    .map((token) => token.trim())
    .filter((token) => token.length > 0);

if (!voucherId) {
    throw new Error('VOUCHER_ID is required');
}
if (tokens.length === 0) {
    throw new Error('TOKEN or TOKENS is required');
}
if (mode !== 'user' && mode !== 'global') {
    throw new Error('MODE must be user or global');
}

const scenarios = {};
if (mode === 'user') {
    // 小迭代数可避免 USER 测试先耗尽 GLOBAL 桶；未传 ITERATIONS 时保留原持续压测模式。
    scenarios.user_limit = iterations > 0 ? {
        executor: 'shared-iterations',
        vus: Number(__ENV.VUS || 1),
        iterations,
        maxDuration: __ENV.DURATION || '10s',
    } : {
        executor: 'constant-vus',
        vus: Number(__ENV.VUS || 10),
        duration: __ENV.DURATION || '10s',
    };
} else {
    // 恒定到达率更适合观察 GLOBAL Token Bucket 的平均速率和 burst。
    scenarios.global_limit = {
        executor: 'constant-arrival-rate',
        rate: Number(__ENV.RATE || 1000),
        timeUnit: '1s',
        duration: __ENV.DURATION || '20s',
        preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 200),
        maxVUs: Number(__ENV.MAX_VUS || 1000),
    };
}

export const options = {
    scenarios,
    // 这里只把未知状态码视为脚本失败。429 是本次要观察的正常结果，503 单独计数用于发现故障。
    thresholds: {
        unexpected_responses: ['count==0'],
    },
};

const allowedResponses = new Counter('allowed_responses');
const acceptedResponses = new Counter('accepted_responses');
const businessFailedResponses = new Counter('business_failed_responses');
const limitedResponses = new Counter('limited_responses');
const unavailableResponses = new Counter('unavailable_responses');
const unexpectedResponses = new Counter('unexpected_responses');

export default function () {
    // GLOBAL 场景按 VU 轮换 token。要减少 USER 规则干扰，需要准备足够多的独立测试账号 token。
    const token = mode === 'user' ? tokens[0] : tokens[(__VU - 1) % tokens.length];
    const response = http.post(`${baseUrl}/voucher-order/seckill/${voucherId}`, null, {
        headers: { authorization: token },
        tags: { rate_limit_mode: mode },
    });

    if (response.status === 200) {
        allowedResponses.add(1);
        try {
            const body = response.json();
            if (body.success === true && body.data !== null && body.data !== undefined) {
                // 返回订单 ID 只表示 Lua 已接受且发送 MQ 的调用返回，最终仍要与 RabbitMQ/MySQL 对账。
                acceptedResponses.add(1);
            } else if (body.success === false) {
                businessFailedResponses.add(1);
            } else {
                unexpectedResponses.add(1);
            }
        } catch (_) {
            unexpectedResponses.add(1);
        }
    } else if (response.status === 429) {
        limitedResponses.add(1);
    } else if (response.status === 503) {
        unavailableResponses.add(1);
    } else {
        unexpectedResponses.add(1);
    }

    check(response, {
        'status is 200, 429, or 503': (res) => [200, 429, 503].includes(res.status),
    });

    if (mode === 'user') {
        sleep(Number(__ENV.SLEEP_SECONDS || 0));
    }
}
