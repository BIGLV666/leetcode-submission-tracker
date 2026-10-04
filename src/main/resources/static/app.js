const API_KEY = 'leetcode-tracker-local';
const API_BASE = '/api/v1';

const state = {
    questions: [],
    monthlyReport: null,
    selectedMonth: currentMonthValue(),
    activeView: 'overview',
    selectedQuestion: null,
    cardFileName: null,
    cardBlobUrl: null
};

const elements = {
    syncStatus: document.querySelector('#sync-status'),
    errorBanner: document.querySelector('#error-banner'),
    updatedTime: document.querySelector('#updated-time'),
    refreshButton: document.querySelector('#refresh-button'),
    attemptedCount: document.querySelector('#attempted-count'),
    solvedCount: document.querySelector('#solved-count'),
    submissionCount: document.querySelector('#submission-count'),
    acceptedCount: document.querySelector('#accepted-count'),
    solveRate: document.querySelector('#solve-rate'),
    averageAttempts: document.querySelector('#average-attempts'),
    acceptedRate: document.querySelector('#accepted-rate'),
    problemSearch: document.querySelector('#problem-search'),
    statusFilter: document.querySelector('#status-filter'),
    problemResultCount: document.querySelector('#problem-result-count'),
    problemTableBody: document.querySelector('#problem-table-body'),
    problemEmpty: document.querySelector('#problem-empty'),
    monthPicker: document.querySelector('#month-picker'),
    previousMonth: document.querySelector('#previous-month'),
    nextMonth: document.querySelector('#next-month'),
    monthSubmissions: document.querySelector('#month-submissions'),
    monthAccepted: document.querySelector('#month-accepted'),
    monthAttempted: document.querySelector('#month-attempted'),
    monthSolved: document.querySelector('#month-solved'),
    monthActiveDays: document.querySelector('#month-active-days'),
    monthAcceptedRate: document.querySelector('#month-accepted-rate'),
    monthSolveRate: document.querySelector('#month-solve-rate'),
    activitySummary: document.querySelector('#activity-summary'),
    activityChart: document.querySelector('#activity-chart'),
    statusTotal: document.querySelector('#status-total'),
    languageTotal: document.querySelector('#language-total'),
    statusDistribution: document.querySelector('#status-distribution'),
    languageDistribution: document.querySelector('#language-distribution'),
    acceptedQuestionCount: document.querySelector('#accepted-question-count'),
    acceptedQuestionList: document.querySelector('#accepted-question-list'),
    detailDialog: document.querySelector('#problem-detail-dialog'),
    detailTitle: document.querySelector('#detail-title'),
    detailDifficulty: document.querySelector('#detail-difficulty'),
    detailSlug: document.querySelector('#detail-slug'),
    detailStatus: document.querySelector('#detail-status'),
    detailSubmissionCount: document.querySelector('#detail-submission-count'),
    detailProblemLink: document.querySelector('#detail-problem-link'),
    detailLoading: document.querySelector('#detail-loading'),
    detailEmpty: document.querySelector('#detail-empty'),
    submissionList: document.querySelector('#submission-list'),
    generateCardButton: document.querySelector('#generate-card'),
    exportCardButton: document.querySelector('#export-card'),
    cardPreview: document.querySelector('#card-preview'),
    cardPreviewImage: document.querySelector('#card-preview-image'),
    cardStatus: document.querySelector('#card-status')
};

function currentMonthValue() {
    const formatter = new Intl.DateTimeFormat('en-CA', {
        timeZone: 'Asia/Shanghai',
        year: 'numeric',
        month: '2-digit'
    });
    const parts = Object.fromEntries(formatter.formatToParts(new Date())
        .filter(part => part.type !== 'literal')
        .map(part => [part.type, part.value]));
    return `${parts.year}-${parts.month}`;
}

async function request(path) {
    const response = await fetch(`${API_BASE}${path}`, {
        headers: {'X-Tracker-Key': API_KEY}
    });
    if (!response.ok) {
        throw new Error(`请求失败：HTTP ${response.status}`);
    }
    return response.json();
}

async function loadOverview() {
    state.questions = await request('/stats/questions');
    renderOverview();
}

async function loadMonthlyReport() {
    const [year, month] = state.selectedMonth.split('-').map(Number);
    state.monthlyReport = await request(`/stats/monthly?year=${year}&month=${month}`);
    renderMonthlyReport();
}

