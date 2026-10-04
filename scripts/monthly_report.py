# -*- coding: utf-8 -*-
"""月度刷题报告生成器：直连 tracker 数据库，输出浅色单文件 HTML 月报 + 社交分享卡片。

无 CDN、无外部资源，图表用内联 SVG/CSS 绘制。月报适合浏览器打开细看；
分享卡片固定 1080px 宽（4:5 比例），配合 scripts/card_shot.cjs 可导出 PNG 发社交媒体。

用法：
    PYTHONIOENCODING=utf-8 python scripts/monthly_report.py              # 当前月
    PYTHONIOENCODING=utf-8 python scripts/monthly_report.py 2026-09      # 指定月份
    node scripts/card_shot.cjs reports/share-2026-09.html                # 卡片导出 PNG
"""
import os
import sys
from collections import defaultdict
from datetime import date, datetime

import pymysql

DB = dict(host='127.0.0.1', port=3306, database='leetcode_tracker',
          user='root', password='changeme')

LANG_LABEL = {'golang': 'Go', 'java': 'Java', 'python3': 'Python',
              'mysql': 'MySQL', 'cpp': 'C++'}
DIFF_LABEL = {'Easy': '简单', 'Medium': '中等', 'Hard': '困难', None: '未标注'}
DIFF_COLOR = {'Easy': '#059669', 'Medium': '#d97706', 'Hard': '#dc2626', None: '#6b7280'}

