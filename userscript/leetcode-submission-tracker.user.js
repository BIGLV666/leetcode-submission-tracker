// ==UserScript==
// @name         LeetCode Submission Tracker
// @namespace    dev.codewise.tracker
// @version      0.1.2
// @description  在本地采集 LeetCode 中文站的提交代码与最终判题结果
// @match        https://leetcode.cn/problems/*
// @grant        unsafeWindow
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_registerMenuCommand
// @grant        GM_xmlhttpRequest
// @grant        GM_setClipboard
// @connect      127.0.0.1
// @connect      localhost
// @connect      *
// @run-at       document-start
// ==/UserScript==

(function () {
    'use strict';

    const DEFAULT_API_URL = 'http://127.0.0.1:8088/api/v1';
    const DEFAULT_API_KEY = 'leetcode-tracker-local';
    const PENDING_KEY = 'leetcodeTrackerPending';
    const RETRY_KEY = 'leetcodeTrackerRetryQueue';
    const API_URL_KEY = 'leetcodeTrackerApiUrl';
    const API_KEY_KEY = 'leetcodeTrackerApiKey';

    let badge;
    let syncing = false;
    let nativePageFetch;
    const pollingSubmissions = new Set();

    function readObject(key) {
        const value = GM_getValue(key, '{}');
        try {
            return JSON.parse(value);
        } catch (error) {
            return {};
        }
    }

    function writeObject(key, value) {
        GM_setValue(key, JSON.stringify(value));
    }

    function getApiUrl() {
        return String(GM_getValue(API_URL_KEY, DEFAULT_API_URL)).replace(/\/$/, '');
    }

    function getApiKey() {
        return String(GM_getValue(API_KEY_KEY, DEFAULT_API_KEY));
    }

    function updateBadge(text, state = 'idle') {
        ensureBadge();
        if (!badge) {
            return;
        }
        badge.textContent = text;
        badge.dataset.state = state;
        badge.title = state === 'error' ? '点击重试同步' : 'LeetCode 提交记录器';
    }

    function ensureBadge() {
        if (badge || !document.body) {
            return;
        }
        const style = document.createElement('style');
        style.textContent = `
            #leetcode-tracker-badge {
                position: fixed;
                right: 18px;
                bottom: 18px;
                z-index: 2147483647;
                height: 30px;
                padding: 0 11px;
                border: 1px solid #d0d7de;
                border-radius: 6px;
                background: #ffffff;
                color: #57606a;
                box-shadow: 0 2px 8px rgba(31, 35, 40, 0.12);
                font: 12px/28px system-ui, sans-serif;
                cursor: pointer;
                user-select: none;
            }
            #leetcode-tracker-badge[data-state="syncing"] { color: #0969da; border-color: #54aeff; }
            #leetcode-tracker-badge[data-state="success"] { color: #1a7f37; border-color: #4ac26b; }
            #leetcode-tracker-badge[data-state="error"] { color: #cf222e; border-color: #ff8182; }
        `;
        document.head.appendChild(style);
        badge = document.createElement('button');
        badge.id = 'leetcode-tracker-badge';
        badge.type = 'button';
        badge.textContent = 'LC 记录器';
        badge.addEventListener('click', flushRetryQueue);
        document.body.appendChild(badge);
    }

    function parseJson(value) {
        if (!value || typeof value !== 'string') {
            return null;
        }
        try {
            return JSON.parse(value);
        } catch (error) {
            return null;
        }
    }

    function getProblemSlug(url) {
        const match = String(url).match(/\/problems\/([^/?#]+)/);
        return match ? decodeURIComponent(match[1]) : '';
    }

    function getProblemTitle() {
        const selectors = [
            '[data-cy="question-title"]',
            'a[href^="/problems/"] h1',
            'div.text-title-large'
        ];
        for (const selector of selectors) {
            const element = document.querySelector(selector);
            if (element?.textContent?.trim()) {
                return element.textContent.trim();
            }
        }
        return document.title
            .replace(/\s*-\s*力扣.*$/, '')
            .replace(/^\d+\.\s*/, '')
            .trim() || getProblemSlug(location.href);
    }

    function getProblemDifficulty() {
        const classElement = document.querySelector('[class*="text-difficulty-"]');
        if (classElement) {
            const className = String(classElement.className);
            if (className.includes('difficulty-easy')) return 'Easy';
            if (className.includes('difficulty-medium')) return 'Medium';
            if (className.includes('difficulty-hard')) return 'Hard';
        }

        const difficultyLabels = new Map([
            ['简单', 'Easy'],
            ['中等', 'Medium'],
            ['困难', 'Hard'],
            ['Easy', 'Easy'],
            ['Medium', 'Medium'],
            ['Hard', 'Hard']
        ]);
        for (const element of document.querySelectorAll('div, span')) {
            const label = element.textContent?.trim();
            if (difficultyLabels.has(label) && element.children.length === 0) {
                return difficultyLabels.get(label);
            }
        }
        return null;
    }

    function rememberSubmission(platformSubmissionId, requestBody, requestUrl) {
        const problemKey = getProblemSlug(requestUrl) || getProblemSlug(location.href);
        const pending = readObject(PENDING_KEY);
        pending[String(platformSubmissionId)] = {
            platform: 'leetcode-cn',
            platformSubmissionId: String(platformSubmissionId),
            problemKey,
            title: getProblemTitle(),
            url: `https://leetcode.cn/problems/${problemKey}/`,
            difficulty: getProblemDifficulty(),
            language: requestBody.lang || requestBody.language || 'unknown',
            code: requestBody.typed_code || requestBody.code || '',
            submittedAt: new Date().toISOString()
        };
        writeObject(PENDING_KEY, pending);
        updateBadge('等待判题', 'syncing');
        pollSubmissionResult(platformSubmissionId);
    }

    async function pollSubmissionResult(platformSubmissionId) {
        const key = String(platformSubmissionId);
        if (!nativePageFetch || pollingSubmissions.has(key)) {
            return;
        }
        pollingSubmissions.add(key);
        try {
            for (let attempt = 0; attempt < 120; attempt++) {
                const pending = readObject(PENDING_KEY);
                if (!pending[key]) {
                    return;
                }
                try {
                    const response = await nativePageFetch(
                        `https://leetcode.cn/submissions/detail/${key}/check/`,
                        {credentials: 'include'}
                    );
                    if (response.ok) {
                        const result = await response.json();
                        if (result.state === 'SUCCESS') {
                            completeSubmission(key, result);
                            return;
                        }
                    }
                } catch (error) {
                    console.error('[LeetCode Tracker] 获取判题结果失败', error);
                }
                await new Promise(resolve => setTimeout(resolve, 1000));
            }
            updateBadge('结果获取超时', 'error');
        } finally {
            pollingSubmissions.delete(key);
        }
    }

    function resumePendingPolling() {
        Object.keys(readObject(PENDING_KEY)).forEach(pollSubmissionResult);
    }

    function completeSubmission(platformSubmissionId, result) {
        if (result.state !== 'SUCCESS') {
            return;
        }
        const key = String(platformSubmissionId);
        const pending = readObject(PENDING_KEY);
        const base = pending[key];
        if (!base) {
            return;
        }

        const record = {
            ...base,
            status: result.status_msg || result.status || 'Unknown',
            runtime: result.status_runtime || result.runtime || null,
            memory: result.status_memory || result.memory || null,
            passedTests: numberOrNull(result.total_correct),
            totalTests: numberOrNull(result.total_testcases)
        };
        const retryQueue = readObject(RETRY_KEY);
        retryQueue[key] = record;
        delete pending[key];
        writeObject(PENDING_KEY, pending);
        writeObject(RETRY_KEY, retryQueue);
        flushRetryQueue();
    }

    function numberOrNull(value) {
        const number = Number(value);
        return Number.isFinite(number) && number >= 0 ? number : null;
    }

    async function handleJsonResponse(url, method, requestBodyText, responseJson) {
        const submitMatch = String(url).match(/\/problems\/([^/?#]+)\/submit\/?(?:\?|$)/);
        if (submitMatch && method.toUpperCase() === 'POST') {
            const requestBody = parseJson(requestBodyText) || {};
            const submissionId = responseJson?.submission_id ?? responseJson?.submissionId;
            if (submissionId && (requestBody.typed_code || requestBody.code)) {
                rememberSubmission(submissionId, requestBody, url);
            }
            return;
        }

        const checkMatch = String(url).match(/\/submissions\/detail\/(\d+)\/check\/?(?:\?|$)/);
        if (checkMatch && responseJson) {
            completeSubmission(checkMatch[1], responseJson);
        }
    }

    function installFetchInterceptor() {
        const originalFetch = unsafeWindow.fetch.bind(unsafeWindow);
        nativePageFetch = originalFetch;
        unsafeWindow.fetch = async function (input, init = {}) {
            const url = typeof input === 'string' ? input : input.url;
            const method = init.method || (typeof input === 'string' ? 'GET' : input.method) || 'GET';
            let requestBody = typeof init.body === 'string' ? init.body : null;
            if (!requestBody && typeof input !== 'string') {
                try {
                    requestBody = await input.clone().text();
                } catch (error) {
                    requestBody = null;
                }
            }

            const response = await originalFetch(input, init);
            response.clone().json()
                .then(json => handleJsonResponse(url, method, requestBody, json))
                .catch(() => {});
            return response;
        };
    }

    function installXhrInterceptor() {
        const OriginalXhr = unsafeWindow.XMLHttpRequest;
        const originalOpen = OriginalXhr.prototype.open;
        const originalSend = OriginalXhr.prototype.send;

        OriginalXhr.prototype.open = function (method, url, ...args) {
            this.__trackerMethod = method;
            this.__trackerUrl = url;
            return originalOpen.call(this, method, url, ...args);
        };
        OriginalXhr.prototype.send = function (body) {
            this.__trackerBody = typeof body === 'string' ? body : null;
            this.addEventListener('load', () => {
                const json = parseJson(this.responseText);
                if (json) {
                    handleJsonResponse(
                        this.__trackerUrl,
                        this.__trackerMethod || 'GET',
                        this.__trackerBody,
                        json
                    );
                }
            });
            return originalSend.call(this, body);
        };
    }

    function postRecord(record) {
        return new Promise((resolve, reject) => {
            const apiKey = getApiKey();
            if (!apiKey) {
                reject(new Error('请先在油猴菜单中设置 API Key'));
                return;
            }
            GM_xmlhttpRequest({
                method: 'POST',
                url: `${getApiUrl()}/submissions`,
                headers: {
                    'Content-Type': 'application/json',
                    'X-Tracker-Key': apiKey
                },
                data: JSON.stringify(record),
                timeout: 10000,
                onload: response => {
                    if (response.status >= 200 && response.status < 300) {
                        resolve();
                    } else {
                        reject(new Error(`HTTP ${response.status}: ${response.responseText}`));
                    }
                },
                onerror: () => reject(new Error('无法连接采集服务')),
                ontimeout: () => reject(new Error('连接采集服务超时'))
            });
        });
    }

    async function flushRetryQueue() {
        if (syncing) {
            return;
        }
        const retryQueue = readObject(RETRY_KEY);
        const entries = Object.entries(retryQueue);
        if (entries.length === 0) {
            updateBadge('LC 已就绪');
            return;
        }

        syncing = true;
        updateBadge(`同步 ${entries.length} 条`, 'syncing');
        let failed = 0;
        for (const [submissionId, record] of entries) {
            try {
                await postRecord(record);
                delete retryQueue[submissionId];
                writeObject(RETRY_KEY, retryQueue);
            } catch (error) {
                failed++;
                console.error('[LeetCode Tracker]', error);
            }
        }
        syncing = false;
        updateBadge(
            failed === 0 ? '已记录' : `${failed} 条待重试`,
            failed === 0 ? 'success' : 'error'
        );
    }

    function requestQuestionStats() {
        const apiKey = getApiKey();
        if (!apiKey) {
            alert('请先设置 API Key');
            return;
        }
        GM_xmlhttpRequest({
            method: 'GET',
            url: `${getApiUrl()}/stats/questions`,
            headers: {'X-Tracker-Key': apiKey},
            onload: response => {
                if (response.status < 200 || response.status >= 300) {
                    alert(`读取失败：HTTP ${response.status}`);
                    return;
                }
                const stats = parseJson(response.responseText) || [];
                const solved = stats.filter(item => item.solved).length;
                GM_setClipboard(JSON.stringify(stats, null, 2), 'text');
                alert(`共记录 ${stats.length} 道题，已通过 ${solved} 道。统计 JSON 已复制。`);
            },
            onerror: () => alert('无法连接采集服务')
        });
    }

    GM_registerMenuCommand('设置采集服务地址', () => {
        const value = prompt('采集服务地址', getApiUrl());
        if (value?.trim()) {
            GM_setValue(API_URL_KEY, value.trim().replace(/\/$/, ''));
        }
    });
    GM_registerMenuCommand('设置 API Key', () => {
        const value = prompt('输入 TRACKER_API_KEY');
        if (value?.trim()) {
            GM_setValue(API_KEY_KEY, value.trim());
            flushRetryQueue();
        }
    });
    GM_registerMenuCommand('立即重试同步', flushRetryQueue);
    GM_registerMenuCommand('复制按题统计 JSON', requestQuestionStats);

    installFetchInterceptor();
    installXhrInterceptor();
    document.addEventListener('DOMContentLoaded', ensureBadge, {once: true});
    setInterval(flushRetryQueue, 30000);
    setTimeout(() => {
        flushRetryQueue();
        resumePendingPolling();
    }, 1500);
})();