async function refreshData() {
    setLoading(true);
    hideError();
    try {
        await Promise.all([
            loadOverview(),
            state.activeView === 'monthly' ? loadMonthlyReport() : Promise.resolve()
        ]);
        const now = new Date();
        elements.updatedTime.dateTime = now.toISOString();
        elements.updatedTime.textContent = now.toLocaleTimeString('zh-CN', {
            hour: '2-digit',
            minute: '2-digit'
        });
        elements.syncStatus.textContent = `已同步 ${state.questions.length} 道题`;
    } catch (error) {
        showError(error.message);
        elements.syncStatus.textContent = '读取失败';
    } finally {
        setLoading(false);
    }
}

function renderOverview() {
    const attempted = state.questions.length;
    const solved = state.questions.filter(question => question.solved).length;
    const submissions = sum(state.questions.map(question => question.submissionCount));
    const accepted = sum(state.questions.map(question => question.acceptedCount));

    elements.attemptedCount.textContent = attempted;
    elements.solvedCount.textContent = solved;
    elements.submissionCount.textContent = submissions;
    elements.acceptedCount.textContent = accepted;
    elements.solveRate.textContent = `通过率 ${percentage(solved, attempted)}`;
    elements.averageAttempts.textContent = `平均 ${attempted ? (submissions / attempted).toFixed(1) : '0'} 次/题`;
    elements.acceptedRate.textContent = `提交通过率 ${percentage(accepted, submissions)}`;
    renderProblemTable();
}

function renderProblemTable() {
    const keyword = elements.problemSearch.value.trim().toLowerCase();
    const status = elements.statusFilter.value;
    const questions = state.questions.filter(question => {
        const matchesKeyword = !keyword
            || question.title.toLowerCase().includes(keyword)
            || question.problemKey.toLowerCase().includes(keyword);
        const matchesStatus = status === 'all'
            || (status === 'solved' && question.solved)
            || (status === 'unsolved' && !question.solved);
        return matchesKeyword && matchesStatus;
    });

    elements.problemResultCount.textContent = `${questions.length} 道题`;
    elements.problemTableBody.replaceChildren(...questions.map(createProblemRow));
    elements.problemEmpty.hidden = questions.length > 0;
    elements.problemTableBody.closest('table').hidden = questions.length === 0;
}

function createProblemRow(question) {
    const row = document.createElement('tr');
    const difficulty = normalizeDifficulty(question.difficulty);
    row.className = 'problem-row';
    row.tabIndex = 0;
    row.setAttribute('role', 'button');
    row.setAttribute('aria-label', `查看 ${question.title} 的提交记录`);
    row.innerHTML = `
        <td><span class="status-badge ${question.solved ? 'solved' : ''}">${question.solved ? '已通过' : '未通过'}</span></td>
        <td>
            <span class="problem-title-line">
                <button class="problem-link" type="button">${escapeHtml(question.title)}</button>
                <a class="problem-external-link" href="${escapeAttribute(question.url)}" target="_blank" rel="noreferrer" aria-label="在力扣打开 ${escapeAttribute(question.title)}">↗</a>
            </span>
            <span class="problem-slug">${escapeHtml(question.problemKey)}</span>
        </td>
        <td><span class="difficulty-badge ${difficulty.className}">${difficulty.label}</span></td>
        <td>${question.submissionCount}</td>
        <td>${question.acceptedCount}</td>
        <td>${formatDateTime(question.firstAcceptedAt)}</td>
        <td>${formatDateTime(question.lastSubmittedAt)}</td>
    `;
    row.addEventListener('click', event => {
        if (!event.target.closest('.problem-external-link')) {
            openQuestionDetail(question);
        }
    });
    row.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            openQuestionDetail(question);
        }
    });
    return row;
}