REPORT_CSS = """
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  background: #f4f5f9; color: #111827;
  font-family: "Segoe UI", "Microsoft YaHei", system-ui, sans-serif;
  line-height: 1.6; padding: 40px 20px 60px;
}
.page { max-width: 1080px; margin: 0 auto; }
.hero { margin-bottom: 32px; }
.hero h1 {
  font-size: 30px; font-weight: 700; letter-spacing: .5px;
  background: linear-gradient(90deg, #4f46e5, #0891b2);
  -webkit-background-clip: text; background-clip: text; color: transparent;
}
.hero .sub { color: #9ca3af; font-size: 13px; margin-top: 4px; }
.grid-kpi { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; margin-bottom: 28px; }
.kpi {
  background: #fff; border: 1px solid #e7e8ee; border-radius: 14px;
  padding: 16px 14px 13px;
  box-shadow: 0 1px 2px rgba(17, 24, 39, .04);
}
.kpi .num { font-size: 26px; font-weight: 700; color: #111827; }
.kpi .num small { font-size: 13px; font-weight: 400; color: #9ca3af; margin-left: 2px; }
.kpi .lab { font-size: 12px; color: #6b7280; margin-top: 2px; }
.kpi .delta { font-size: 11px; margin-top: 5px; }
.kpi .delta.up { color: #059669; }
.kpi .delta.down { color: #dc2626; }
.kpi .delta.flat { color: #9ca3af; }
.grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.card {
  background: #fff; border: 1px solid #e7e8ee; border-radius: 14px; padding: 20px 22px;
  box-shadow: 0 1px 2px rgba(17, 24, 39, .04);
}
.card h2 {
  font-size: 14px; font-weight: 600; color: #374151; margin-bottom: 16px;
  letter-spacing: 1px;
}
.card h2 .hint { color: #9ca3af; font-weight: 400; letter-spacing: 0; margin-left: 8px; font-size: 12px; }
/* 日历热力图 */
.cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 7px; }
.cal .wd { text-align: center; font-size: 11px; color: #9ca3af; padding-bottom: 2px; }
.cal .cell {
  aspect-ratio: 1; border-radius: 8px; background: #f0f1f5;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; color: #9ca3af;
}
.cal .cell.off { background: transparent; }
.cal .cell.l1 { background: #dbeafe; color: #1d4ed8; }
.cal .cell.l2 { background: #93c5fd; color: #1e3a8a; }
.cal .cell.l3 { background: #6366f1; color: #eef2ff; }
.cal .cell.l4 { background: linear-gradient(135deg, #4f46e5, #0891b2); color: #fff; font-weight: 600; }
/* 横向条形 */
.bar-row { display: grid; grid-template-columns: 120px 1fr 64px; gap: 10px; align-items: center; margin-bottom: 11px; }
.bar-row:last-child { margin-bottom: 0; }
.bar-row .name { font-size: 13px; color: #374151; text-align: right; }
.bar-row .track { height: 12px; background: #f0f1f5; border-radius: 6px; overflow: hidden; }
.bar-row .fill { height: 100%; border-radius: 6px; }
.bar-row .val { font-size: 12px; color: #6b7280; }
.bar-row .val b { color: #111827; font-size: 13px; }
/* 卡壳榜 */
.stuck-item { display: flex; align-items: center; gap: 12px; padding: 9px 0; border-bottom: 1px solid #f0f1f5; }
.stuck-item:last-child { border-bottom: none; }
.stuck-item .rank {
  width: 26px; height: 26px; border-radius: 8px; flex: none;
  background: #fef3c7; color: #b45309; font-size: 12px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.stuck-item .info { flex: 1; min-width: 0; }
.stuck-item .t { font-size: 13px; color: #111827; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.stuck-item .t a { color: inherit; text-decoration: none; }
.stuck-item .t a:hover { color: #4f46e5; }
.stuck-item .meta { font-size: 11px; color: #9ca3af; margin-top: 1px; }
.badge { font-size: 11px; padding: 2px 9px; border-radius: 999px; flex: none; }
.badge.ok { color: #047857; background: #d1fae5; }
.badge.no { color: #b91c1c; background: #fee2e2; }
/* AC 清单表 */
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th {
  text-align: left; color: #9ca3af; font-weight: 500; font-size: 12px;
  padding: 8px 10px; border-bottom: 1px solid #e7e8ee;
}
td { padding: 8px 10px; border-bottom: 1px solid #f0f1f5; color: #374151; }
tr:last-child td { border-bottom: none; }
td a { color: #111827; text-decoration: none; }
td a:hover { color: #4f46e5; }
td .dim { color: #9ca3af; font-size: 12px; }
.star { color: #d97706; margin-left: 6px; }
.donut-wrap { display: flex; align-items: center; gap: 22px; }
.legend { display: flex; flex-direction: column; gap: 8px; }
.legend .li { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #374151; }
.legend .dot { width: 10px; height: 10px; border-radius: 3px; }
.legend .cnt { color: #9ca3af; font-size: 12px; }
.donut-center { font-size: 22px; font-weight: 700; fill: #111827; }
.donut-sub { font-size: 10px; fill: #9ca3af; }
.footer { color: #b0b4be; font-size: 12px; text-align: center; margin-top: 32px; }
@media (max-width: 860px) {
  .grid-kpi { grid-template-columns: repeat(3, 1fr); }
  .grid-2 { grid-template-columns: 1fr; }
}
"""


PALETTE = ['#6366f1', '#06b6d4', '#10b981', '#f59e0b', '#ec4899', '#84cc16']


def month_range(year, month):
    start = date(year, month, 1)
    end = date(year + 1, 1, 1) if month == 12 else date(year, month + 1, 1)
    return start, end


def fetch_rows(cur, start, end):
    cur.execute("""
        SELECT s.submitted_at, s.status, s.language, q.id, q.title, q.url, q.difficulty
        FROM tracker_submission s JOIN tracker_question q ON q.id = s.question_id
        WHERE s.submitted_at >= %s AND s.submitted_at < %s
        ORDER BY s.submitted_at
    """, (start, end))
    return cur.fetchall()


