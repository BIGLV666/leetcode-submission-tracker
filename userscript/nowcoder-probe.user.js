// ==UserScript==
// @name         Nowcoder Probe（牛客采集探针·临时）
// @namespace    dev.codewise.tracker
// @version      0.2.0
// @description  纯被动记录牛客答题/判题相关接口的请求与响应（白名单收紧版），导出样本供编写错题采集解析器；不发起任何额外请求
// @match        https://www.nowcoder.com/*
// @match        https://mc.nowcoder.com/*
// @match        https://nowcoder.com/*
// @grant        unsafeWindow
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_deleteValue
// @grant        GM_registerMenuCommand
// @grant        GM_setClipboard
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    const STORE_KEY = 'ncProbe.v1.entries';
    const MAX_ENTRIES = 500;
    const MAX_TOTAL_CHARS = 3_500_000;
    const MAX_BODY_CHARS = 250_000;
    const CAPTURE_PATHS = [
        '/api/sparta/test/detail',        // 试卷全部题目+选项
        '/api/sparta/test/report',        // 交卷报告：对错+我的选项+正确答案+解析
        '/api/sparta/question/detail',
        '/api/qmp/question/detail',       // 编程题题干/样例/限制
        '/api/service/judge/submit',      // 编程题提交（含代码）
        '/api/service/judge/submit-status' // 判题结果轮询（含报错信息）
    ];

    let entries = load();
    let dirty = false;
    let badge = null;
    let flushTimer = null;

    function load() {
        try {
            const parsed = JSON.parse(GM_getValue(STORE_KEY, '[]'));
            return Array.isArray(parsed) ? parsed : [];
        } catch (error) {
            return [];
        }
    }

    function persist() {
        trimToBudget();
        try {
            GM_setValue(STORE_KEY, JSON.stringify(entries));
        } catch (error) {
            console.error('[NC Probe] 保存失败', error);
        }
    }

    function schedulePersist() {
        dirty = true;
        if (flushTimer) {
            return;
        }
        flushTimer = setTimeout(() => {
            flushTimer = null;
            if (dirty) {
                dirty = false;
                persist();
            }
            updateBadge();
        }, 800);
    }

    function trimToBudget() {
        while (entries.length > MAX_ENTRIES) {
            entries.shift();
        }
        let total = entries.reduce((sum, item) => sum + item.resp.length + (item.req ? item.req.length : 0), 0);
        while (entries.length > 1 && total > MAX_TOTAL_CHARS) {
            const removed = entries.shift();
            total -= removed.resp.length + (removed.req ? removed.req.length : 0);
        }
    }

    function truncate(text) {
        return text.length > MAX_BODY_CHARS ? text.slice(0, MAX_BODY_CHARS) + '…[truncated]' : text;
    }

    function shouldCapture(rawUrl) {
        try {
            const url = new URL(rawUrl, location.href);
            return /(^|\.)nowcoder\.com$/.test(url.hostname)
                && CAPTURE_PATHS.some(p => url.pathname.endsWith(p));
        } catch (error) {
            return false;
        }
    }

    function dedupKey(url, req, resp) {
        const path = new URL(url, location.href).pathname;
        try {
            const json = JSON.parse(resp);
            const id = json?.data?.id ?? json?.data?.testId ?? '';
            if (id) {
                return path + '|' + id + '|' + (req || '');
            }
        } catch (error) {}
        return path + '|' + (req || '');
    }

    function looksJson(response) {
        const type = (response.headers?.get?.('content-type') || '').toLowerCase();
        return type.includes('json') || type === '';
    }

    function record({url, method, req, status, resp}) {
        const entry = {
            ts: new Date().toISOString(),
            href: location.href,
            pageTitle: document.title || '',
            url,
            method,
            status,
            req: req ? truncate(req) : '',
            resp: truncate(resp)
        };
        const key = dedupKey(url, entry.req, entry.resp);
        const dup = entries.findIndex(item => item.__key === key);
        if (dup >= 0) {
            entries.splice(dup, 1);
        }
        entry.__key = key;
        entries.push(entry);
        schedulePersist();
        updateBadge();
    }

    function captureText(url, method, req, status, text, maybeJson) {
        if (!shouldCapture(url) || !text) {
            return;
        }
        if (!maybeJson && !/^\s*[[{]/.test(text)) {
            return;
        }
        record({url, method, req, status, resp: text});
    }

    function installFetchInterceptor() {
        const originalFetch = unsafeWindow.fetch.bind(unsafeWindow);
        unsafeWindow.fetch = async function (input, init = {}) {
            const url = typeof input === 'string' ? input : input?.url;
            const method = init.method || (typeof input !== 'string' ? input.method : 'GET') || 'GET';
            let req = typeof init.body === 'string' ? init.body : null;
            if (!req && init.body instanceof URLSearchParams) {
                req = init.body.toString();
            }
            const response = await originalFetch(input, init);
            try {
                if (looksJson(response)) {
                    response.clone().text()
                        .then(text => captureText(url, method, req, response.status, text, true))
                        .catch(() => {});
                }
            } catch (error) {}
            return response;
        };
    }

    function installXhrInterceptor() {
        const Xhr = unsafeWindow.XMLHttpRequest;
        const originalOpen = Xhr.prototype.open;
        const originalSend = Xhr.prototype.send;

        Xhr.prototype.open = function (method, url, ...args) {
            this.__ncMethod = method;
            this.__ncUrl = url;
            return originalOpen.call(this, method, url, ...args);
        };
        Xhr.prototype.send = function (body) {
            let req = null;
            if (typeof body === 'string') {
                req = body;
            } else if (body instanceof URLSearchParams) {
                req = body.toString();
            } else if (body && typeof unsafeWindow.FormData !== 'undefined' && body instanceof unsafeWindow.FormData) {
                const pairs = [];
                body.forEach((value, key) => pairs.push(`${key}=${typeof value === 'string' ? value : '[file]'}`));
                req = pairs.join('&');
            }
            this.addEventListener('load', () => {
                try {
                    const type = (this.getResponseHeader('content-type') || '').toLowerCase();
                    const acceptable = type.includes('json') || (this.responseType === '' || this.responseType === 'text');
                    if (acceptable && this.responseText) {
                        captureText(this.__ncUrl, this.__ncMethod || 'GET', req, this.status, this.responseText, type.includes('json'));
                    }
                } catch (error) {}
            });
            return originalSend.call(this, body);
        };
    }

    function ensureBadge() {
        if (badge || !document.body) {
            return;
        }
        const style = document.createElement('style');
        style.textContent = `
            #nc-probe-badge {
                position: fixed; right: 14px; bottom: 14px; z-index: 2147483647;
                height: 28px; padding: 0 10px; border: 1px solid #f0a020; border-radius: 6px;
                background: #fff8ec; color: #8a5a00; font: 12px/26px system-ui, sans-serif;
                cursor: pointer; user-select: none; box-shadow: 0 2px 8px rgba(0,0,0,.12);
            }`;
        document.head.appendChild(style);
        badge = document.createElement('button');
        badge.id = 'nc-probe-badge';
        badge.type = 'button';
        badge.title = '点击导出探针样本';
        badge.addEventListener('click', exportJson);
        document.body.appendChild(badge);
        updateBadge();
    }

    function updateBadge() {
        ensureBadge();
        if (badge) {
            badge.textContent = `NC 探针 ${entries.length} 条`;
        }
    }

    function snapshot() {
        persist();
        return JSON.stringify({
            exportedAt: new Date().toISOString(),
            entries: entries.map(({__key, ...item}) => item)
        }, null, 1);
    }

    function exportJson() {
        const blob = new Blob([snapshot()], {type: 'application/json'});
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a');
        link.href = url;
        link.download = `nc-probe-${new Date().toISOString().slice(0, 10)}.json`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
    }

    GM_registerMenuCommand('导出探针样本 JSON', exportJson);
    GM_registerMenuCommand('复制探针样本到剪贴板', () => {
        GM_setClipboard(snapshot(), 'text');
    });
    GM_registerMenuCommand('清空探针记录', () => {
        entries = [];
        GM_deleteValue(STORE_KEY);
        updateBadge();
    });

    installFetchInterceptor();
    installXhrInterceptor();
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', updateBadge, {once: true});
    } else {
        updateBadge();
    }
    window.addEventListener('beforeunload', () => persist());
})();