function renderMonthlyReport() {
    const report = state.monthlyReport;
    if (!report) {
        return;
    }
    elements.monthSubmissions.textContent = report.totalSubmissions;
    elements.monthAccepted.textContent = report.acceptedSubmissions;
    elements.monthAttempted.textContent = report.attemptedQuestions;
    elements.monthSolved.textContent = report.solvedQuestions;
    elements.monthActiveDays.textContent = `活跃 ${report.activeDays} 天`;
    elements.monthAcceptedRate.textContent = `通过率 ${percentage(report.acceptedSubmissions, report.totalSubmissions)}`;
    elements.monthSolveRate.textContent = `题目通过率 ${percentage(report.solvedQuestions, report.attemptedQuestions)}`;
    elements.activitySummary.textContent = report.totalSubmissions
        ? `共 ${report.totalSubmissions} 次提交，${report.activeDays} 个活跃日`
        : '本月暂无提交';
    renderActivityChart(report.dailyActivity);
    renderDistribution(elements.statusDistribution, report.statusDistribution, 'status');
    renderDistribution(elements.languageDistribution, report.languageDistribution, 'language');
    elements.statusTotal.textContent = `${report.totalSubmissions} 次`;
    elements.languageTotal.textContent = `${report.totalSubmissions} 次`;
    renderAcceptedQuestions(report.acceptedQuestions);
}

function renderActivityChart(days) {
    const maximum = Math.max(1, ...days.map(day => day.submissions));
    elements.activityChart.style.setProperty('--day-count', days.length);
    elements.activityChart.replaceChildren(...days.map((day, index) => {
        const column = document.createElement('div');
        const totalHeight = day.submissions === 0 ? 2 : Math.max(8, day.submissions / maximum * 100);
        const acceptedHeight = day.submissions === 0 ? 0 : day.accepted / day.submissions * 100;
        const showLabel = index === 0 || (index + 1) % 5 === 0;
        column.className = 'day-column';
        column.title = `${day.date}：${day.submissions} 次提交，${day.accepted} 次 AC`;
        column.innerHTML = `
            <span class="day-value">${day.submissions}</span>
            <div class="bar-track">
                <div class="bar-total" style="height:${totalHeight}%">
                    <div class="bar-accepted" style="height:${acceptedHeight}%"></div>
                </div>
            </div>
            <span class="day-label">${showLabel ? Number(day.date.slice(-2)) : ''}</span>
        `;
        return column;
    }));
}

function renderDistribution(container, distribution, type) {
    const entries = Object.entries(distribution).sort((left, right) => right[1] - left[1]);
    const maximum = Math.max(1, ...entries.map(([, value]) => value));
    if (entries.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.innerHTML = '<strong>暂无数据</strong><span>本月还没有提交记录</span>';
        container.replaceChildren(empty);
        return;
    }
    container.replaceChildren(...entries.map(([label, value]) => {
        const row = document.createElement('div');
        const stateClass = type === 'status'
            ? label === 'Accepted' ? 'accepted' : 'failed'
            : '';
        row.className = 'distribution-row';
        row.innerHTML = `
            <span class="distribution-label" title="${escapeAttribute(label)}">${escapeHtml(formatLabel(label))}</span>
            <span class="distribution-track"><span class="distribution-fill ${stateClass}" style="width:${value / maximum * 100}%"></span></span>
            <span class="distribution-value">${value}</span>
        `;
        return row;
    }));
}

function renderAcceptedQuestions(questions) {
    elements.acceptedQuestionCount.textContent = `${questions.length} 道`;
    if (questions.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'empty-state';
        empty.innerHTML = '<strong>本月暂无通过题目</strong><span>继续提交后会自动更新</span>';
        elements.acceptedQuestionList.replaceChildren(empty);
        return;
    }
    elements.acceptedQuestionList.replaceChildren(...questions.map(question => {
        const item = document.createElement('article');
        item.className = 'accepted-item';
        const difficulty = normalizeDifficulty(question.difficulty);
        item.innerHTML = `
            <span class="accepted-check" aria-hidden="true">✓</span>
            <span class="accepted-question-main">
                <a href="${escapeAttribute(question.url)}" target="_blank" rel="noreferrer">${escapeHtml(question.title)}</a>
                <span class="difficulty-badge ${difficulty.className}">${difficulty.label}</span>
            </span>
            <time class="accepted-time">${formatReportTime(question.acceptedAt)}</time>
        `;
        return item;
    }));
}