def aggregate(rows):
    """把一个月的提交行聚合月报需要的所有视图。"""
    by_q = defaultdict(lambda: {'subs': 0, 'ac': 0, 'fails': 0, 'types': set(),
                                'langs': set(), 'title': '', 'url': '',
                                'diff': None, 'first_ac': None, 'last_ac': None, 'last_fail': None})
    status_cnt = defaultdict(int)
    lang_cnt = defaultdict(int)
    daily = defaultdict(lambda: {'subs': 0, 'ac': 0})
    for ts, status, lang, qid, title, url, diff in rows:
        d = by_q[qid]
        d.update(title=title, url=url, diff=diff, langs=d['langs'] | {lang})
        d['subs'] += 1
        status_cnt[status] += 1
        lang_cnt[lang] += 1
        key = ts.date()
        daily[key]['subs'] += 1
        if status == 'Accepted':
            d['ac'] += 1
            daily[key]['ac'] += 1
            if d['first_ac'] is None:
                d['first_ac'] = ts
            d['last_ac'] = ts
        else:
            if status != 'Compile Error':
                d['fails'] += 1
                d['types'].add(status)
            d['last_fail'] = ts
    return by_q, status_cnt, lang_cnt, daily


def month_stats(rows):
    by_q, status_cnt, lang_cnt, daily = aggregate(rows)
    solved_q = [d for d in by_q.values() if d['ac'] > 0]
    return {
        'by_q': by_q, 'status_cnt': status_cnt, 'lang_cnt': lang_cnt, 'daily': daily,
        'total': len(rows), 'attempted': len(by_q), 'solved': len(solved_q),
        'solved_q': solved_q, 'active_days': len(daily),
        'first_try': sum(1 for d in solved_q if d['subs'] == 1),
        'ac_rate': sum(d['ac'] for d in by_q.values()) / len(rows) * 100 if rows else 0,
    }


def delta_html(now, prev):
    if prev is None:
        return '<div class="delta flat">—</div>'
    if now > prev:
        return f'<div class="delta up">▲ 较上月 +{now - prev}</div>'
    if now < prev:
        return f'<div class="delta down">▼ 较上月 -{prev - now}</div>'
    return '<div class="delta flat">— 与上月持平</div>'


def kpi_card(num, unit, label, delta):
    return (f'<div class="kpi"><div class="num">{num}<small>{unit}</small></div>'
            f'<div class="lab">{label}</div>{delta}</div>')


def calendar_html(year, month, daily, prefix=''):
    """prefix 用于分享卡片里放大版日历的样式（卡片 CSS 用 .share .cal 级联，无需前缀类）。"""
    wd_cn = ['一', '二', '三', '四', '五', '六', '日']
    cells = [f'<div class="wd">{w}</div>' for w in wd_cn]
    first_wd = date(year, month, 1).weekday()      # 周一=0
    days_in_month = month_range(year, month)[1] - date(year, month, 1)
    cells += ['<div class="cell off"></div>'] * first_wd
    for day in range(1, days_in_month.days + 1):
        d = date(year, month, day)
        info = daily.get(d)
        n = info['subs'] if info else 0
        if n == 0:
            cls = ''
        elif n <= 2:
            cls = ' l1'
        elif n <= 5:
            cls = ' l2'
        elif n <= 8:
            cls = ' l3'
        else:
            cls = ' l4'
        tip = f'{d}：提交 {n} 次' + (f'，AC {info["ac"]} 次' if info else '')
        cells.append(f'<div class="cell{cls}" title="{tip}">{day}</div>')
    return f'<div class="{prefix}cal">' + ''.join(cells) + '</div>'


def bars_html(counter, label_fn, color_fn, total=None):
    items = sorted(counter.items(), key=lambda kv: (-kv[1], str(kv[0])))
    total = total or sum(cnt for _, cnt in items)
    parts = []
    for key, cnt in items:
        pct = cnt / total * 100 if total else 0
        parts.append(
            f'<div class="bar-row"><div class="name">{label_fn(key)}</div>'
            f'<div class="track"><div class="fill" style="width:{pct:.1f}%;background:{color_fn(key)}"></div></div>'
            f'<div class="val"><b>{cnt}</b> · {pct:.0f}%</div></div>')
    return ''.join(parts)