async function openQuestionDetail(question) {
    state.selectedQuestion = question;
    const difficulty = normalizeDifficulty(question.difficulty);
    elements.detailTitle.textContent = question.title;
    elements.detailSlug.textContent = question.problemKey;
    elements.detailDifficulty.className = `difficulty-badge ${difficulty.className}`;
    elements.detailDifficulty.textContent = difficulty.label;
    elements.detailStatus.className = `status-badge ${question.solved ? 'solved' : ''}`;
    elements.detailStatus.textContent = question.solved ? '已通过' : '未通过';
    elements.detailSubmissionCount.textContent = `${question.submissionCount} 次提交`;
    elements.detailProblemLink.href = question.url;
    elements.detailLoading.hidden = false;
    elements.detailEmpty.hidden = true;
    elements.submissionList.replaceChildren();

    if (!elements.detailDialog.open) {
        elements.detailDialog.showModal();
    }

    try {
        const submissions = await request(`/questions/${encodeURIComponent(question.problemKey)}/submissions`);
        if (state.selectedQuestion?.problemKey !== question.problemKey) {
            return;
        }
        renderSubmissionList(submissions);
    } catch (error) {
        elements.detailLoading.textContent = error.message;
        elements.detailLoading.classList.add('error');
    }
}

function renderSubmissionList(submissions) {
    elements.detailLoading.hidden = true;
    elements.detailLoading.classList.remove('error');
    elements.detailLoading.textContent = '正在读取提交记录…';
    elements.detailEmpty.hidden = submissions.length > 0;
    elements.submissionList.replaceChildren(...submissions.map((submission, index) => {
        const accepted = submission.status === 'Accepted';
        const item = document.createElement('article');
        item.className = 'submission-item';
        item.innerHTML = `
            <div class="submission-head">
                <div>
                    <span class="submission-index">第 ${submissions.length - index} 次提交</span>
                    <strong class="submission-status ${accepted ? 'accepted' : 'failed'}">${escapeHtml(submission.status)}</strong>
                </div>
                <time>${formatDateTime(submission.submittedAt)}</time>
            </div>
            <div class="submission-meta">
                <span>${escapeHtml(formatLabel(submission.language))}</span>
                <span>耗时 ${escapeHtml(submission.runtime || '--')}</span>
                <span>内存 ${escapeHtml(submission.memory || '--')}</span>
                <span>${formatPassedTests(submission)}</span>
            </div>
            <details class="code-details">
                <summary>查看本次代码</summary>
                <pre><code>${escapeHtml(submission.code)}</code></pre>
            </details>
        `;
        return item;
    }));
}

function formatPassedTests(submission) {
    if (submission.passedTests == null || submission.totalTests == null) {
        return '测试点 --';
    }
    return `测试点 ${submission.passedTests}/${submission.totalTests}`;
}

function normalizeDifficulty(value) {
    const difficulty = String(value || '').toLowerCase();
    if (difficulty === 'easy' || difficulty === '简单') {
        return {label: '简单', className: 'easy'};
    }
    if (difficulty === 'medium' || difficulty === '中等') {
        return {label: '中等', className: 'medium'};
    }
    if (difficulty === 'hard' || difficulty === '困难') {
        return {label: '困难', className: 'hard'};
    }
    return {label: '未知', className: 'unknown'};
}

function setActiveView(viewName) {
    state.activeView = viewName;
    document.querySelectorAll('.tab-button').forEach(button => {
        button.classList.toggle('active', button.dataset.view === viewName);
    });
    document.querySelectorAll('.view').forEach(view => {
        const active = view.id === `${viewName}-view`;
        view.classList.toggle('active', active);
        view.hidden = !active;
    });
    if (viewName === 'monthly' && !state.monthlyReport) {
        setLoading(true);
        loadMonthlyReport()
            .catch(error => showError(error.message))
            .finally(() => setLoading(false));
    }
}

function shiftMonth(offset) {
    const [year, month] = state.selectedMonth.split('-').map(Number);
    const date = new Date(Date.UTC(year, month - 1 + offset, 1));
    state.selectedMonth = `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, '0')}`;
    elements.monthPicker.value = state.selectedMonth;
    state.monthlyReport = null;
    resetCardState();
    setLoading(true);
    loadMonthlyReport()
        .catch(error => showError(error.message))
        .finally(() => setLoading(false));
}

function monthParams() {
    const [year, month] = state.selectedMonth.split('-').map(Number);
    return {year, month};
}

function resetCardState() {
    state.cardFileName = null;
    if (state.cardBlobUrl) {
        URL.revokeObjectURL(state.cardBlobUrl);
        state.cardBlobUrl = null;
    }
    elements.cardPreview.hidden = true;
    elements.cardPreviewImage.removeAttribute('src');
    elements.cardStatus.textContent = '';
    elements.exportCardButton.disabled = true;
    elements.generateCardButton.disabled = false;
    elements.generateCardButton.textContent = '生成月报卡片';
}

async function generateCard() {
    elements.generateCardButton.disabled = true;
    elements.generateCardButton.textContent = '生成中…';
    hideError();
    try {
        const {year, month} = monthParams();
        const response = await fetch(`${API_BASE}/report/card/${year}/${month}`, {
            method: 'POST',
            headers: {'X-Tracker-Key': API_KEY}
        });
        if (!response.ok) {
            throw new Error(`生成失败：HTTP ${response.status}`);
        }
        const card = await response.json();
        state.cardFileName = card.fileName;
        await loadCardPreview();
        elements.cardStatus.textContent = `已生成 ${card.fileName}，点击“导出 PNG”保存`;
        elements.exportCardButton.disabled = false;
    } catch (error) {
        showError(error.message);
    } finally {
        elements.generateCardButton.disabled = false;
        elements.generateCardButton.textContent = '生成月报卡片';
    }
}

async function loadCardPreview() {
    const {year, month} = monthParams();
    const response = await fetch(`${API_BASE}/report/card/${year}/${month}/png`, {
        headers: {'X-Tracker-Key': API_KEY}
    });
    if (!response.ok) {
        throw new Error(`读取卡片失败：HTTP ${response.status}`);
    }
    const blob = await response.blob();
    if (state.cardBlobUrl) {
        URL.revokeObjectURL(state.cardBlobUrl);
    }
    state.cardBlobUrl = URL.createObjectURL(blob);
    elements.cardPreviewImage.src = state.cardBlobUrl;
    elements.cardPreview.hidden = false;
}

async function exportCard() {
    if (!state.cardFileName) {
        return;
    }
    hideError();
    try {
        if (!state.cardBlobUrl) {
            await loadCardPreview();
        }
        const link = document.createElement('a');
        link.href = state.cardBlobUrl;
        link.download = state.cardFileName;
        link.click();
    } catch (error) {
        showError(error.message);
    }
}

function setLoading(loading) {
    elements.refreshButton.disabled = loading;
    elements.refreshButton.textContent = loading ? '…' : '↻';
}

function showError(message) {
    elements.errorBanner.textContent = message;
    elements.errorBanner.hidden = false;
}

function hideError() {
    elements.errorBanner.hidden = true;
}

function percentage(part, total) {
    return total ? `${Math.round(part / total * 100)}%` : '0%';
}

function sum(values) {
    return values.reduce((total, value) => total + Number(value || 0), 0);
}

function formatDateTime(value, includeDate = true) {
    if (!value) {
        return '--';
    }
    const date = new Date(value.endsWith('Z') ? value : `${value}Z`);
    return date.toLocaleString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        month: includeDate ? '2-digit' : undefined,
        day: includeDate ? '2-digit' : undefined,
        hour: '2-digit',
        minute: '2-digit',
        hour12: false
    });
}

function formatReportTime(value) {
    return value ? value.slice(11, 16) : '--';
}

function formatLabel(label) {
    const labels = {
        golang: 'Go',
        java: 'Java',
        python3: 'Python',
        cpp: 'C++'
    };
    return labels[label] || label;
}

function escapeHtml(value) {
    return String(value)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function escapeAttribute(value) {
    return escapeHtml(value);
}

document.querySelectorAll('.tab-button').forEach(button => {
    button.addEventListener('click', () => setActiveView(button.dataset.view));
});
elements.refreshButton.addEventListener('click', refreshData);
elements.problemSearch.addEventListener('input', renderProblemTable);
elements.statusFilter.addEventListener('change', renderProblemTable);
elements.previousMonth.addEventListener('click', () => shiftMonth(-1));
elements.nextMonth.addEventListener('click', () => shiftMonth(1));
elements.monthPicker.addEventListener('change', event => {
    if (!event.target.value) {
        return;
    }
    state.selectedMonth = event.target.value;
    state.monthlyReport = null;
    resetCardState();
    shiftMonth(0);
});

elements.generateCardButton.addEventListener('click', generateCard);
elements.exportCardButton.addEventListener('click', exportCard);

elements.monthPicker.value = state.selectedMonth;
refreshData();