def donut_svg(counter):
    items = sorted(counter.items(), key=lambda kv: (-kv[1], str(kv[0])))
    total = sum(cnt for _, cnt in items) or 1
    r, c = 62, 2 * 3.14159 * 62
    offset, segs, legend = 0, [], []
    for i, (lang, cnt) in enumerate(items):
        frac = cnt / total
        color = PALETTE[i % len(PALETTE)]
        segs.append(f'<circle r="{r}" cx="80" cy="80" fill="none" stroke="{color}" '
                    f'stroke-width="20" stroke-dasharray="{frac * c:.1f} {c:.1f}" '
                    f'stroke-dashoffset="{-offset * c:.1f}" transform="rotate(-90 80 80)"/>')
        label = LANG_LABEL.get(lang, lang)
        legend.append(f'<div class="li"><span class="dot" style="background:{color}"></span>'
                      f'{label}<span class="cnt">{cnt} 次 · {frac * 100:.0f}%</span></div>')
        offset += frac
    return (f'<div class="donut-wrap"><svg width="160" height="160" viewBox="0 0 160 160">'
            f'<circle r="{r}" cx="80" cy="80" fill="none" stroke="#f0f1f5" stroke-width="20"/>'
            + ''.join(segs) +
            f'<text x="80" y="78" text-anchor="middle" class="donut-center">{total}</text>'
            f'<text x="80" y="96" text-anchor="middle" class="donut-sub">总提交</text></svg>'
            f'<div class="legend">' + ''.join(legend) + '</div></div>')


def stuck_html(by_q):
    stuck = sorted((d for d in by_q.values() if d['fails'] > 0),
                   key=lambda d: (-d['fails'], d['title']))
    parts = []
    for i, d in enumerate(stuck[:8], 1):
        ok = d['ac'] > 0 and (d['last_fail'] is None or d['last_ac'] > d['last_fail'])
        badge = ('<span class="badge ok">已攻克</span>' if ok
                 else '<span class="badge no">未攻克</span>')
        types = '、'.join(sorted(d['types']))
        parts.append(
            f'<div class="stuck-item"><div class="rank">{i}</div>'
            f'<div class="info"><div class="t"><a href="{d["url"]}" target="_blank">{d["title"]}</a></div>'
            f'<div class="meta">{DIFF_LABEL[d["diff"]]} · 失败 {d["fails"]} 次（{types}）</div></div>{badge}</div>')
    return ''.join(parts) or '<div class="dim">本月没有卡壳的题，太稳了。</div>'


def solved_table_html(by_q):
    solved = sorted((d for d in by_q.values() if d['ac'] > 0),
                    key=lambda d: d['first_ac'])
    rows = []
    for d in solved:
        star = '<span class="star" title="一次提交通过">★</span>' if d['subs'] == 1 else ''
        langs = ' / '.join(sorted(LANG_LABEL.get(l, l) for l in d['langs']))
        rows.append(
            f'<tr><td><a href="{d["url"]}" target="_blank">{d["title"]}</a>{star}</td>'
            f'<td style="color:{DIFF_COLOR[d["diff"]]}">{DIFF_LABEL[d["diff"]]}</td>'
            f'<td>{langs}</td><td>{d["subs"]}</td>'
            f'<td class="dim">{d["first_ac"]:%m-%d %H:%M}</td></tr>')
    head = '<tr><th>题目</th><th>难度</th><th>语言</th><th>提交</th><th>首次 AC</th></tr>'
    return (f'<table><thead>{head}</thead><tbody>' + ''.join(rows) + '</tbody></table>')


def build_html(year, month, stats, prev_stats):
    status_label = {'Accepted': '通过', 'Wrong Answer': '答案错误',
                    'Time Limit Exceeded': '超时', 'Runtime Error': '运行错误',
                    'Memory Limit Exceeded': '内存超限', 'Output Limit Exceeded': '输出超限',
                    'Compile Error': '编译错误'}
    status_color = {'Accepted': '#10b981', 'Wrong Answer': '#f87171',
                    'Time Limit Exceeded': '#f59e0b', 'Runtime Error': '#ec4899',
                    'Memory Limit Exceeded': '#8b5cf6', 'Output Limit Exceeded': '#06b6d4',
                    'Compile Error': '#9ca3af'}
    diff_cnt = defaultdict(int)
    for d in stats['solved_q']:
        diff_cnt[d['diff']] += 1
    ft_rate = stats['first_try'] / stats['solved'] * 100 if stats['solved'] else 0
    p = prev_stats
    p_ft = p['first_try'] / p['solved'] * 100 if p and p['solved'] else None

    kpis = ''.join([
        kpi_card(stats['total'], '', '提交次数',
                 delta_html(stats['total'], p['total'] if p else None)),
        kpi_card(stats['attempted'], '', '做题数',
                 delta_html(stats['attempted'], p['attempted'] if p else None)),
        kpi_card(stats['solved'], '', 'AC 题数',
                 delta_html(stats['solved'], p['solved'] if p else None)),
        kpi_card(f'{ft_rate:.0f}', '%', '一发即中率',
                 delta_html(round(ft_rate), round(p_ft) if p_ft is not None else None)),
        kpi_card(stats['active_days'], '', '活跃天数',
                 delta_html(stats['active_days'], p['active_days'] if p else None)),
        kpi_card(f'{stats["ac_rate"]:.0f}', '%', '提交 AC 率',
                 delta_html(round(stats['ac_rate']),
                            round(p['ac_rate']) if p else None)),
    ])

    month_cn = f'{year} 年 {month} 月'
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>刷题月报 · {month_cn}</title>
<style>{REPORT_CSS}</style>
</head>
<body>
<div class="page">
  <div class="hero">
    <h1>刷题月报 · {month_cn}</h1>
    <div class="sub">数据来自本地 LeetCode Submission Tracker · 生成于 {datetime.now():%Y-%m-%d %H:%M}</div>
  </div>

  <div class="grid-kpi">{kpis}</div>

  <div class="grid-2">
    <div class="card">
      <h2>打卡日历<span class="hint">颜色越亮，当天提交越多</span></h2>
      {calendar_html(year, month, stats['daily'])}
    </div>
    <div class="card">
      <h2>判题结果<span class="hint">共 {stats['total']} 次提交</span></h2>
      {bars_html(stats['status_cnt'], lambda k: status_label.get(k, k), lambda k: status_color.get(k, '#6366f1'))}
    </div>
  </div>

  <div class="grid-2">
    <div class="card">
      <h2>语言分布<span class="hint">按提交次数</span></h2>
      {donut_svg(stats['lang_cnt'])}
    </div>
    <div class="card">
      <h2>攻克题目难度<span class="hint">本月 AC 的 {stats['solved']} 题</span></h2>
      {bars_html(diff_cnt, lambda k: DIFF_LABEL[k], lambda k: DIFF_COLOR[k], total=stats['solved'] or 1)}
    </div>
  </div>

  <div class="card" style="margin-bottom:16px">
    <h2>卡壳榜<span class="hint">本月失败提交最多的题，失败后已 AC 记为已攻克</span></h2>
    {stuck_html(stats['by_q'])}
  </div>

  <div class="card">
    <h2>本月攻克清单<span class="hint">{stats['solved']} 题 · ★ 为一次提交通过</span></h2>
    {solved_table_html(stats['by_q'])}
  </div>

  <div class="footer">LeetCode Submission Tracker · 本地生成</div>
</div>
</body>
</html>"""



CARD_CSS = """
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  background: #f2e9dc; color: #4b3f33;
  font-family: "Segoe UI", "Microsoft YaHei", system-ui, sans-serif;
  display: flex; justify-content: center; padding: 24px 0;
}
.share {
  width: 1080px; background: #fdf9f2; border-radius: 32px; overflow: hidden;
  border: 2px dashed #e5d5bb;
}
.share .inner { padding: 60px 64px 48px; }
.share .head { display: flex; align-items: center; gap: 18px; }
.share .head .emoji { font-size: 52px; }
.share h1 { font-size: 52px; font-weight: 800; }
.share .when { margin-left: auto; font-size: 24px; color: #b09a7c; font-weight: 700; }
.share .sub { margin-top: 10px; font-size: 23px; color: #8a7a63; }
.share .pills { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; margin: 38px 0 8px; }
.share .pill { border-radius: 999px; padding: 26px 10px 22px; text-align: center; border: 2px dashed; }
.share .pill .num { font-size: 56px; font-weight: 800; line-height: 1.15; }
.share .pill .lab { font-size: 21px; margin-top: 2px; font-weight: 600; }
.share .pill.c1 { background: #ffe9e2; border-color: #ff8f70; color: #c2410c; }
.share .pill.c2 { background: #fff3d6; border-color: #eec253; color: #92600a; }
.share .pill.c3 { background: #e8f3dd; border-color: #95bd7a; color: #3f6212; }
.share .sec { font-size: 22px; font-weight: 800; margin: 40px 0 18px; }
.share .cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 12px; }
.share .cal .wd { text-align: center; font-size: 18px; color: #c4b190; font-weight: 700; }
.share .cal .cell {
  aspect-ratio: 1; border-radius: 16px; border: 2px dashed #e8dcc6;
  display: flex; align-items: center; justify-content: center;
  font-size: 19px; color: #c4b190; background: transparent;
}
.share .cal .cell.off { border: none; }
.share .cal .cell.l1 { background: #ffe4cc; border: 2px solid #ffd0a8; color: #b45309; }
.share .cal .cell.l2 { background: #ffc4a3; border: none; color: #9a3412; }
.share .cal .cell.l3 { background: #ff9d6b; border: none; color: #7c2d12; }
.share .cal .cell.l4 { background: #ff6f4d; border: none; color: #fff; font-weight: 800; }
.share .stack { display: flex; height: 42px; border-radius: 999px; overflow: hidden; border: 2px dashed #e5d5bb; }
.share .stack .seg { display: flex; align-items: center; justify-content: center; font-size: 18px; color: #fff; font-weight: 700; white-space: nowrap; overflow: hidden; }
.share .legend { display: flex; flex-wrap: wrap; gap: 10px 26px; margin-top: 16px; }
.share .legend .li { display: flex; align-items: center; gap: 9px; font-size: 20px; font-weight: 600; }
.share .legend .dot { width: 14px; height: 14px; border-radius: 50%; }
.share .legend .cnt { color: #b09a7c; font-size: 17px; font-weight: 500; }
.share .stuck { display: flex; flex-direction: column; gap: 14px; }
.share .stuck .row {
  background: #fff4d6; border: 2px dashed #eec253; border-radius: 18px;
  padding: 16px 22px; display: flex; align-items: center; gap: 14px;
}
.share .stuck .t { font-size: 23px; font-weight: 700; flex: 1; }
.share .stuck .meta { font-size: 18px; color: #92600a; font-weight: 600; }
.share .foot {
  margin-top: 46px; padding-top: 22px; border-top: 2px dashed #e5d5bb;
  display: flex; justify-content: space-between; font-size: 20px; color: #b09a7c;
}
"""






def _stuck_list(stats):
    """按失败次数排序的前三道卡壳题，返回 (序号, 数据, 是否已攻克) 列表。"""
    stuck = sorted((d for d in stats['by_q'].values() if d['fails'] > 0),
                   key=lambda d: (-d['fails'], d['title']))[:3]
    out = []
    for i, d in enumerate(stuck, 1):
        ok = d['ac'] > 0 and (d['last_fail'] is None or d['last_ac'] > d['last_fail'])
        out.append((i, d, ok))
    return out


def build_card(year, month, stats):
    """手账贴纸风：奶油底、虚线框、暖色贴纸药丸。"""
    ft_rate = stats['first_try'] / stats['solved'] * 100 if stats['solved'] else 0
    days_in_month = month_range(year, month)[1] - date(year, month, 1)
    lang_items = sorted(stats['lang_cnt'].items(), key=lambda kv: (-kv[1], str(kv[0])))
    lang_total = sum(cnt for _, cnt in lang_items) or 1
    lang_colors = ['#ff6f4d', '#f5b73d', '#7fb069', '#6fa8dc', '#c785c8', '#a5a5a5']
    segs, legend = [], []
    for i, (lang, cnt) in enumerate(lang_items):
        frac = cnt / lang_total
        color = lang_colors[i % len(lang_colors)]
        text = (f'{LANG_LABEL.get(lang, lang)} {frac * 100:.0f}%' if frac >= 0.18 else '')
        segs.append(f'<div class="seg" style="width:{frac * 100:.1f}%;background:{color}">{text}</div>')
        legend.append(f'<div class="li"><span class="dot" style="background:{color}"></span>'
                      f'{LANG_LABEL.get(lang, lang)}<span class="cnt">{cnt} 次 · {frac * 100:.0f}%</span></div>')
    stuck_rows = [
        f'<div class="row"><div class="t">{d["title"]}</div>'
        f'<div class="meta">失败 {d["fails"]} 次 · {"已攻克 ✓" if ok else "继续加油"}</div></div>'
        for _, d, ok in _stuck_list(stats)]
    stuck_body = ('<div class="stuck">' + ''.join(stuck_rows) + '</div>'
                  if stuck_rows else '<div class="meta">本月零卡壳 💎</div>')
    return f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<style>{CARD_CSS}</style>
</head>
<body>
<div class="share" id="share">
  <div class="inner">
    <div class="head">
      <div class="emoji">📘</div>
      <h1>{month} 月刷题手账</h1>
      <div class="when">{year}.{month:02d}</div>
    </div>
    <div class="sub">打卡 {stats['active_days']}/{days_in_month.days} 天 · 提交 {stats['total']} 次 · 都是很棒的努力</div>

    <div class="pills">
      <div class="pill c1"><div class="num">{stats['solved']}</div><div class="lab">题 AC</div></div>
      <div class="pill c2"><div class="num">{stats['total']}</div><div class="lab">次提交</div></div>
      <div class="pill c3"><div class="num">{ft_rate:.0f}%</div><div class="lab">一发即中</div></div>
    </div>

    <div class="sec">🌟 打卡日历</div>
    {calendar_html(year, month, stats['daily'])}

    <div class="sec">💬 语言占比</div>
    <div class="stack">{''.join(segs)}</div>
    <div class="legend">{''.join(legend)}</div>

    <div class="sec">🌵 最难缠的题</div>
    {stuck_body}

    <div class="foot">
      <div>下个月也要天天打卡鸭 🦆</div>
    </div>
  </div>
</div>
</body>
</html>"""





def main():
    argv = sys.argv[1:]
    today = date.today()
    if argv:
        year, month = map(int, argv[0].split('-'))
    else:
        year, month = today.year, today.month
    out_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', 'reports'))
    os.makedirs(out_dir, exist_ok=True)

    start, end = month_range(year, month)
    p_start, p_end = month_range(*((year - 1, 12) if month == 1 else (year, month - 1)))
    conn = pymysql.connect(**DB)
    cur = conn.cursor()
    stats = month_stats(fetch_rows(cur, start, end))
    prev_stats = month_stats(fetch_rows(cur, p_start, p_end))
    conn.close()

    outputs = [
        (os.path.join(out_dir, f'monthly-{year}-{month:02d}.html'),
         build_html(year, month, stats, prev_stats)),
        (os.path.join(out_dir, f'share-{year}-{month:02d}.html'),
         build_card(year, month, stats)),
    ]
    for path, html in outputs:
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(html)
        print(f'已生成：{path}')


if __name__ == '__main__':
    main()
